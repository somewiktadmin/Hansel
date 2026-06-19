// Hansel - GPS breadcrumb logger v0.987
// Copyright (C) 2026 GrimmsTales
// GNU General Public License v3 - https://www.gnu.org/licenses/gpl-3.0.html

package com.hansel.app;

import static com.hansel.app.MainActivity.mapView;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

/**
 * Hansel GPS breadcrumb logger - v0.987.
 * File format: NDJSON v0.931.
 *
 * <p>LocationService is the heart of Hansel.  It runs as an Android foreground
 * service, receives GPS fixes from FusedLocationProviderClient, filters them
 * through a live deadband, and writes surviving points to an NDJSON log file.
 * It also owns file rotation, monthly rollup, and the two background clocks
 * that drive them.</p>
 *
 * <p>File rotation happens two ways.  The primary path is scheduleTopOfHourRotation(),
 * a Handler clock that fires at HH:00:00.001 regardless of GPS fix rate.  The
 * secondary path is the hour key check in handleLocation(), which catches any
 * case where the clock misfired or the service was late to start.  Both paths
 * call rotateFile() and the double-rotation is neutralized by currentHourKey
 * being updated as the very first act of rotateFile().</p>
 *
 * <p>Prior to the clock-driven rotation, the hourly boundary was detected
 * entirely by handleLocation().  This worked in the field because
 * FusedLocationProvider was delivering fixes far more frequently than the
 * requested 30-second interval on the target devices.  That happy accident
 * is now replaced by a design that is device and context independent.</p>
 *
 * <p>Monthly rollup is handled by consolidateOldFiles(), called at startup
 * and again each day at noon by scheduleNoon().  Hour files older than
 * yesterday are consumed into a monthly canonical file and deleted.  Moving
 * consumed files to a ./backup/ subdirectory instead of deleting them is
 * a pending improvement.</p>
 *
 * <p>Future: LocationService and its file I/O are destined to move to a
 * standalone headless app.  The WebView UI will become a separate viewer
 * app (Gretel).  That split is post-v1.0.</p>
 *
 * TODO: Add BootReceiver so the service survives a phone reboot without
 *       requiring the user to open the app.
 * TODO: Move consumed hour files to ./backup/ instead of deleting them
 *       in consolidateOldFiles().
 * TODO: Version existing monthly files (slots 61-99) before overwriting
 *       in consolidateOldFiles().
 * TODO: consolidateOldFiles() still uses SAF (DocumentFile) rather than
 *       direct File access.  Low priority until the backup move is
 *       implemented.
 */
public class LocationService extends Service {

    /**
     * isFileOpen guards against double-open in openFile().  It is set true
     * when a file is successfully opened and false at the top of rotateFile()
     * before the old file is closed.  The asymmetry is intentional - false
     * before close means a racing openFile() call will proceed rather than
     * skip, which is the safe failure mode.
     */
    private boolean isFileOpen = false;

    /**
     * Singleton convenience set in onCreate() and cleared in onDestroy().
     * Things use this to call say() and rotateNow() and mark() directly.
     */
    public static LocationService instance;

    /** FusedLocationProviderClient - Google Play Services location provider. */
    FusedLocationProviderClient client;

    /**
     * Active location callback registered with FusedLocationProviderClient.
     * Retained as a field so it can be unregistered cleanly in onDestroy().
     * A new instance is created each time startGPS() is called - there is
     * no mechanism to call startGPS() twice, but if that ever changed it
     * would register a second listener without removing the first.
     * TODO: Add a removeLocationUpdates() guard at the top of startGPS().
     */
    LocationCallback callback;

    /** Active BufferedWriter for the current hour file.  Null when no file is open. */
    BufferedWriter writer;

    /**
     * Hour key of the currently open file, formatted yyyy-MM-dd_HH.
     * Initialized to empty string so the first GPS fix always triggers
     * rotateFile() via the mismatch check in handleLocation().
     */
    String currentHourKey = "";

    /** DocumentFile handle for the currently open log file. */
    DocumentFile currentFile;

    /**
     * Hansel logging interval in milliseconds.  Currently fixed at 30_000.
     * This value is also passed to FusedLocationProvider as a hint, but
     * the logging cadence is what actually matters.
     *
     * <p>This was once a user-selectable setting.  Values below 30_000 cause
     * duplicate timestamps.  Values above 30_000 degrade
     * audio/video sync and file rotation timing.  30 seconds is not a default,
     * it is the only working value at this stage of the project.</p>
     *
     * TODO: Remove the last_interval SharedPreference read in
     *       startLoggingDefault() - it will always be 30_000 and pretending
     *       otherwise is misleading.
     */
    int interval = 30000; //30000 30_000

    /**
     * Minimum GPS request interval in milliseconds.  500ms was tried and
     * produced duplicate yyyy-MM-dd_HH-mm-ss timestamps.  1000ms is the
     * minimum safe value for second-granular datetime fields.
     */
    private static final int INTERVAL_FLOOR_MS = 1000;

    /**
     * Live deadband filter window size.  N=20 was overkill, N=5 overreacts,
     * N=10 was field-tuned on the Big Island as the best balance.
     * TODO: Future: std deviation spike filter for Saddle Road cell-mode
     *       altitude thrashing.
     */
    private static final int DEADBAND_N = 10;

    /** Deadband suppression threshold in feet. */
    private static final double DEADBAND_FT = 35.0;

    /** Sliding window of raw latitude readings (degrees) for deadband calculation. */
    private final double[] deadbandLat = new double[DEADBAND_N];

    /** Sliding window of raw longitude readings (degrees) for deadband calculation. */
    private final double[] deadbandLon = new double[DEADBAND_N];

