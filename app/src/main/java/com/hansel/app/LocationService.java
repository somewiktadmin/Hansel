// Hansel - GPS breadcrumb logger v0.98
// Copyright (C) 2026 GrimmsTales
// GNU General Public License v3 -- https://www.gnu.org/licenses/gpl-3.0.html

package com.hansel.app;

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


public class LocationService extends Service {

    private boolean isFileOpen = false;

    public static final String ACTION_STOP = "STOP_LOGGING";
    public static LocationService instance;

    FusedLocationProviderClient client;
    LocationCallback callback;
    BufferedWriter writer;
    String currentHourKey = "";
    DocumentFile currentFile;
    int interval = 30000;

    private Handler noonHandler = new Handler(Looper.getMainLooper());
    private Runnable noonRunnable;

    // interval floor: 500ms was tried and produced duplicate yyyy-MM-dd_HH-mm-ss timestamps.
    // 1000ms is the minimum safe value for second-granular datetime fields.
    private static final int    INTERVAL_FLOOR_MS    = 1000;

    // === live deadband filter (N=10, 35ft) ===
    // N=20 was overkill, N=5 overreacts, N=10 field-tuned as best balance.
    // Future: std deviation spike filter for Saddle Road cell-mode thrashing.
    private static final int    DEADBAND_N   = 10;
    private static final double DEADBAND_FT = 35.0;
    private final double[] deadbandWindow  = new double[DEADBAND_N];
    private int  deadbandCount            = 0;
    private boolean deadbandFull         = false;
    private Location lastLocation       = null;
    private float lastSpd              = 0.0f;
    private float lastCrs             = 0.0f;

