/*
 * Hansel - GPS breadcrumb logger v0.988
 * Copyright (C) 2026 GrimmsTales
 * GNU General Public License v3 - https://www.gnu.org/licenses/gpl-3.0.html
 */
package com.hansel.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;
import java.util.DuplicateFormatFlagsException;

/**
 * Hansel GPS breadcrumb logger - v0.988.
 *
 * WebAppInterface is the bridge between index.html (JavaScript) and the
 * Android Java layer.  Every method annotated @JavascriptInterface is
 * callable from JS as AndroidBridge.methodName().  AndroidBridge is the
 * name assigned in MainActivity.onCreate() when this object is attached
 * to the WebView.
 *
 * All file I/O here uses direct File access via resolveTreeUriToFile(),
 * not SAF DocumentFile.  resolveTreeUriToFile() hand-parses the SAF tree
 * URI stored in HanselPrefs to recover a plain filesystem path.  This is
 * fragile by design - it depends on the /storage/volumeId/path convention
 * holding across Android versions - but it works on all target devices and
 * is far simpler than the SAF alternative for read operations.
 *
 * LocationService.instance is used directly throughout this class to
 * call say(), mark(), rotateNow(), and consolidateOldFiles().  The service
 * is either running or it is not - if it is not running, the null checks
 * short-circuit cleanly.
 *
 * TODO: Remove getTreeDir() - it is never called.  All file access goes
 *       through resolveTreeUriToFile().  Retained only because removing
 *       dead SAF code requires confirming nothing else sneaks back to it.
 */
public class WebAppInterface {

    Context context;

    /**
     * say() convenience debug method because LOGCAT fails most
     * of the time on Android Studio Bumblebee.
     */
    private static void say(String something) {
        LocationService.say(something, "webAppInt.");
    }

    /**
     * Constructs the bridge with the Activity context from MainActivity.
     * The context is used for SharedPreferences access and ContentResolver
     * calls in resolveTreeUriToFile() and getTreeDir().
     */
    public WebAppInterface(Context context) { this.context = context; }

    /**
     * Javascript [REPLAY] and [72h] send this GeoPoint() to mapView to be plotted
     * @param data
     */
    @android.webkit.JavascriptInterface
    public void replayPoint(String data) {
        MainActivity.handleReplayPoint(data);
    }

    /**
     * Triggers an immediate monthly consolidation run.
     * Called from the Rollup button in index.html.
     * Delegates directly to LocationService.consolidateOldFiles().
     */
    @JavascriptInterface
    public void consolidateNow() {
        if (LocationService.instance != null) {
            say("consolidateNow" );
            LocationService.instance.consolidateOldFiles();
        }
    }

    /**
     * Returns the interval the dropdown should actually show on page load.
     * Prefers the live LocationService's running interval when the service
     * is already active (the common case - see the startLogging() doc
     * comment on why the service is usually already running by the time
     * this is called).  Falls back to the persisted last_interval when the
     * service hasn't started yet.
     */
    @JavascriptInterface
    public int getCurrentInterval() {
        say("getCurrentInterval called by javascript");
        if (LocationService.instance != null) {
            say("returning interval=" + LocationService.instance.interval);
            return LocationService.instance.interval;
        }
        say("returning previously saved interval="
             + context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("last_interval", 5000) );
        return context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("last_interval", 5000);
    }

    /**
     * Sets the GPS logging interval.  Delegates to LocationService.updateInterval().
     *
     *     RESTORED AND DELETED TOO MANY TIMES!  OBVIOUSLY NEEDED!
     *
     * Persists the choice to last_interval so it survives a restart; see
     * getCurrentInterval() below, which is what the dropdown reads back on
     * page load.
     *
     * @param intervalString requested interval in milliseconds.
     */
    @JavascriptInterface
    public void setInterval(String intervalString) {
        say("setInterval.interval: " + intervalString);
        if (LocationService.instance != null) {
            // This next line is amazingly important - using android's WebApp Bridge does not work
            // properly for int, pass as a string instead and parse here - if you try letting the
            // broken android interface do it for you, you get interval = 0
            int interval = Integer.parseInt(intervalString);
            LocationService.instance.updateInterval( interval );
            context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putInt("last_interval", interval).apply();
        }
    }

    @JavascriptInterface
    public int getMaxReplayPoints() {
        return MainActivity.maxReplayPoints;
    }

    @JavascriptInterface
    public void setMaxReplayPoints(String value) {
        int n = Integer.parseInt(value);
        MainActivity.maxReplayPoints = n;
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(MainActivity.PREF_MAX_REPLAY_POINTS, n)
                .apply();
    }

    @JavascriptInterface
    public boolean getStartup72hr() {
        return MainActivity.startup72hr;
    }