    /** Sliding window of altitude readings (feet) for deadband calculation. */
    private final double[] deadbandAlt = new double[DEADBAND_N];

    /** Number of readings written into the deadband windows so far. */
    private int deadbandCount = 0;

    /** True once the deadband windows have been filled at least once. */
    private boolean deadbandFull = false;

    /** Most recent Location fix, retained for reference.  Not currently used in output. */
    private Location lastLocation = null;

    /** Most recent speed in m/s from FusedLocationProvider.  Converted to MPH on write. */
    private float lastSpd = 0.0f;

    /** Most recent bearing in degrees from FusedLocationProvider. */
    private float lastCrs = 0.0f;

    /**
     * Drives the daily noon consolidation.  consolidateOldFiles() runs at
     * startup as a safety net for any files missed while the service was
     * down, then again each day at noon via this Handler when yesterday's
     * hour files are guaranteed cold.
     */
    private Handler  noonHandler     = new Handler(Looper.getMainLooper());
    private Runnable noonRunnable;

    /**
     * Drives hourly file rotation at HH:00:00.001, independent of GPS fix
     * rate.  Prior to this clock, rotation was detected by handleLocation()
     * noticing a changed hour key - accurate only by accident on the target
     * devices.  This clock makes it reliable.
     * TODO: Send completed hour files to a spool directory for a future
     *       cloud upload utility.
     */
    private Handler  hourHandler          = new Handler(Looper.getMainLooper());
    private Runnable hourRotationRunnable;

    /**
     * Schedules a file rotation at HH:00:00.001 of the next hour.  Assigns
     * a fresh hourRotationRunnable lambda each call so that hourHandler can
     * cancel it cleanly in onDestroy().
     *
     * <p>The lambda calls rotateFile() with the current hour key at fire
     * time, then reschedules itself for the following hour.  The +1ms offset
     * targets just past the hour boundary - close enough for audio/video sync
     * purposes, far enough to be reliably after rather than accidentally
     * before due to scheduler jitter.</p>
     *
     * <p>Called from rotateFile() after a new file is successfully opened.
     * Not called from rotateNow() - manual rotations do not disturb the
     * clock chain.</p>
     */
    private void scheduleTopOfHourRotation() {
        long now = System.currentTimeMillis();
        long next = ((now / 3600000L) + 1) * 3600000L + 1;
        hourRotationRunnable = () -> {
            say( "hourly scheduleTopOfHourRotation()" );
            rotateFile( getHourKey( System.currentTimeMillis() ) );
            scheduleTopOfHourRotation();
        };
        hourHandler.postDelayed(hourRotationRunnable, next - now);
    }

    /**
     * Returns true if the given altitude reading should be suppressed by the
     * live deadband filter.  Suppressed points are not written to the log
     * but are still sent to the UI via sendToUI().
     *
     * The filter maintains a sliding window of the last DEADBAND_N (10)
     * altitude readings.  Once the window is full, a reading is suppressed
     * if the window range (max minus min) is within DEADBAND_FT (35ft) and
     * the new reading is also within DEADBAND_FT of the window minimum.  In
     * plain terms: if the last 10 readings are all hovering in a 35ft band
     * and the new reading is in the same band, it is noise, not movement.
     *
     * A suppressed point is not noise - it is a redundant position.
     * Standing still on the rim of a crater is every bit as intentional as
     * walking toward it.  The deadband simply avoids writing the same
     * position repeatedly.  Notes always reset the deadband and are always
     * written, so a stationary mark is never lost.
     *
     * The window fills unconditionally - suppressed and unsuppressed
     * readings both enter the window.  This is intentional: the window
     * tracks what the GPS is actually reporting, not what was written.
     *
     * N=10 and 35ft were field-tuned on the Big Island.  N=20 was too
     * slow to react to real altitude changes.  N=5 overreacted to cell tower
     * altitude injection.  35ft covers typical stationary GPS drift without
     * swallowing real elevation changes on Saddle Road.
     *
     * Returns true if the given fix should be suppressed by the live deadband
     * filter.  Answers the question "have I moved in the last DEADBAND_N seconds?"
     *
     * New June 9, 2026:
     * Horizontal comparison uses 5-decimal-place rounding on lat and lon.
     * At 5 decimal places, 1 unit is ~3.6 feet at the equator and ~1.9 feet
     * at 57 deg N longitude.  This is sufficient to answer "have I moved"
     * without trig, constants, or projection math.  Altitude uses DEADBAND_FT
     * directly since feet are already the right unit.  Code simplicity wins.
     *
     * A fix is suppressed only when the window is full AND every entry in
     * the window has the same rounded lat, lon, and is within DEADBAND_FT of
     * the current altitude.  Any difference in the last 60 seconds, defeats
     * the deadband suppression.
     *
     * @param lat   raw latitude in decimal degrees.
     * @param lon   raw longitude in decimal degrees.
     * @param altFt altitude in feet.
     * @return true if the fix should be suppressed, false if it should be written.
     */
    private boolean deadbandSuppress(double lat, double lon, double altFt) {
        long rLat = Math.round(lat * 100000.0);
        long rLon = Math.round(lon * 100000.0);

        if (deadbandFull) {
            boolean allClose = true;
            for (int i = 0; i < DEADBAND_N; i++) {
                if (Math.round(deadbandLat[i] * 100000.0) != rLat ||
                        Math.round(deadbandLon[i] * 100000.0) != rLon ||
                        Math.abs(altFt - deadbandAlt[i]) > DEADBAND_FT) {
                    allClose = false;
                    break;
                }
            }
            if (allClose) return true;
        }
        int slot = deadbandCount % DEADBAND_N;
        deadbandLat[slot] = lat;
        deadbandLon[slot] = lon;
        deadbandAlt[slot] = altFt;
        deadbandCount++;
        if (deadbandCount >= DEADBAND_N) deadbandFull = true;
        return false;
    }