    private boolean deadbandSuppress(double altFt) {
        if (deadbandFull) {
            double lo = deadbandWindow[0], hi = deadbandWindow[0];
            for (double v : deadbandWindow) {
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
            if ((hi - lo) <= DEADBAND_FT && Math.abs(altFt - lo) <= DEADBAND_FT)
                return true;
        }
        deadbandWindow[deadbandCount % DEADBAND_N] = altFt;
        deadbandCount++;
        if (deadbandCount >= DEADBAND_N) deadbandFull = true;
        return false;
    }

    private void deadbandReset() {
        deadbandCount = 0;
        deadbandFull  = false;
    }

    String getHourKey(long timeMillis) {
        return new SimpleDateFormat("yyyy-MM-dd_HH")
                .format(new Date(timeMillis));
    }

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


    // === get SAF tree from SharedPreferences ===
    private DocumentFile getTreeDir() {
        SharedPreferences prefs = getSharedPreferences(
                MainActivity.PREFS_NAME, MODE_PRIVATE);
        String uriString = prefs.getString(MainActivity.PREF_TREE_URI, null);
        if (uriString == null) {
            say("No folder selected -- open app to pick one");
            return null;
        }
        return DocumentFile.fromTreeUri(this, Uri.parse(uriString));
    }

    // ===== UI bridge =====
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

    // ===== Consolidation deadband (N=5, 35ft) =====
    // Tighter than live deadband -- hour files already filtered once.
    // Notes always kept and always reset the counter.
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

    // ===== Consolidate old hour files into monthly rollups =====
    // Runs at startup and at noon daily.
    // Skips today, yesterday, existing monthly rollups, and non-ndjson files.
    // Monthly filename format: YYYY-MM-00_00-00-00.ndjson -- not up for debate.
    // WAS: Hour files are deleted after successful consolidation.
    // NOW: Files moved to ./backup/ for posterity.
    void consolidateOldFiles() {
        DocumentFile dir = getTreeDir();
        if (dir == null) return;

        DocumentFile[] files = dir.listFiles();
        if (files == null || files.length == 0) return;

        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String yesterday = new SimpleDateFormat("yyyy-MM-dd")
                .format(new Date(System.currentTimeMillis() - 86400000L));

        int consolidated = 0;
        int deleted = 0;

        for (DocumentFile f : files) {
            String name = f.getName();
            if (name == null) continue;

            if (!name.endsWith(".ndjson")) continue;
            if (name.contains("-00_00-00-00")) continue;
            if (name.startsWith(today)) continue;
            if (name.startsWith(yesterday)) continue;

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
                    bw.write("{\"hansel\":\"0.93\",\"tz\":\"HST\",\"source\":\"consolidate()\"}\n");
                }

                // consolidation deadband state -- resets per source file
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

                        // notes are sacred -- always keep, always reset deadband
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
                        // malformed line -- skip silently
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

    // ===== Schedule noon trigger =====
    // Startup is the safety net for missed consolidations.
    // Noon is the "tidy up while running" path -- yesterday's files are cold by then.
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

    // ===== Open new NDJSON file via SAF =====
    void openFile() {
        //say("openFile called from: " + Thread.currentThread().toString());
        try {
            if (isFileOpen) {
                say("openFile: already open, skipping");
                return;
            }

            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }

            DocumentFile dir = getTreeDir();
            if (dir == null) return;

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
            writer.write("{\"hansel\":\"0.93\",\"tz\":\"HST\",\"source\":\"openFile()\"}\n");

            say("Logging to: " + currentFile.getUri().toString());
            //Toast.makeText(this, "Logging to: " + currentFile.getUri().toString(), Toast.LENGTH_LONG).show();
            say("Logging to: " + name);
            //Toast.makeText(this, "Logging to: " + name, Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            e.printStackTrace();
            say("openFile error: " + e.getMessage());
            Toast.makeText(this, "File error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ===== Rotate to new file on hour boundary =====
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
            writer.write("{\"hansel\":\"0.93\",\"tz\":\"HST\",\"source\":\"rotateFile()\"}\n");

        } catch (Exception e) {
            e.printStackTrace();
            say("rotateFile error: " + e.getMessage());
        }
    }

    // ===== Handle GPS point =====
    void handleLocation(Location loc) {
        try {
            double altFt = loc.hasAltitude() ? loc.getAltitude() * 3.28084 : 0;

            long now = System.currentTimeMillis();
            String hour = getHourKey(now);

            if (!hour.equals(currentHourKey)) {
                rotateFile(hour);
            }

            if (loc.hasSpeed())   lastSpd = loc.getSpeed();
            if (loc.hasBearing()) lastCrs = loc.getBearing();

            JSONObject obj = new JSONObject();
            obj.put("t",   new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()));
            obj.put("lat", fmtLatLon(loc.getLatitude()));
            obj.put("lon", fmtLatLon(loc.getLongitude()));
            obj.put("alt", Math.round(altFt));
            obj.put("acc", Math.round(loc.getAccuracy()));
            obj.put("spd", Math.round(lastSpd * 2.23694f));
            obj.put("crs", Math.round(lastCrs));

            sendToUI(obj.toString());

            lastLocation = loc;

            if (deadbandSuppress(altFt)) return;

            if (writer != null) {
                writer.write(obj.toString());
                writer.newLine();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

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

    private static double fmtLatLon(double v) {
        double val = Math.round(v * 1000000) / 1000000.0;
        String decimals = String.valueOf(val).split("\\.")[1];
        if (decimals.length() < 6) {
            val += 0.000001;
            val = Math.round(val * 1000000) / 1000000.0;
        }
        return val;
    }

    public String stopAndReport() {
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }

            isFileOpen = false;

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

    // ===== Start GPS =====
    void startGPS() {
        LocationRequest req = LocationRequest.create()
                .setInterval(interval)
                .setFastestInterval(interval)
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

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

    // ===== Lifecycle =====

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        client = LocationServices.getFusedLocationProviderClient(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            String msg = stopAndReport();
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(1, NotificationHelper.build(this),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(1, NotificationHelper.build(this));
            }
            interval = intent.getIntExtra("interval", 30000);
            consolidateOldFiles();
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;

        if (noonRunnable != null) noonHandler.removeCallbacks(noonRunnable);

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

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