    @JavascriptInterface
    public void setStartup72hr(boolean value) {
        MainActivity.startup72hr = value;
        if (value) MainActivity.startupReplay = false;
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(MainActivity.PREF_STARTUP_72HR, value)
                .putBoolean(MainActivity.PREF_STARTUP_REPLAY, MainActivity.startupReplay)
                .apply();
    }

    @JavascriptInterface
    public boolean getStartupReplay() {
        return MainActivity.startupReplay;
    }

    @JavascriptInterface
    public void setStartupReplay(boolean value) {
        MainActivity.startupReplay = value;
        if (value) MainActivity.startup72hr = false;
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(MainActivity.PREF_STARTUP_REPLAY, value)
                .putBoolean(MainActivity.PREF_STARTUP_72HR, MainActivity.startup72hr)
                .apply();
    }

    /**
     * Returns a DocumentFile handle to the working directory from the SAF
     * tree URI stored in HanselPrefs.  Never called - all file access goes
     * through resolveTreeUriToFile() instead.
     *
     * TO DON'T: openFile() uses this. Remove once confirmed nothing will revert to SAF access.
     */
    private DocumentFile getTreeDir() {
        SharedPreferences prefs = context.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String uriString = prefs.getString(MainActivity.PREF_TREE_URI, null);
        if (uriString == null) {
            say("..No folder selected - open app to pick one");
            return null;
        }
        return DocumentFile.fromTreeUri(context, Uri.parse(uriString));
    }