    /**
     * Resets the live deadband filter.  Called by mark() so that a note
     * always breaks through, and the filter starts fresh from that point.
     * All three window arrays are cleared together.
     */
    private void deadbandReset() {
        deadbandCount = 0;
        deadbandFull  = false;
    }

    /**
     * Returns an hour key string formatted yyyy-MM-dd_HH for the given
     * millisecond timestamp.  Used to detect hour boundaries in
     * handleLocation() and to name the current file slot in openFile().
     *
     * @param timeMillis millisecond timestamp, typically System.currentTimeMillis().
     * @return hour key string, e.g. "2026-06-03_14".
     */
    String getHourKey(long timeMillis) {
        return new SimpleDateFormat("yyyy-MM-dd_HH")
                .format(new Date(timeMillis));
    }

    /**
     * Updates the GPS fix request interval.  Enforces INTERVAL_FLOOR_MS
     * to prevent timestamp collisions and phone overheating.
     *
     * <p>The body of this method is guarded by if(false) and does not
     * execute.  Re-registering the location listener on every interval
     * change caused a double-listener bug.  The guard is the fix.  The
     * UI element that called this has been removed.  The method and its
     * floor logic are retained as a reference for when interval adjustment
     * is revisited post-v1.0.</p>
     *
     * TODO: Revisit as part of the Gretel/headless-Hansel split.  A proper
     *       interval change will require stopping and restarting the listener
     *       atomically.
     * @param newInterval requested interval in milliseconds.
     */
    public void updateInterval(int newInterval) {
        interval = Math.max(newInterval, INTERVAL_FLOOR_MS);
        if (client == null || callback == null) return;

        LocationRequest req = LocationRequest.create()
                .setInterval(interval)
                .setFastestInterval(interval)
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        if (false)
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                client.requestLocationUpdates(req, callback, Looper.getMainLooper());
            }
    }

    /**
     * rotateNow() was and is for the [STOP] button on the GUI, which doesn't
     * actually STOP the logging (it did in earliest versions) but rather rolls
     * the logfile to a new file, so that the current file can be copied to
     * a spool directory and uploaded to the cloud, where my ex-wife can read it,
     * V. Putin can read it, AOC can read it etc etc etc.
     *
     * But since the removal of the [START] button, this also triggers a new
     * file creation, every time.  The only way to stop logging is to force
     * stop this Hansel app.  Why?  Because this version is not for general
     * consumption.  It is for *ME* to use while hiking inside an active volcano
     * and no, I am not gonna stop to check the screen and this and that and
     * the other.  It has just got to work, no questions, no off switch, just work.
     *
     * It seemingly duplicates much of other functions, openFile() and rotateFile()
     * in particular, have tried to usurp these duties, and instead have caused
     * days of debugging, double file-opens, quintuple callbacks per second and
     * a wide variety of other nonsense.
     *
     * Do not conflate rotateFile() {the hourly thing} with rotateNow() {the GUI
     * button thing} and use a lot of dialog when thinking about changing anything
     * in this realm of the code.  Blood, sweat and tears, and that was just
     * from the AI - you can imagine the human suffering.
     *
     * @return File currentFile the newest newly opened logfile
     */
    public String rotateNow() {
        stopAndReport();
        openFile();
        return currentFile != null ? currentFile.getName() : "unknown";
    }

    /**
     * Retrieves the SAF tree URI stored in HanselPrefs and returns a
     * DocumentFile handle to the working directory.  Returns null and
     * toasts a message if no folder has been selected yet.
     *
     * <p>Called by openFile(), rotateFile(), and consolidateOldFiles()
     * every time they need the directory.  There is no cached handle -
     * each call goes back to SharedPreferences.  This is intentional:
     * the URI is stable once set and the overhead is negligible compared
     * to the file I/O that follows.</p>
     */
    private DocumentFile getTreeDir() {
        SharedPreferences prefs = getSharedPreferences(
                MainActivity.PREFS_NAME, MODE_PRIVATE);
        String uriString = prefs.getString(MainActivity.PREF_TREE_URI, null);
        if (uriString == null) {
            say("No folder selected - open app to pick one");
            return null;
        }
        return DocumentFile.fromTreeUri(this, Uri.parse(uriString));
    }

    /**
     * Sends a JSON string to the UI by calling the JavaScript function
     * onGPSUpdate() in the WebView.  Used by handleLocation() to push
     * each GPS fix to the map and display, whether or not the point is
     * written to the log file.
     *
     * Deadband-suppressed points are still sent here - the UI sees everything,
     * the log file sees only what survives the filter.  The JS side reformats
     * the raw JSON considerably before displaying it to the user.
     *
     * <p>The call is posted to the main looper because evaluateJavascript()
     * must run on the UI thread.  LocationService callbacks arrive on the
     * main looper already, but the post() is kept as an explicit guarantee
     * rather than an assumption.</p>
     */
    void sendToUI(String json) {
        if (MainActivity.webView == null) return;
        android.os.Handler h = new android.os.Handler(getMainLooper());
        h.post(() ->
                MainActivity.webView.evaluateJavascript(
                        "onGPSUpdate(" + JSONObject.quote(json) + ")",
                        null
                )
        );
    }

    /**
     * Loading AndroidStudio Bumblebee, LOGCAT refused to work for the first month,
     * until I learned that motorola makes you toggle a weird switch in developer
     * options just to be able to see your own debug messages.
     *
     *      LOGGER BUFFER SIZES = 1 MB (minimum, default is OFF.)
     *
     * So during the hell month, and especially during the initial project phase,
     * the only way to see any sort of debug message, was to do it myself.  Sending
     * 1/2 line of text to a named div id gets old REALLY quick.  So I created a
     * scrollbox of text, that quickly became the center of the universe for the
     * entire app.  User needs to see something?  Log it with say().
     *
     * Now, doing this from javascript has several challenges.  Because of webview
     * built into studio, it wasn't so bad to have javascript send to webappinterface
     * which sent to LocationService which sent to MainActivity to go through
     * index.html to get a message back to the scrollbox.  But suddently I had
     * a uniform way to generate a debug message from every layer of the project.
     *
     * Then one day, even LOGCAT started working, so these messages get duplicated
     * over there too, now.
     *
     * @param something - just don't say nuthin'
     */
    void say(String something) {
        if (MainActivity.webView == null) return;
        android.os.Handler h = new android.os.Handler(getMainLooper());
        h.post(() ->
                MainActivity.webView.evaluateJavascript(
                        "say(" + JSONObject.quote(something) + ")",
                        null
                )
        );
    }

    /**
     * Consolidation-pass deadband filter, applied when hour files are being
     * rolled into the monthly canonical file.  Stricter than the live filter
     * in deadbandSuppress() - window size is 5 instead of 10 - because the
     * points arriving here have already survived one deadband pass at capture
     * time.  Anything of urgence has already been [REPLAY]ed with the
     * button [72hr], now we want to diminish lag time aggressively.
     *
     * <p>Implemented as a static method taking explicit window state arrays
     * so that each source file gets its own independent filter state without
     * allocating a new object.  The arrays are allocated by the caller in
     * consolidateOldFiles() and reset between files.</p>
     *
     * <p>Notes hold the highest status in the file format - they are always
     * written unconditionally and always reset the deadband state on both
     * sides of them.  consolidateOldFiles() handles notes directly before
     * ever calling this method, so consolidateSuppress() only ever sees
     * plain trackpoints.</p>
     *
     * @param altFt  altitude in feet.
     * @param window sliding window of recent altitude readings.
     * @param count  number of readings written so far, wrapped in int[1].
     * @param full   true once the window has filled once, wrapped in boolean[1].
     * @return true if the point should be suppressed.
     */
    private static boolean consolidateSuppress(double altFt,
                                               double[] window, int[] count, boolean[] full) {
        if (full[0]) {
            double lo = window[0], hi = window[0];
            for (double v : window) {
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
            if ((hi - lo) <= DEADBAND_FT && Math.abs(altFt - lo) <= DEADBAND_FT)
                return true;
        }
        window[count[0] % 5] = altFt;
        count[0]++;
        if (count[0] >= 5) full[0] = true;
        return false;
    }

    /**
     * Consolidates old hour files into monthly canonical files.  Runs at
     * service startup and again each day at noon via scheduleNoon().
     *
     * <p>Files are skipped if they are from today, yesterday, or the day
     * before yesterday (72 hour live window to support the 72h replay
     * button), are already a monthly canonical file (name contains
     * "-00_00-00-00"), or are not .ndjson files.  MTP duplicate files
     * named "datetime.ndjson (1)" are accepted alongside their originals
     * and rolled in normally.</p>
     *
     * <p>For each eligible file, the target monthly file is named
     * yyyy-MM-00_00-00-00.ndjson.  The 00 day field is not a bug - it is
     * a deliberate exploitation of an impossible calendar value to mark
     * canonical monthly rollups.  It does not represent one day of
     * a given month, rather, it represents the WHOLE month.</p>
     *
     * <p>The loop makes no attempt to detect or handle month boundaries.
     * Each file's target monthly is derived purely from its own filename
     * prefix - a file named 2026-04-30_23-xx-xx.ndjson goes into
     * 2026-04-00_00-00-00.ndjson and a file named 2026-05-01_00-xx-xx.ndjson
     * goes into 2026-05-00_00-00-00.ndjson, with no special case needed.
     * The month boundary takes care of itself.  This is lazy programming
     * at its very best; it is the correct level of complexity for the
     * problem.  Twice as much code to handle exotic one-off conditions,
     * when I can rename my file from June 1st to be May 32nd and all works
     * just peachy.  Since this is programming practice for me and no
     * one else will EVER see this code, thwwwwwwpppt.</p>
     *
     * <p>Each source file gets its own fresh consolidation deadband state.
     * Notes are written unconditionally and reset the deadband.  The monthly
     * file gets a fresh header only if it is newly created - appending to an
     * existing monthly does not add a second header.</p>
     *
     * <p>After successful consolidation the source hour file is deleted.
     * Moving consumed files to a ./backup/ subdirectory instead of deleting
     * them is a pending improvement.</p>
     *
     * <p>Sorting and exact-line deduplication are not currently performed
     * during consolidation.  Originally this was intentional - data from
     * four phones was being combined and legitimate identical records from
     * separate devices were not to be removed.  That constraint no longer
     * applies.  The one-record-per-line NDJSON format makes this a natural
     * sort-and-uniq operation on the raw lines.</p>
     *
     * TODO: Sort lines and remove exact duplicate lines during consolidation.
     *       Standard sort-and-uniq semantics on the raw NDJSON lines.
     * TODO: Move consumed files to ./backup/ instead of deleting them.
     * TODO: Version existing monthly files before overwriting.  Backup slots
     *       are yyyy-MM-00_00-00-61.ndjson through yyyy-MM-00_00-00-99.ndjson.
     *       The seconds field 61-99 is deliberately outside the valid 00-59
     *       range so that any datetime parser will reject these as data files
     *       rather than silently misreading them as trackpoints.
     * TODO: Convert from SAF (DocumentFile) to direct File access, consistent
     *       with the rest of the file I/O in this class.
     *
     * TODO: Convert from SAF (DocumentFile) to direct File access.  SAF was
     *       used here because it was the available pattern at the time this
     *       method was written.  The rest of the file I/O in this class uses
     *       direct File access via MANAGE_ALL_FILES.  More critically, SAF
     *       cannot traverse subdirectories cleanly - DocumentFile.listFiles()
     *       only sees the top level of the tree URI.  The ./backup/ move
     *       implementation is blocked until this conversion is done.
     */
    void consolidateOldFiles() {
        DocumentFile dir = getTreeDir();
        if (dir == null) return;

        DocumentFile[] files = dir.listFiles();
        if (files == null || files.length == 0) return;

        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String yesterday = new SimpleDateFormat("yyyy-MM-dd")
                .format(new Date(System.currentTimeMillis() - 86400000L));
        String dayBefore = new SimpleDateFormat("yyyy-MM-dd")
                .format(new Date(System.currentTimeMillis() - 172800000L));

        int consolidated = 0;
        int deleted = 0;

        for (DocumentFile f : files) {
            String name = f.getName();
            if (name == null) continue;

            if ( ( !name.endsWith(".ndjson") ) &&
                    ( !name.endsWith(".ndjson (1)") ) ) continue;
            if (name.contains("-00_00-00-00")) continue;
            if (name.startsWith(today)) continue;
            if (name.startsWith(yesterday)) continue;
            if (name.startsWith(dayBefore)) continue;

            if (name.length() < 7) continue;
            String month = name.substring(0, 7); // "yyyy-MM"
            String monthlyName = month + "-00_00-00-00.ndjson";

            // find or create the monthly file
            DocumentFile monthly = null;
            DocumentFile[] allFiles = dir.listFiles();
            if (allFiles != null) {
                for (DocumentFile candidate : allFiles) {
                    if (monthlyName.equals(candidate.getName())) {
                        monthly = candidate;
                        break;
                    }
                }
            }

            boolean isNewMonthly = (monthly == null);
            if (isNewMonthly) {
                monthly = dir.createFile("application/x-ndjson", monthlyName);
                if (monthly == null) {
                    say("consolidate: could not create " + monthlyName);
                    continue;
                }
            }

            try {
                InputStream is = getContentResolver().openInputStream(f.getUri());
                if (is == null) continue;
                BufferedReader br = new BufferedReader(new InputStreamReader(is));

                OutputStream os = getContentResolver().openOutputStream(monthly.getUri(), "wa");
                if (os == null) { br.close(); continue; }
                BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os));

                if (isNewMonthly) {
                    bw.write("{\"hansel\":\"0.931\",\"tz\":\"HST\"," +
                            "\"Altitude units\":\"feet\",\"Speed\":\"MPH\"," +
                            "\"source\":\"consolidate()\"}\n" );
                }

                // consolidation deadband state - resets per source file
                double[] cbWindow = new double[5];
                int[]    cbCount  = new int[]{0};
                boolean[] cbFull  = new boolean[]{false};

                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.contains("\"hansel\"")) continue;

                    try {
                        JSONObject obj = new JSONObject(line);

                        // notes are sacred - always keep, always reset deadband
                        if (obj.has("note")) {
                            cbCount[0] = 0;
                            cbFull[0]  = false;
                            bw.write(line);
                            bw.newLine();
                            continue;
                        }

                        double altFt = obj.optDouble("alt", 0);
                        if (!consolidateSuppress(altFt, cbWindow, cbCount, cbFull)) {
                            bw.write(line);
                            bw.newLine();
                        }

                    } catch (Exception e) {
                        // malformed line - skip silently
                    }
                }

                bw.flush();
                bw.close();
                br.close();
                consolidated++;

            } catch (Exception e) {
                say("consolidate error on " + name + ": " + e.getMessage());
                continue;
            }

            if (f.delete()) {
                deleted++;
            } else {
                say("consolidate: could not delete " + name);
            }
        }

        if (consolidated > 0) {
            say("consolidate: " + consolidated + " files -> monthly, " + deleted + " deleted");
        }
    }

    /**
     * Schedules the next noon consolidation run.  Called once from
     * onStartCommand() to start the chain, then self-rescheduling via
     * noonRunnable.
     *
     * <p>Noon was chosen because yesterday's files are guaranteed cold by
     * then - no risk of consolidating a file that is still being written.
     * Startup consolidation is the safety net for files that accumulated
     * while the service was not running.  The two together mean no hour
     * file should ever sit unconsolidated for more than 36 hours.</p>
     *
     * <p>The noonRunnable lambda is reassigned on every call so that
     * noonHandler.removeCallbacks() in onDestroy() always holds the
     * current instance.  The same pattern is used by
     * scheduleTopOfHourRotation().</p>
     *
     * <p>Noon HST is not arbitrary.  USGS ASHCAM imagery at
     * ashcam.volcanoes.usgs.gov is timestamped in UTC.  Hawaii is UTC-10,
     * so a UTC calendar day runs from 2pm HST to 2pm HST.  Timelapse
     * composites built against UTC dates therefore bracket the nighttime
     * lava flows as a single uninterrupted sequence - which is the whole
     * point, since lava glows at night and is nearly invisible in daylight
     * from Volcano House at 2.6 miles.  Noon consolidation guarantees all
     * Hansel files are clean and current before the 2pm UTC day boundary
     * rolls over, so there is no risk of a composite straddling a
     * partially-consolidated month file.</p>
     *
     * TODO: If the service is started after noon and before midnight,
     *       the first scheduled noon will be tomorrow.  Files from today
     *       that age out of the 72h window overnight will not be consolidated
     *       until tomorrow noon.  Acceptable for now.
     */
    void scheduleNoon() {
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, 12);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);

        if (next.getTimeInMillis() <= System.currentTimeMillis()) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }

        long delay = next.getTimeInMillis() - System.currentTimeMillis();

        noonRunnable = () -> {
            say("noon consolidation running");
            consolidateOldFiles();
            scheduleNoon();
        };

        noonHandler.postDelayed(noonRunnable, delay);
    }


    /**
     * Opens a new NDJSON log file in the working directory and writes the
     * file header.  Called from onStartCommand() only.
     *
     * <p>The header is written immediately on file open, before any GPS
     * data arrives.  A file containing only a header line is valid and
     * intentional - it will occur whenever the deadband suppresses all
     * points in a given hour.  The Python pipeline accepts header-only
     * files without complaint.</p>
     *
     * <p>The isFileOpen guard at the top is the primary defense against
     * double-open.  This guard, and the decision to NOT call openFile()
     * from onActivityResult() in MainActivity, were the result of days of
     * debugging double service starts, duplicate trackpoints, and
     * quintuple callbacks.  The commented-out Toast and say() calls below
     * are diagnostic survivors from that era.</p>
     *
     * <p>currentHourKey is set after a successful open, not at the top.
     * A failed open leaves currentHourKey unchanged, which causes
     * handleLocation() to retry rotateFile() on the next GPS fix rather
     * than silently writing to a null writer.</p>
     *
     * <p>Files are named from actual wallclock time at the moment of
     * opening, never from a pre-computed hour key.</p>
     */
    void openFile() {
        //say("openFile called from: " + Thread.currentThread().toString());
        try {
            if (isFileOpen) {
                say("openFile: already open, skipping");
                return;
            }

            // By design this code should not be reachable.  Several iterations conflated
            // various roles to the extent that this safety valve was the only successful
            // debugging tool at one point.  It holds a special place in my heart, so don't
            // remove it, m'kay?
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }

            //TODO: we're supposed to ask for a new directory here, if null?
            DocumentFile dir = getTreeDir();
            if (dir == null) return;

            //This is my format.  Don't EVAR default to currentimestampmillis
            String name = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")
                    .format(new Date()) + ".ndjson";

            currentFile = dir.createFile("application/x-ndjson", name);
            if (currentFile == null) {
                say("openFile: createFile returned null");
                return;
            }

            OutputStream os = getContentResolver().openOutputStream(currentFile.getUri(), "w");
            if (os == null) {
                say("openFile: openOutputStream returned null");
                return;
            }

            writer = new BufferedWriter(new OutputStreamWriter(os));
            isFileOpen = true;
            writer.write("{\"hansel\":\"0.931\",\"tz\":\"HST\"," +
                    "\"Altitude units\":\"feet\",\"Speed\":\"MPH\"," +
                    "\"source\":\"openFile()\"}\n" );

            //               yyyy-MM-dd_HH (no minutes, no seconds)
            currentHourKey = getHourKey( System.currentTimeMillis() );

            //say("Logging to: " + currentFile.getUri().toString());
            //Toast.makeText(this, "Logging to: " + currentFile.getUri().toString(), Toast.LENGTH_LONG).show();
            say("Logging to: " + name);
            //Toast.makeText(this, "Logging to: " + name, Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            e.printStackTrace();
            say("openFile error: " + e.getMessage());
            Toast.makeText(this, "File error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Rotates to a new log file at the top of the hour.  Called from
     * handleLocation() when the hour key changes, and from
     * scheduleTopOfHourRotation() on the clock-driven path.
     *
     * <p>The very first act is to update currentHourKey.  This is the
     * double-rotation guard - if handleLocation() and the hour clock
     * both fire within milliseconds of each other, the second caller
     * sees a matching hour key and takes no action.</p>
     *
     * <p>isFileOpen is set false before the old file is closed.  This
     * asymmetry with openFile() is intentional - false before close
     * means a racing openFile() call will proceed rather than skip,
     * which is the safe failure mode.</p>
     *
     * <p>The new file is named from actual wallclock time, never from
     * the hourKey parameter.  The hourKey parameter exists only to
     * update currentHourKey and trigger the rotation decision in
     * handleLocation() - it does not determine the filename.</p>
     *
     * @param hourKey the new hour key formatted yyyy-MM-dd_HH.
     */
    void rotateFile(String hourKey) {
        try {
            isFileOpen = false;

            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }

            currentHourKey = hourKey;

            DocumentFile dir = getTreeDir();
            if (dir == null) return;

            // always name from actual wallclock, never from hourKey
            String name = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")
                    .format(new Date()) + ".ndjson";

            currentFile = dir.createFile("application/x-ndjson", name);
            if (currentFile == null) {
                say("rotateFile: createFile returned null");
                return;
            }

            OutputStream os = getContentResolver().openOutputStream(currentFile.getUri(), "w");
            if (os == null) {
                say("rotateFile: openOutputStream returned null");
                return;
            }

            writer = new BufferedWriter(new OutputStreamWriter(os));
            isFileOpen = true;
            writer.write("{\"hansel\":\"0.931\",\"tz\":\"HST\"," +
                    "\"Altitude units\":\"feet\",\"Speed\":\"MPH\"," +
                    "\"source\":\"rotateFile()\"}\n");

        } catch (Exception e) {
            e.printStackTrace();
            say("rotateFile error: " + e.getMessage());
        }
    }

    //  Handle GPS point
    /**
     * When the no-clothes emperor so decrees, so it shall be so.
     *
     * When the goog says this is how you're allowed to get location information,
     * you stand on your head while chewing gum, whistling and drinking a glass
     * of water upside down all at the same time.
     *
     * I'm just glad I didn't mention that I can juggle, too.
     *
     * This be one crazy kinda callback thing going on here.
     *
     * The "loc" object has things in it, most of which are not there all the
     * time.  Speed, Course, Direction, Altitude are not guaranteed.
     *
     * But wait, you say, the ALTITUDE is the only real data I even want!  What
     * do you mean not guaranteed? The goog sez so, so it must be true.
     *
     * Altitude - convert to feet  *3.28084
     * Speed - convert to MPH  *2.23694
     * accuracy is actually just a random number
     * CouRSe is 0 north 90 east 180 south 270 west
     *
     */
     void handleLocation(Location loc) {
        try {
            double altFt = loc.hasAltitude() ? loc.getAltitude() * 3.28084 : 0;

            long now = System.currentTimeMillis();
            String hour = getHourKey(now);

            if (!hour.equals(currentHourKey)) {
                rotateFile(hour);
            }

            String t = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            double lat = fmtLatLon(loc.getLatitude());
            double lon = fmtLatLon(loc.getLongitude());
            if (loc.hasSpeed())   lastSpd = loc.getSpeed();
            if (loc.hasBearing()) lastCrs = loc.getBearing();

            JSONObject obj = new JSONObject();
            obj.put("t", t  );
            obj.put("lat", lat );
            obj.put("lon", lon );
            obj.put("alt", Math.round(altFt));
            obj.put("acc", Math.round(loc.getAccuracy()));
            obj.put("spd", Math.round(lastSpd * 2.23694f));
            obj.put("crs", Math.round(lastCrs));

            sendToUI(obj.toString());

            if (!MainActivity.replayInProgress)
                MainActivity.updateGpsInfoOverlay( t, lat, lon, altFt, lastSpd, lastCrs );
            else
                MainActivity.updateCenterOverlay( lat, lon, altFt, 14 );

            lastLocation = loc;

            if (deadbandSuppress(loc.getLatitude(), loc.getLongitude(), altFt)) return;

            if (writer != null) {
                writer.write(obj.toString());
                writer.newLine();
                writer.flush();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Writes a mark (note) to the current log file.  Called from the UI
     * via the AndroidBridge JavaScript interface.
     *
     * <p>A mark is a trackpoint with a "note" field added.  The JSON is
     * constructed entirely on the JS side and passed in as a string.
     * This method writes it verbatim - no reformatting, no deadband, no
     * questions asked.</p>
     *
     * <p>deadbandReset() is called first, unconditionally.  A mark
     * represents a moment of intentional human attention.  Whatever the
     * GPS was doing before it is no longer relevant - the filter starts
     * fresh from here.</p>
     *
     * <p>The "MARK HIT SERVICE" say() call is a diagnostic survivor from
     * early development when it was not obvious whether marks were making
     * it through the JS/Java bridge at all.  Retained because it is
     * occasionally still useful in the field.</p>
     *
     * <p>mark() serves triple duty.  It handles free-text notes typed by
     * the user, sound event markers, and the one-tap panic button (M) for
     * when typing is not an option.  The JSON structure distinguishes them
     * but this method does not care - it writes whatever arrives and resets
     * the deadband regardless.</p>
     *
     * @param json the complete mark JSON string, constructed by the JS side.
     */
    @JavascriptInterface
    public void mark(String json) {
        deadbandReset();

        if (writer == null) {
            say("MARK dropped: no writer");
            return;
        }

        say("MARK HIT SERVICE");

        try {
            writer.write(json);
            writer.write("\n");
            writer.flush();
            sendToUI(json);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Formats a latitude or longitude value to exactly 6 decimal places.
     * 6 decimal places is approximately 0.1 meter precision, which is
     * finer than any consumer GPS can actually deliver but is the
     * established convention for this file format.
     *
     * <p>The method rounds to 6 places, then checks the decimal portion
     * of the string representation.  If the string has fewer than 6
     * decimal places (e.g. the value rounded to an exact multiple of
     * 0.00001), it nudges the value up by 0.000001 and rounds again.
     * This guarantees the string representation always has exactly 6
     * decimal places, which simplifies downstream parsing.</p>
     *
     * <p>The nudge is upward, never downward, and is smaller than the
     * noise floor of any GPS receiver.  It does not affect data quality.</p>
     *
     * If you think you are safe to modify this Method, for any reason
     * under the sun, please reschedule your lobotomy first.
     *
     * @param v raw latitude or longitude in decimal degrees.
     * @return value rounded and nudged to exactly 6 decimal places.
     */
    private static double fmtLatLon(double v) {
        double val = Math.round(v * 1000000) / 1000000.0;
        String decimals = String.valueOf(val).split("\\.")[1];
        if (decimals.length() < 6) {
            val += 0.000001;
            val = Math.round(val * 1000000) / 1000000.0;
        }
        return val;
    }

    /**
     * Flushes and closes the current log file.  Called from rotateNow()
     * before opening the next file.
     *
     * <p>Despite the name, this method no longer stops logging.  It closes
     * the current file and reports what was saved via say() and a Toast.
     * rotateNow() immediately calls openFile() after this, so logging
     * continues uninterrupted.  The only way to actually stop logging is
     * to force-close the app.</p>
     *
     * <p>The Toast here is one of the few surviving Toasts in the codebase.
     * It provides tactile confirmation that a manual rotation completed,
     * which matters when you are standing on a lava field and cannot
     * easily read the screen.</p>
     *
     * TODO: Remove the Toast - say() is sufficient and consistent with the
     *       rest of the codebase.  The Toast was a debugging remnant from
     *       before say() was reliable.
     *
     * @return a human-readable summary string naming the closed file,
     *         for the caller to display or log as needed.
     */
    public String stopAndReport() {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }

            isFileOpen = false;

            say("stop: currentFile=" + (currentFile == null ? "NULL" : currentFile.getUri().toString()));

            String msg = "Saved: " + currentHourKey +
                    "\nFile: " + (currentFile != null ? currentFile.getName() : "none") +
                    "\n(Logging stopped)";

            say(msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return msg;

        } catch (Exception e) {
            say("Stop error: " + e.getMessage());
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return "Stop error: " + e.getMessage();
        }
    }

    /**
     * Registers the FusedLocationProvider callback and begins receiving
     * GPS fixes.  Called once from onStartCommand().
     *
     * <p>The LocationCallback is constructed here rather than at field
     * declaration so that it closes over the current service instance.
     * Each fix delivered to onLocationResult() is passed to
     * handleLocation() - see that method for the full story on why
     * this callback is not to be trifled with.</p>
     *
     * <p>The interval passed to LocationRequest is a hint, not a
     * guarantee.  FusedLocationProvider may deliver fixes more frequently,
     * which on the target devices it does.  This accidental generosity
     * was previously the only thing keeping hourly rotation accurate.
     * It is now just a bonus.</p>
     *
     * <p>If ACCESS_FINE_LOCATION is not granted, the method bails with a
     * say() and a Toast.  In practice this should never happen since the
     * permission is granted manually at sideload time, but the check is
     * required by the Android API regardless.</p>
     *
     * TODO: When BootReceiver is implemented, confirm that startGPS() is
     *       not called before the permission check in MainActivity has
     *       had a chance to run on a fresh install.
     */
    void startGPS() {
        /*
        LocationRequest req = LocationRequest.create()
                .setInterval(interval)
                .setFastestInterval(interval)
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        */

        LocationRequest req = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, interval)
                .setMinUpdateIntervalMillis(interval)
                .build();

        callback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) return;
                for (Location loc : result.getLocations()) {
                    handleLocation(loc);
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            say("No location permission!");
            Toast.makeText(this, "No location permission!", Toast.LENGTH_LONG).show();
            return;
        }

        client.requestLocationUpdates(req, callback, Looper.getMainLooper());
    }

    //  Lifecycle

    /**
     * Service onCreate() - initializes the FusedLocationProviderClient.
     * The two Handler instances (noonHandler, hourHandler) are initialized
     * at field declaration rather than here, consistent with each other.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        MainActivity.locationService = this;
        client = LocationServices.getFusedLocationProviderClient(this);
    }

    /**
     * Service entry point.  Starts the foreground notification, reads the
     * logging interval from the Intent, runs startup consolidation, starts
     * both background clocks, opens the log file, and starts GPS.
     *
     * <p>The foreground notification is required on SDK 26+ for any service
     * that continues running while the app is in the background.  On SDK 29+
     * the FOREGROUND_SERVICE_TYPE_LOCATION flag is required in addition,
     * or the system will not deliver location updates.  Both are handled
     * here with a version check.</p>
     *
     * <p>If the service is already running and receives a second start
     * Intent, onStartCommand() fires again.  The isFileOpen guard in
     * openFile() prevents a double file open.  The startId diagnostic
     * say() call makes a repeated start visible in the message box.</p>
     *
     * <p>START_STICKY tells Android to restart the service automatically
     * if it is killed by the system.  The Intent will be null on restart,
     * which is handled by the null check on intent.getIntExtra()
     * defaulting to 30_000.</p>
     *
     * @param intent  the starting Intent, or null if restarted by the system.
     * @param flags   delivery flags, not used.
     * @param startId unique ID for this start request, logged via say().
     * @return START_STICKY so the system restarts the service if killed.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(1, NotificationHelper.build(this),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(1, NotificationHelper.build(this));
            }
            consolidateOldFiles();
            scheduleTopOfHourRotation();
            scheduleNoon();
            say("startId: " + startId + " opening file");
            openFile();
            startGPS();
        } catch (Exception e) {
            e.printStackTrace();
            say("CRASH: " + e.getClass().getSimpleName());
            Toast.makeText(this, "CRASH: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }

        return START_STICKY;
    }

    /**
     * Cleans up on service destruction.  Cancels both background clock
     * chains, removes the GPS listener, and closes the log file.
     *
     * <p>Both noonRunnable and hourRotationRunnable are cancelled here.
     * Failing to cancel hourRotationRunnable would cause rotateNow() to
     * fire on a destroyed service, calling openFile() with no valid
     * context.  The null checks are technically redundant since both
     * Handlers are initialized at field declaration, but are consistent
     * with the noonHandler pattern and harmless.</p>
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;

        if (noonRunnable != null) noonHandler.removeCallbacks( noonRunnable );
        if (hourHandler != null) hourHandler.removeCallbacks( hourRotationRunnable );

        try {
            if (client != null && callback != null) {
                client.removeLocationUpdates(callback);
            }
            if (writer != null) {
                writer.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * This service does not support binding.  Returns null as required
     * by the Service contract for non-bound services.
     *
     * @param intent not used.
     * @return null always.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}