    /**
     * Starts LocationService with the given interval and rollover values.
     * Called from index.html start() on page load.
     *
     * The double-start guard checks LocationService.instance - if the
     * service is already running, the call is silently ignored.  This is
     * the correct behavior: the JS side calls startLogging() on every page
     * load, and the service may already be running from MainActivity's
     * startLoggingDefault() on subsequent launches.  The commented-out
     * say() below was the diagnostic that confirmed the guard was firing
     * correctly during hell week.
     *
     * The interval is persisted to HanselPrefs as last_interval for
     * startLoggingDefault() to read on the next launch.  In practice this
     * will always be 30_000.
     *
     * @param interval logging interval in milliseconds.
     * @param rollover file rotation interval in seconds.
     */
    @JavascriptInterface
    public void startLogging(int interval, int rollover) {
        //Prevent doubletap
        if (LocationService.instance != null) {
            say("startLogging: should not exist!"
                    + " LocationService.instance: "
                    + LocationService.instance.toString()
                    + " service already running, skipping." );
            //throw new RuntimeException("WebAppInterface.startLogging: MainActivity.locationService: " +
            //        MainActivity.locationService.toString() );
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        interval = prefs.getInt("last_interval", interval );

        // Startup replay toggles - at most one fires, per the mutual-exclusion
        // enforced in setStartup72hr()/setStartupReplay().
        if (MainActivity.startup72hr) {
            MainActivity.webView.post(() ->
                    MainActivity.webView.evaluateJavascript("replay72h()", null));
        } else if (MainActivity.startupReplay) {
            MainActivity.webView.post(() ->
                    MainActivity.webView.evaluateJavascript("replay()", null));
        }

        Intent i = new Intent(context, LocationService.class);
        i.putExtra("interval", interval);
        i.putExtra("rollover", rollover);
        ContextCompat.startForegroundService(context, i);
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt("last_interval", interval )
                .apply();
    }

    /**
     * Triggers a manual file rotation and returns the new filename.
     * Called from the STOP button handler in index.html.
     * Delegates to LocationService.rotateNow().
     * Returns "no service" if LocationService is not running.
     *
     * @return the new log filename, or "no service".
     */
    @JavascriptInterface
    public String rotateNow() {
        if (LocationService.instance != null) {
            return LocationService.instance.rotateNow();
        }
        return "no service";
    }

    /**
     * Writes a mark to the current log file.
     * Called from index.html mark() for notes, sound events, and the M
     * panic button.  Delegates directly to LocationService.mark().
     * The JSON is constructed entirely on the JS side and passed through
     * verbatim - this method does not inspect or reformat it.
     *
     * @param json complete mark JSON string, constructed by index.html.
     */
    @JavascriptInterface
    public void mark(String json) {
        if (LocationService.instance != null) {
            LocationService.instance.mark(json);
        }
    }

    /**
     * Hand-parses the SAF tree URI stored in HanselPrefs to recover a
     * plain filesystem path as a java.io.File.  Used by getFileList()
     * and readFile() to bypass SAF entirely for read operations.
     *
     * The parsing assumes the URI contains a /tree/ segment followed
     * by a docId of the form volumeId:path.  This convention holds on
     * all target devices but is not guaranteed by the Android API.  If
     * the URI format ever changes - different Android version, different
     * manufacturer - this method will return null and file listing will
     * silently fail.  All intermediate steps are logged via say() to
     * make failures visible in the scrollbox.
     *
     * This fragility is an acceptable tradeoff.  The SAF alternative
     * for recursive directory listing requires DocumentFile.listFiles()
     * which cannot traverse subdirectories, making the ./backup/
     * implementation impossible without this approach.
     */
    private File resolveTreeUriToFile() {
        SharedPreferences prefs = context.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String uriString = prefs.getString(MainActivity.PREF_TREE_URI, null);
        say("resolveTreeUri: input=" + uriString);

        if (uriString == null) {
            say("resolveTreeUri: no URI in prefs");
            return null;
        }

        String decoded = Uri.decode(uriString);
        int treeIdx = decoded.indexOf("/tree/");
        if (treeIdx < 0) {
            say("resolveTreeUri: no /tree/ found");
            return null;
        }

        String docId   = decoded.substring(treeIdx + 6);
        int colonIdx   = docId.indexOf(":");
        if (colonIdx < 0) {
            say("resolveTreeUri: no colon in docId=" + docId);
            return null;
        }

        String volumeId = docId.substring(0, colonIdx);
        String path     = docId.substring(colonIdx + 1);
        File result     = new File("/storage/" + volumeId + "/" + path);

        say("resolveTreeUri: resolved=" + result.getAbsolutePath());
        return result;
    }

    /**
     * Returns a JSON array string of absolute paths to all .ndjson files
     * in the working directory, sorted chronologically by filename.
     * Called from index.html replay() and replay72h().
     *
     * Sorting by filename is correct because the filename format
     * yyyy-MM-dd_HH-mm-ss sorts lexicographically in chronological order.
     * No date parsing needed.
     *
     * MTP duplicate files named "datetime.ndjson (1)" are excluded by
     * the endsWith(".ndjson") filter - they accumulate harmlessly on the
     * SD card and are invisible to replay.
     *
     * @return JSON array string of absolute file paths, or "[]" on failure.
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    @JavascriptInterface
    public String getFileList() {
        JSONArray arr = new JSONArray();

        File dir = resolveTreeUriToFile();
        if (dir == null) {
            say("getFileList: could not resolve directory");
            return "[]";
        }

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            say("getFileList: no files found in " + dir.getAbsolutePath());
            return "[]";
        }

        ArrayList<File> ndjson = new ArrayList<>();
        for (File f : files) {
            if (f.getName().endsWith(".ndjson")) ndjson.add(f);
        }

        ndjson.sort((a, b) -> a.getName().compareTo(b.getName()));

        say("getFileList: " + ndjson.size() + " files");
        for (File f : ndjson) {
            say("found: " + f.getName());
            arr.put(f.getAbsolutePath());
        }

        return arr.toString();
    }

    /**
     * Reads the full contents of a file and returns it as a string.
     * Called from index.html replay() and replay72h() for each file
     * in the replay list.
     *
     * Absolute paths (starting with "/") are read via FileInputStream
     * directly.  All paths returned by getFileList() are absolute, so the
     * ContentResolver branch is a fallback that should never fire in
     * normal operation.
     *
     * All errors are caught, logged via say(), and return an empty
     * string.  A failed read silently skips that file in replay rather
     * than crashing.  The say() output makes the failure visible in the
     * scrollbox.
     *
     * @param path absolute filesystem path or content URI string.
     * @return file contents as a string, or empty string on failure.
     */
    @JavascriptInterface
    public String readFile(String path) {
        say("readFile: " + path);
        try {
            java.io.InputStream is;
            if (path.startsWith("/")) {
                is = new java.io.FileInputStream(path);
            } else {
                is = context.getContentResolver().openInputStream(Uri.parse(path));
            }
            if (is == null) {
                say("readFile: null stream for " + path);
                return "";
            }

            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            say("readFile: done, " + sb.length() + " chars");
            return sb.toString();

        } catch (Exception e) {
            say("readFile error: " + e.getMessage());
            return "";
        }
    }



    /**
     * Called from JS replay() and replay72h() before interval starts.
     * Single source of truth for replayInProgress = true.
     */
    @JavascriptInterface
    public void replayStarting() {
        ((android.app.Activity) context).runOnUiThread(() -> {
            MainActivity.replayInProgress = true;
            MainActivity.replayFollowMode = true;
            MainActivity.liveFollowMode   = false;
            MainActivity.replayPausedFloatie
                    .setVisibility(android.view.View.GONE);
            MainActivity.liveUpdatesPausedFloatie
                    .setVisibility(android.view.View.VISIBLE);
        });
    }

    /**
     * Called from JS stopReplay() and natural interval exhaustion.
     * Runs resumeLive to restore live following and clear floaties.
     */
    @JavascriptInterface
    public void replayComplete() {
        ((android.app.Activity) context).runOnUiThread(
                MainActivity::resumeLive);
    }

}
