/*
 * Hansel - GPS breadcrumb logger v0.986
 * Copyright (C) 2026 GrimmsTales
 * GNU General Public License v3 - https://www.gnu.org/licenses/gpl-3.0.html
 */

package com.hansel.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.TilesOverlay;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.util.List;

/**
 * Hansel v0.986 main activity.
 *
 * TODO: Rewrite this Javadoc block once OSMDroid integration and replay are stable.
 *
 * <p>Future: MainActivity and the WebView UI are destined to become a
 * separate viewer app (Gretel).  The logging core - LocationService and
 * its file I/O - will move to a standalone headless Hansel app.  That
 * split is post-v1.0.</p>
 *
 * TODO Consolidate the two MANAGE_ALL_FILES blocks into one, in the right
 *       place, and confirm it actually works on the target devices.
 * TODO: Add a "change folder" button or menu item so the user can re-pick
 *       the working directory without reinstalling.
 * todo: Add BootReceiver to auto-resume LocationService after reboot, then
 *       remove startLoggingDefault() from this class entirely.
 */
public class MainActivity extends Activity {

    public static WebView webView;
    public static org.osmdroid.views.MapView mapView;

    private TextView replayPausedFloatie;
    //private SeekBar zoomSlider;
    //private boolean sliderTracking = false; // suppress map->slider echo

    // At Hansel v1 and 0.94, started using the number 1094
    private static final int REQUEST_TREE = 1094;
    private static final int REQUEST_TILE = 1095;
    private static final int REQUEST_SPOOL= 1096;

    static final String PREFS_NAME    = "HanselPrefs";
    static final String PREF_TREE_URI = "tree_uri"; //Hansel ndJSON logs
    static final String PREF_TILE_URI = "3rd tile cache layer"; // supplemental tile cache on SD card
    static final String PREF_SPOOL_URI= "cloud uploads"; // cloud upload spool directory

    private MyLocationNewOverlay locationOverlay;
    //private CompassOverlay compassOverlay;

    public static TextView gpsInfoOverlay;
    public static TextView skyBarBox;

    /** Cached center overlay data - updated on pan/zoom/replay. */
    private static double centerLat  = 0;
    private static double centerLon  = 0;
    private static double centerAlt  = 0;
    private static int    centerZoom = 0;

    /** Cached status overlay data - updated 1Hz from handleLocation(). */
    private static String statusLine1 = "";

    /** Sky bar header line - constant, 3 leading spaces align DD| prefix. */
    private static final String SKY_HEADER = "   12    16    20    00    04    08    12";

    /** Usable line count in gpsInfoOverlay - measured after layout. */
    private static int overlayLines = 21;
    /** Usable character width of gpsInfoOverlay - measured after layout. */
    private static int overlayChars = 40;

    private static boolean replayFollowMode = true;

    // cached values - recalculate only when date changes
    private static String lastCalcDate = "";
    private static String cachedMoon   = "";
    private static String[] cachedSun  = new String[6];

    private static boolean updatingMap = false;
    public static boolean replayInProgress = false;

    private static org.osmdroid.views.overlay.Polyline replayPointsOverlay;
    private static org.osmdroid.views.overlay.Marker   replayHeadMarker;
    private static boolean  liveFollowMode  = true;
    private static GeoPoint lastReplayPoint = null;
    private static int      replayPointCount = 0;
    private static java.util.List<org.osmdroid.views.overlay.Polyline>
            replaySegments = new java.util.ArrayList<>();
    private static java.util.List<org.osmdroid.views.overlay.Marker>
            replayGapDots  = new java.util.ArrayList<>();

    private static final int    MAX_REPLAY_POINTS = 2500;
    private static final int    REPLAY_LINE_COLOR = Color.argb(127, 127, 100, 40);
    private static final float  REPLAY_LINE_WIDTH = 4f;
    private static final double GAP_METERS        = 1609.34;
    private Button btnMeRef;


    /**
     *
     * >>> Hybrid mapView / webView split this version <<<
     *
     * currently playing with mapView experiments...older webView narrative is still
     * somewhat relevant (gosh, two days ago?) as I try different things.
     *
     * Initializes the activity, requests permissions, builds the WebView, and
     * either prompts for a working directory (first launch) or loads the UI and
     * starts the location service (subsequent launches).
     *
     * <p>Permission requests fire in this order: MANAGE_APP_ALL_FILES_ACCESS,
     * POST_NOTIFICATIONS, ACCESS_FINE_LOCATION.  All three are currently granted
     * manually at install time via Android Studio, so the runtime request blocks
     * are present but not reliably exercised.</p>
     *
     * <p>The WebView is configured with JavaScript, DOM storage, and file access
     * enabled.  WebAppInterface is attached as "AndroidBridge", which is the
     * name index.html uses for all Java callbacks.</p>
     *
     * <p>If no working directory URI is stored in HanselPrefs, ACTION_OPEN_DOCUMENT_TREE
     * fires and onActivityResult() handles the response.  If a URI is already
     * stored, the UI loads immediately and startLoggingDefault() starts the
     * service.</p>
     *
     * TODO: Runtime permission requests are currently bypassed by manual grant
     *       at sideload time.  Before any Google Play submission these blocks
     *       must be tested cold - Google Play requires that the app request
     *       only the permissions it genuinely needs, at the moment it needs
     *       them, with a rationale shown to the user.
     *
     * @param savedInstanceState Android Activity saved state bundle.  Not used
     *        here because the WebView reconstructs cleanly from its asset on every
     *        start.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        android.util.Log.d("Hansel", "onCreate firing");

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.POST_NOTIFICATIONS
            }, 2);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, 1);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivityForResult(intent, 2001);
                return; // wait for result before continuing onCreate
            }
        }

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        mapView = findViewById(R.id.mapView);

        // OSMDroid init
        initOsmdroid();
        org.osmdroid.config.Configuration.getInstance()
                .setUserAgentValue(
                        "Hansel/0.986 personal field logger - single user, Kilauea HI");
        //cache for OSMDroid is not allowed to live on sdcard, so try this
        org.osmdroid.config.Configuration.getInstance()
                .setOsmdroidBasePath(getFilesDir());
        org.osmdroid.config.Configuration.getInstance()
                .setOsmdroidTileCache(new java.io.File(getFilesDir(), "tiles"));
        mapView.setTileSource(
                org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK);
        mapView.setBuiltInZoomControls(false);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(12);
        mapView.getController().setCenter(new GeoPoint(19.402, -155.293));

        mapView.getOverlayManager().getTilesOverlay()
                .setColorFilter(TilesOverlay.INVERT_COLORS);
        //mapView.getOverlayManager().getTilesOverlay()
        //        .setLoadingBackgroundColor(Color.BLACK);
        mapView.getOverlayManager().getTilesOverlay()
                .setLoadingLineColor(Color.argb(255, 0, 255, 0));

        replayPausedFloatie = findViewById(R.id.replayPausedFloatie);
        TextView liveUpdatesPausedFloatie =
                findViewById(R.id.liveUpdatesPausedFloatie);

        gpsInfoOverlay = findViewById(R.id.gpsInfoOverlay);
        gpsInfoOverlay.setShadowLayer(2f, 1f, 1f, 0xFF000000);
        gpsInfoOverlay.post(() -> {
            if (gpsInfoOverlay.getLineHeight() > 0) {
                overlayLines = gpsInfoOverlay.getHeight() / gpsInfoOverlay.getLineHeight();
            }
            if (gpsInfoOverlay.getPaint() != null && gpsInfoOverlay.getWidth() > 0) {
                float charW = gpsInfoOverlay.getPaint().measureText("M");
                if (charW > 0) overlayChars = (int)(gpsInfoOverlay.getWidth() / charW);
            }
        });

        skyBarBox = findViewById(R.id.skyBarBox);
        skyBarBox.setShadowLayer(2f, 1f, 1f, 0xFF000000);

        Button btnMe      = findViewById(R.id.btnMe);
        Button btnHMM     = findViewById(R.id.btnHMM);
        Button btnZoomIn  = findViewById(R.id.btnZoomIn);
        Button btnZoomOut = findViewById(R.id.btnZoomOut);
        btnMeRef = btnMe;

        // resumeLive: re-enable live map following.  Does NOT touch log files,
        // does NOT call JS stop() - that caused log rotation on every pan.
        // Replay is stopped by setting replayInProgress=false only; the JS
        // replay interval will exhaust naturally or be stopped separately.
        Runnable resumeLive = () -> {
            replayInProgress = false;
            replayFollowMode = true;
            liveFollowMode   = true;
            btnMe.setTextColor(Color.GREEN);
            liveUpdatesPausedFloatie.setVisibility(View.GONE);
            replayPausedFloatie.setVisibility(View.GONE);
            if (locationOverlay.getMyLocation() != null) {
                mapView.getController().setZoom(15);
                mapView.getController().animateTo(
                        locationOverlay.getMyLocation());
            }
        };

        // [ME] - resume live follow, stop replay, snap to phone position
        btnMe.setOnClickListener(v -> resumeLive.run());

        // gpsInfoOverlay tap - same as [ME]
        gpsInfoOverlay.setOnClickListener(v -> resumeLive.run());

        // [HISTORY REPLAYING] tap - same as [ME]
        liveUpdatesPausedFloatie.setOnClickListener(v -> resumeLive.run());

        // [REPLAY PAUSED] tap - resume replay follow only, live stays paused
        replayPausedFloatie.setOnClickListener(v -> {
            replayFollowMode = true;
            replayPausedFloatie.setVisibility(View.GONE);
        });

        // [HMM] - Halemaumau
        btnHMM.setOnClickListener(v -> {
            mapView.getController().setZoom(14);
            mapView.getController().animateTo(new GeoPoint(19.411, -155.269 ));
        });

        // [+] and [-] step zoom by 1
        btnZoomIn.setOnClickListener(v -> {
            double z = Math.min(mapView.getZoomLevelDouble() + 1.0, 19.0);
            mapView.getController().setZoom(z);
            //syncSliderToMap();
        });

        btnZoomOut.setOnClickListener(v -> {
            double z = Math.max(mapView.getZoomLevelDouble() - 1.0, 1.0);
            mapView.getController().setZoom(z);
            //syncSliderToMap();
        });

        // zoom slider - user drag sets map zoom
        // rotation="270" means max (top) = high zoom, min (bottom) = low zoom
        /*
        zoomSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    mapView.getController().setZoom((double) progress + 1.0);
                }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) { sliderTracking = true; }
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar)  { sliderTracking = false; }
        });*/

        locationOverlay = new MyLocationNewOverlay(
                new GpsMyLocationProvider(this), mapView);
        locationOverlay.enableMyLocation();
        mapView.getOverlays().add(locationOverlay);

        mapView.addMapListener(new MapListener() {
            @Override
            public boolean onScroll(ScrollEvent event) {
                liveFollowMode = false;
                if (btnMeRef != null) btnMeRef.setTextColor(Color.GRAY);
                // [HISTORY REPLAYING] only shown during replay, not during live pan
                if (replayInProgress) {
                    replayFollowMode = false;
                    liveUpdatesPausedFloatie.setVisibility(View.VISIBLE);
                    replayPausedFloatie.setVisibility(View.VISIBLE);
                }
                IGeoPoint gs = mapView.getMapCenter();
                centerZoom = (int) Math.round(mapView.getZoomLevelDouble());
                centerLat  = gs.getLatitude();
                centerLon  = gs.getLongitude();
                centerAlt  = 0;
                rebuildGpsInfoOverlay();
                return false;
            }

            @Override
            public boolean onZoom(ZoomEvent event) {
                liveFollowMode = false;
                if (btnMeRef != null) btnMeRef.setTextColor(Color.GRAY);
                if (replayInProgress) {
                    replayFollowMode = false;
                    liveUpdatesPausedFloatie.setVisibility(View.VISIBLE);
                    replayPausedFloatie.setVisibility(View.VISIBLE);
                }
                IGeoPoint gs = mapView.getMapCenter();
                centerZoom = (int) Math.round(mapView.getZoomLevelDouble());
                centerLat  = gs.getLatitude();
                centerLon  = gs.getLongitude();
                centerAlt  = 0;
                rebuildGpsInfoOverlay();
                return false;
            }
        });

        /*
        compassOverlay = new CompassOverlay(
                this, new InternalCompassOrientationProvider(this), mapView);
        compassOverlay.enableCompass();
        mapView.getOverlays().add(compassOverlay);
         */

        // replay overlays - added to map but empty until replay starts
        replayPointsOverlay = new org.osmdroid.views.overlay.Polyline();
        replayPointsOverlay.setColor(Color.argb(180, 255, 165, 0)); // orange trail
        replayPointsOverlay.setWidth(4f);
        mapView.getOverlays().add(replayPointsOverlay);

        replayHeadMarker = new org.osmdroid.views.overlay.Marker(mapView);
        replayHeadMarker.setAnchor(
                org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                org.osmdroid.views.overlay.Marker.ANCHOR_CENTER);
        mapView.getOverlays().add(replayHeadMarker);

        // WebView init
        WebView.setWebContentsDebuggingEnabled(true);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");

        // TODO: confirm MANAGE_ALL_FILES request fires correctly on target
        // devices.  Currently all permissions granted manually at sideload.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivity(intent);
            }
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUri = prefs.getString(PREF_TREE_URI, null);

        if (savedUri == null) {
            // first launch - ask user to pick a folder
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, REQUEST_TREE);
        } else {
            // already have a folder, go straight to UI
            webView.loadUrl("file:///android_asset/index.html");
            startLoggingDefault();
        }
        initOsmdroid();
    }

    /*
     * Syncs the zoom slider thumb to the current map zoom level.
     * No-op while the user is actively dragging the slider (sliderTracking == true)
     * to avoid feedback loops.
     */
    /*
    private void syncSliderToMap() {
        if (!sliderTracking) {
            int z = (int) Math.round(mapView.getZoomLevelDouble()) - 1;
            zoomSlider.setProgress(Math.max(0, Math.min(z, zoomSlider.getMax())));
        }
    }
     */



    /**
     * Called from WebAppInterface.replayPoint() on each replay step.
     * Adds the point to the current polyline segment.  If the jump
     * from the last point exceeds one mile, drops a dot at the remote
     * point and starts a fresh segment - map does not follow across
     * the gap.  Enforces MAX_REPLAY_POINTS by removing the oldest
     * segment when exceeded.
     *
     * @param data JSON string from JS, contains t, lat, lon, etc.
     */
    public static void handleReplayPoint(String data) {
        try {
            org.json.JSONObject d = new org.json.JSONObject(data);
            if (!d.has("t")) return;
            double lat = d.getDouble("lat");
            double lon = d.getDouble("lon");
            GeoPoint pt = new GeoPoint(lat, lon);
            boolean gap = false;

            if (lastReplayPoint != null) {
                double dist = pt.distanceToAsDouble(lastReplayPoint);
                if (dist > GAP_METERS) {
                    gap = true;
                    org.osmdroid.views.overlay.Marker dot =
                            new org.osmdroid.views.overlay.Marker(mapView);
                    dot.setPosition(pt);
                    dot.setAnchor(
                            org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                            org.osmdroid.views.overlay.Marker.ANCHOR_CENTER);
                    dot.setIcon(makeGapDot());
                    dot.setTitle("");
                    mapView.getOverlays().add(dot);
                    replayGapDots.add(dot);
                    startNewReplaySegment();
                }
            }

            replayPointsOverlay.addPoint(pt);
            lastReplayPoint = pt;
            replayPointCount++;

            if (replayPointCount > MAX_REPLAY_POINTS
                    && !replaySegments.isEmpty()) {
                org.osmdroid.views.overlay.Polyline oldest =
                        replaySegments.remove(0);
                replayPointCount -= oldest.getActualPoints().size();
                mapView.getOverlays().remove(oldest);
            }

            replayHeadMarker.setPosition(pt);

            if (replayFollowMode && !gap && (mapView.getProjection() != null) ) {
                mapView.post(() -> mapView.getController().animateTo(pt));
            }

            mapView.invalidate();
            centerLat  = lat;
            centerLon  = lon;
            centerAlt  = d.optDouble("alt", 0);
            centerZoom = (int) Math.round(mapView.getZoomLevelDouble());
            updateGpsInfoOverlay(d.getString("t"), lat, lon,
                    d.optDouble("alt",  0),
                    d.optDouble("spd",  0),
                    d.optDouble("crs",  0));

        } catch (Exception e) {
            android.util.Log.e("Hansel",
                    "replayPoint error: " + e.getMessage());
        }
    }

    /**
     * Starts a fresh polyline segment and tracks it in replaySegments.
     * Call once from onCreate() to initialize, then on each gap.
     */
    private static void startNewReplaySegment() {
        replayPointsOverlay = new org.osmdroid.views.overlay.Polyline();
        replayPointsOverlay.setColor(REPLAY_LINE_COLOR);
        replayPointsOverlay.setWidth(REPLAY_LINE_WIDTH);
        mapView.getOverlays().add(replayPointsOverlay);
        replaySegments.add(replayPointsOverlay);
    }

    /**
     * Clears all replay overlays and resets state.
     * Call on stop, rewind, or new file load.
     */
    public static void clearReplay() {
        for (org.osmdroid.views.overlay.Polyline seg : replaySegments) {
            mapView.getOverlays().remove(seg);
        }
        replaySegments.clear();
        for (org.osmdroid.views.overlay.Marker dot : replayGapDots) {
            mapView.getOverlays().remove(dot);
        }
        replayGapDots.clear();
        replayPointCount = 0;
        lastReplayPoint  = null;
        liveFollowMode   = true;
        startNewReplaySegment();
        mapView.invalidate();
    }

    /**
     * Creates a filled circle bitmap for gap dot markers.
     * 3px radius matches JS arc() dot.  12x12 bitmap for clean edges.
     * Fixed screen size, zoom-invariant.
     */
    private static android.graphics.drawable.Drawable makeGapDot() {
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(
                12, 12, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(REPLAY_LINE_COLOR);
        c.drawCircle(6, 6, 3, p);
        return new android.graphics.drawable.BitmapDrawable(
                mapView.getResources(), bm);
    }

    @Override
    public void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        mapView.onPause();
    }

    private void initOsmdroid() {
        android.util.Log.d("Hansel", "initOsmDroid() firing");
        java.io.File cacheDir = new java.io.File(getExternalFilesDir(null), "tiles");
        android.util.Log.d("Hansel", "tile cache path: " + cacheDir.getAbsolutePath()
                + " exists=" + cacheDir.exists()
                + " writable=" + cacheDir.canWrite());
        org.osmdroid.config.Configuration.getInstance()
                .setOsmdroidBasePath(getExternalFilesDir(null));
        org.osmdroid.config.Configuration.getInstance()
                .setOsmdroidTileCache(cacheDir);
        org.osmdroid.config.Configuration.getInstance()
                .setExpirationOverrideDuration( 1000L * 60 * 60 * 24 * 365 );
    }


// ====
// Call this from the location callback whenever a new fix arrives.
// All values reflect the same instant - timestamp is honest last-fix time.
// ====

    /**
     * Called from handleLocation() (1Hz live) and handleReplayPoint().
     * Updates cached status fields and triggers a full overlay rebuild.
     *
     * @param fixTime timestamp string yyyy-MM-dd_HH-mm-ss HST.
     * @param lat     latitude decimal degrees.
     * @param lon     longitude decimal degrees.
     * @param altFt   altitude in feet.
     * @param spdMph  speed in MPH.
     * @param crsDeg  course in degrees from north (not shown - arrow shows it graphically).
     */
    public static void updateGpsInfoOverlay(String fixTime, double lat, double lon,
                                            double altFt, double spdMph, double crsDeg) {
        if (gpsInfoOverlay == null) return;

        // translation of the javascript timestamp format
        String ts = fixTime.replace("_", " ")
                .replaceAll("-(\\d{2})-(\\d{2})$", ":$1:$2");

        // mostCurrentGPS line: timestamp first, lat, lon, alt, spd
        // For example: 2026-06-08 18:36:21  19.411411 -155.269269  1242ft  0 MPH
        statusLine1 = String.format(java.util.Locale.US,
                "%s  %.6f %.6f  %dft  %d MPH",
                ts, lat, lon, (int) altFt, (int) spdMph);

        updateSkyOverlay(fixTime.substring(0, 10));
        rebuildGpsInfoOverlay();
    }

// ====
// Helpers
// ====

    /**
     *  Returns OSMDroid cache hit rate as "nn.n%" or "n/a" if unavailable.
     * TODO: find a public API for OSMDroid 6.1.18 cache hit rate.
     * TODO: cache my files atop of their bad caching and calculate my own success rate
     */
    private static String getCacheRate() {
        return "n/a";
    }

/*
    private static String getCacheRate() {
        try {
            org.osmdroid.tileprovider.MapTileProviderBase p =
                    mapView.getTileProvider();
            long success = p.mTileCache.getHitCount();
            long total   = p.mTileCache.getHitCount()
                    + p.mTileCache.getMissCount();
            if (total == 0) return "n/a";
            return String.format(java.util.Locale.US, "%.1f%%",
                    100.0 * success / total);
        } catch (Exception e) {
            return "n/a";
        }
    }
*/

    /**
     *  Reads the first cpu thermal zone and converts to Fahrenheit.
     *  Supposedly manufacturer-specific code, but doesn't work on any phone on earth.
     */
    private static float getCpuTempF() {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader(
                            "/sys/class/thermal/thermal_zone0/temp") );
            float raw = Float.parseFloat( br.readLine().trim() );
            br.close();
            // most devices report millidegrees C, some report degrees C
            float c = raw > 1000 ? raw / 1000f : raw;
            return c * 9f / 5f + 32f;
        } catch (Exception e) {
            return 0f;
        }
    }


    private static void recalcDailyIfNeeded(String t, double lat, double lon) {
        String date = t.substring(0, 10); // "yyyy-MM-dd"
        if (date.equals(lastCalcDate)) return;
        lastCalcDate = date;
        cachedMoon = calcMoonPhase(date);
        cachedSun  = calcSunTimes(date, lat, lon);
    }

    public static String getMoonPhase(String t, double lat, double lon) {
        recalcDailyIfNeeded(t, lat, lon);
        return cachedMoon;
    }

    public static String[] getSunTimes(String t, double lat, double lon) {
        recalcDailyIfNeeded(t, lat, lon);
        return cachedSun;
    }


// ====


    /**
     * Returns moon phase label and days to next FM or NM.
     * e.g. "WG FM in 4d"
     * Reference new moon: 2000-01-06 - a known NM date, no time needed.
     * Cycle = 29.53059 days.
     */
    private static String calcMoonPhase(String date) {
        // parse date digits directly
        int y = Integer.parseInt(date.substring(0, 4));
        int m = Integer.parseInt(date.substring(5, 7));
        int d = Integer.parseInt(date.substring(8, 10));

        // days since reference new moon 2000-01-06 using integer day arithmetic
        // Julian Day Number - no time component needed, day resolution is fine
        long jd  = julianDay(y, m, d);
        long jd0 = julianDay(2000, 1, 6); // reference NM
        double age = ((jd - jd0) % 29.53059 + 29.53059) % 29.53059; // 0..29.53

        // 8 named phases, 28-step label array
        String phase;
        double daysToFM;
        double daysToNM;
        if      (age <  1.85) { phase = "NM"; }
        else if (age <  7.38) { phase = "WC"; }
        else if (age < 11.07) { phase = "FQ"; }
        else if (age < 14.77) { phase = "WG"; }
        else if (age < 16.61) { phase = "FM"; }
        else if (age < 22.15) { phase = "WG"; } // waning gibbous
        else if (age < 25.84) { phase = "LQ"; }
        else                  { phase = "WC"; } // waning crescent

        // days to next FM and NM
        daysToFM = (14.765 - age + 29.53059) % 29.53059;
        daysToNM = (29.53059 - age) % 29.53059;

        if (phase.equals("FM")) return "FM NM in " + (int) Math.ceil(daysToNM) + "d";
        if (phase.equals("NM")) return "NM FM in " + (int) Math.ceil(daysToFM) + "d";

        // approaching FM or NM - show whichever is closer
        if (daysToFM <= daysToNM) {
            return phase + " FM in " + (int) Math.ceil(daysToFM) + "d";
        } else {
            return phase + " NM in " + (int) Math.ceil(daysToNM) + "d";
        }
    }

    /**
     * Integer Julian Day Number from calendar date.
     * No time component - day resolution only.
     * Standard formula, no library needed.
     */
    private static long julianDay(int y, int m, int d) {
        int a = (14 - m) / 12;
        int yy = y + 4800 - a;
        int mm = m + 12 * a - 3;
        return d + (153 * mm + 2) / 5 + 365L * yy + yy / 4 - yy / 100 + yy / 400 - 32045;
    }

// ====

    /**
     * Returns [astroRise, nautRise, civilRise, civilSet, nautSet, astroSet]
     * as "HH:mm" strings in HST.
     *
     * Solar noon is calculated from longitude only - no TimeZone object,
     * no UTC conversion.  HST = UTC-10, so solar noon in HST minutes from
     * midnight = 720 - 4*lon - eot + 600  (the +600 converts UTC noon to HST).
     * lon is negative for west, e.g. -155.29 for Kilauea.
     *
     * Depression angles: astronomical=18, nautical=12, civil=6 degrees.
     */
    private static String[] calcSunTimes(String date, double lat, double lon) {
        int y = Integer.parseInt(date.substring(0, 4));
        int m = Integer.parseInt(date.substring(5, 7));
        int d = Integer.parseInt(date.substring(8, 10));

        // day of year
        int doy = dayOfYear(y, m, d);

        // solar declination (degrees)
        double decl = 23.45 * Math.sin(Math.toRadians(360.0 / 365.0 * (doy - 81)));

        // equation of time (minutes) - Spencer formula
        double b   = Math.toRadians(360.0 / 365.0 * (doy - 81));
        double eot = 9.87 * Math.sin(2*b) - 7.53 * Math.cos(b) - 1.5 * Math.sin(b);

        // solar noon in minutes from HST midnight
        // UTC solar noon = 720 - 4*lon - eot  (lon negative for west)
        // HST = UTC - 600 minutes
        double solarNoonHST = 720.0 - 4.0 * lon - eot - 600.0;

        double[] depressions = {18.0, 12.0, 6.0};
        String[] result = new String[6];

        for (int i = 0; i < 3; i++) {
            double cosH =
                    (Math.cos(Math.toRadians(90.0 + depressions[i]))
                            - Math.sin(Math.toRadians(lat)) * Math.sin(Math.toRadians(decl)))
                            / (Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(decl)));

            if (cosH < -1.0 || cosH > 1.0) {
                result[i]     = "--:--";
                result[5 - i] = "--:--";
                continue;
            }

            double hMin    = Math.toDegrees(Math.acos(cosH)) * 4.0; // minutes
            double riseMin = solarNoonHST - hMin;
            double setMin  = solarNoonHST + hMin;

            result[i]     = minsToHHMM(riseMin);
            result[5 - i] = minsToHHMM(setMin);
        }
        return result;
    }

    /** Converts minutes-from-midnight to "HH:mm" string. Wraps at 0 and 1440. */
    private static String minsToHHMM(double mins) {
        int total = (int) Math.round(mins) % 1440;
        if (total < 0) total += 1440;
        return String.format(java.util.Locale.US, "%02d:%02d",
                total / 60, total % 60);
    }

    /** Day of year, 1-based. Accounts for leap years. */
    private static int dayOfYear(int y, int m, int d) {
        int[] dim = {31,28,31,30,31,30,31,31,30,31,30,31};
        if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) dim[1] = 29;
        int doy = d;
        for (int i = 0; i < m - 1; i++) doy += dim[i];
        return doy;
    }

    /**
     * Returns one of 28 moon phase characters based on age of moon at fixTime.
     * Cycle length 29.53 days.  Index 0 = new moon, 14 = full moon.
     * Uses Unicode 0x1F311-0x1F318 cycled - falls back to ASCII label if
     * rendering is a concern.
     *
     * NOTE: returns a plain ASCII abbreviation for now pending font confirmation.
     * Replace chars[] entries with Unicode moon emoji if device renders them.
     */
    private static String calcMoonPhase(long fixTime) {
        // known new moon reference: 2000-01-06 18:14 UTC = 947182440000L ms
        final long NEW_MOON_REF_MS = 947182440000L;
        final double CYCLE_MS = 29.530588 * 24 * 60 * 60 * 1000.0;
        double age = ((fixTime - NEW_MOON_REF_MS) % CYCLE_MS + CYCLE_MS) % CYCLE_MS;
        // 28 steps
        String[] chars = {
                "NM", "WC1","WC2","WC3","WC4","WC5","WC6",
                "FQ", "WG1","WG2","WG3","WG4","WG5","WG6",
                "FM", "WG7","WG8","WG9","WGA","WGB","WGC",
                "LQ", "WC7","WC8","WC9","WCA","WCB","WCC"
        };
        int idx = (int) (age / CYCLE_MS * 28) % 28;
        return chars[idx];
    }

    /**
     * Returns [astroRise, nautRise, civilRise, civilSet, nautSet, astroSet]
     * as HH:mm strings in HST for the date of fixTime at the given lat/lon.
     * Uses simple declination/hour-angle calculation - accuracy within 1-2 min.
     */
    private static String[] calcSunTimes(double lat, double lon, long fixTime) {
        // depression angles in degrees: astronomical=18, nautical=12, civil=6
        double[] depressions = {18.0, 12.0, 6.0};
        String[] result = new String[6];

        java.util.Calendar cal = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Pacific/Honolulu"));
        cal.setTimeInMillis(fixTime);
        int doy = cal.get(java.util.Calendar.DAY_OF_YEAR);
        int year = cal.get(java.util.Calendar.YEAR);

        // solar declination (degrees)
        double decl = 23.45 * Math.sin(Math.toRadians(360.0 / 365.0 * (doy - 81)));

        // equation of time approximation (minutes)
        double b = Math.toRadians(360.0 / 365.0 * (doy - 81));
        double eot = 9.87 * Math.sin(2*b) - 7.53 * Math.cos(b) - 1.5 * Math.sin(b);

        // solar noon in HST minutes from midnight
        // HST = UTC-10, lon correction: 4 min per degree
        double solarNoonMin = 720 - 4 * lon - eot - (-10 * 60);

        java.text.SimpleDateFormat hhmm =
                new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        hhmm.setTimeZone(java.util.TimeZone.getTimeZone("Pacific/Honolulu"));

        for (int i = 0; i < 3; i++) {
            double cosH = (Math.cos(Math.toRadians(90.0 + depressions[i]))
                    - Math.sin(Math.toRadians(lat)) * Math.sin(Math.toRadians(decl)))
                    / (Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(decl)));
            if (cosH < -1 || cosH > 1) {
                // sun never rises/sets at this depression - polar condition
                result[i]     = "--:--";
                result[5 - i] = "--:--";
                continue;
            }
            double hDeg = Math.toDegrees(Math.acos(cosH));
            double riseMin = solarNoonMin - hDeg * 4;
            double setMin  = solarNoonMin + hDeg * 4;
            java.util.Date riseDate = new java.util.Date(
                    cal.getTimeInMillis()
                            - (cal.get(java.util.Calendar.HOUR_OF_DAY) * 3600000L
                            +  cal.get(java.util.Calendar.MINUTE) * 60000L
                            +  cal.get(java.util.Calendar.SECOND) * 1000L)
                            + (long)(riseMin * 60000));
            java.util.Date setDate = new java.util.Date(
                    cal.getTimeInMillis()
                            - (cal.get(java.util.Calendar.HOUR_OF_DAY) * 3600000L
                            +  cal.get(java.util.Calendar.MINUTE) * 60000L
                            +  cal.get(java.util.Calendar.SECOND) * 1000L)
                            + (long)(setMin * 60000));
            result[i]     = hhmm.format(riseDate);
            result[5 - i] = hhmm.format(setDate);
        }
        return result;
    }

    /**
     * Receives the result of the ACTION_OPEN_DOCUMENT_TREE folder picker launched
     * on first run.  On a successful pick, persists read/write URI permission
     * across reboots via takePersistableUriPermission(), stores the URI string
     * in HanselPrefs, and loads index.html.
     *
     * <p>Only REQUEST_TREE (1094) is handled here.  The 1094 value is a mnemonic
     * for the version milestone where this code solidified: v1.0 meets v0.94.
     * Lame but memorable.</p>
     *
     * <p>startLoggingDefault() is intentionally commented out here.  The folder
     * picker fires only on first launch, and at that point index.html has not
     * finished loading yet.  The JS side calls startLogging() once it is ready.
     * On subsequent launches startLoggingDefault() fires from onCreate() instead,
     * where the timing is safe.</p>
     *
     * TODO: Add a "change folder" option before leaving sideload-only stage.
     * @param requestCode the request that triggered this result.
     * @param resultCode  RESULT_OK on success, RESULT_CANCELED if user dismissed.
     * @param data        the Intent carrying the selected tree URI, or null.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_TREE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();

            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(PREF_TREE_URI, uri.toString())
                    .apply();

            webView.loadUrl("file:///android_asset/index.html");

            /**
             * <p>startLoggingDefault() is commented out here and must stay that way.
             * When it was active on this path, the service started twice: once here
             * before index.html had loaded, and again when the JS called startLogging()
             * on page load.  The double-start caused the location listener to register
             * twice, producing duplicate trackpoints and unpredictable rotation behavior.
             * Days of debugging.  The commented line is a tombstone - do not resurrect
             * it without also adding a double-start guards in LocationService
             *  and index.html.</p>
             */
            //startLoggingDefault();

            /**
             * The OpenStreetMap.anDroid cache stuff
             */
            initOsmdroid();

        }
    }

    /**
     * Starts LocationService with the default 30-second interval and 3600-second
     * rollover.  Called from onCreate() on subsequent launches only.
     *
     * <p>Interval defaults to 30_000ms.  The HanselPrefs key is a fossil from an
     * earlier interval-selector design.  Value will always be 30_000 in practice.</p>
     *
     * TODO: Remove once BootReceiver is implemented (v0.99).
     */
    private void startLoggingDefault() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int interval = prefs.getInt("last_interval", 1000); //30_000 30000
        Intent i = new Intent(this, LocationService.class);
        i.putExtra("interval", interval);
        i.putExtra("rollover", 3600);
        ContextCompat.startForegroundService(this, i);
    }


    private static boolean verifyCacheWritable(java.io.File cacheDir) {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs();
            java.io.File test = new java.io.File(cacheDir, ".writetest");
            test.createNewFile();
            test.delete();
            return true;
        } catch (Exception e) {
            android.util.Log.e("Hansel", "cache not writable: "
                    + e.getMessage());
            return false;
        }
    }

    /** Hardcoded Kilauea center coordinates for sky calculations. */
    private static final double SKY_LAT =  19.411411;
    private static final double SKY_LON = -155.269269;

    /** Accumulated sky bar lines for replay mode (up to 10). */
    private static final java.util.LinkedList<String> skyBarLines =
            new java.util.LinkedList<>();

    /**
     * Updates cached center overlay fields and triggers overlay rebuild.
     * Called from onScroll(), onZoom(), and handleReplayPoint().
     * Alt is 0 when called from pan/zoom (shown as "alt:?").
     * TODO: look up nearest recorded alt from altitude database within 25ft.
     *
     * @param lat   map center latitude decimal degrees.
     * @param lon   map center longitude decimal degrees.
     * @param altFt recorded altitude feet, 0 if unknown.
     * @param zoom  current map zoom level.
     */
    public static void updateCenterOverlay(double lat, double lon,
                                           double altFt, int zoom) {
        centerLat  = lat;
        centerLon  = lon;
        centerAlt  = altFt;
        centerZoom = zoom;
        rebuildGpsInfoOverlay();
    }

    /**
     * Returns solar altitude in degrees for a given fractional Julian Day (UT)
     * at the given coordinates.  Accurate to ~1 degree - sufficient for
     * twilight zone boundaries.
     *
     * @param jd  fractional Julian Day in UT.
     * @param lat latitude in decimal degrees.
     * @param lon longitude in decimal degrees.
     * @return solar altitude in degrees, negative below horizon.
     */
    private static double calcSunAltitude(double jd, double lat, double lon) {
        double n   = jd - 2451545.0;
        double L   = (280.460 + 0.9856474 * n) % 360.0;
        double g   = Math.toRadians((357.528 + 0.9856003 * n) % 360.0);
        double lam = Math.toRadians(L + 1.915 * Math.sin(g)
                + 0.020 * Math.sin(2*g));
        double eps    = Math.toRadians(23.439 - 0.0000004 * n);
        double sinDec = Math.sin(eps) * Math.sin(lam);
        double decl   = Math.asin(sinDec);
        double ra     = Math.atan2(Math.cos(eps) * Math.sin(lam), Math.cos(lam));
        double gmst   = (280.46061837 + 360.98564736629 * n) % 360.0;
        double ha     = Math.toRadians(gmst + lon - Math.toDegrees(ra));
        double sinAlt = Math.sin(Math.toRadians(lat)) * Math.sin(decl)
                + Math.cos(Math.toRadians(lat)) * Math.cos(decl) * Math.cos(ha);
        return Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, sinAlt))));
    }

    /**
     * Returns lunar altitude in degrees for a given fractional Julian Day (UT)
     * at the given coordinates.  Low-precision (~1-2 degrees), sufficient for
     * above/below horizon and 30-degree elevation checks.
     *
     * @param jd  fractional Julian Day in UT.
     * @param lat latitude in decimal degrees.
     * @param lon longitude in decimal degrees.
     * @return lunar altitude in degrees, negative below horizon.
     */
    private static double calcMoonAltitude(double jd, double lat, double lon) {
        double n  = jd - 2451545.0;
        double Lm = (218.316 + 13.176396 * n) % 360.0;
        double Mm = Math.toRadians((134.963 + 13.064993 * n) % 360.0);
        double Fm = Math.toRadians((93.272  + 13.229350 * n) % 360.0);
        double lam = Math.toRadians(Lm
                + 6.289 * Math.sin(Mm)
                - 1.274 * Math.sin(2*Math.toRadians(Lm) - Mm)
                + 0.658 * Math.sin(2*Math.toRadians(Lm)));
        double beta   = Math.toRadians(5.128 * Math.sin(Fm));
        double eps    = Math.toRadians(23.439 - 0.0000004 * n);
        double sinDec = Math.sin(eps) * Math.sin(lam) * Math.cos(beta)
                + Math.cos(eps) * Math.sin(beta);
        double decl   = Math.asin(Math.max(-1.0, Math.min(1.0, sinDec)));
        double ra     = Math.atan2(
                Math.sin(lam) * Math.cos(eps) * Math.cos(beta)
                        - Math.sin(beta) * Math.sin(eps),
                Math.cos(lam) * Math.cos(beta));
        double gmst   = (280.46061837 + 360.98564736629 * n) % 360.0;
        double ha     = Math.toRadians(gmst + lon - Math.toDegrees(ra));
        double sinAlt = Math.sin(Math.toRadians(lat)) * Math.sin(decl)
                + Math.cos(Math.toRadians(lat)) * Math.cos(decl) * Math.cos(ha);
        return Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, sinAlt))));
    }

    /**
     * Returns lunar illumination fraction 0.0-1.0 for a given Julian Day.
     * 0.0 = new moon, 1.0 = full moon.
     *
     * @param jd fractional Julian Day in UT.
     * @return illumination fraction 0.0 to 1.0.
     */
    private static double calcMoonIllumination(double jd) {
        double n   = jd - 2451545.0;
        double Lm  = Math.toRadians((218.316 + 13.176396 * n) % 360.0);
        double Ls  = Math.toRadians((280.460 +  0.9856474 * n) % 360.0);
        double Mm  = Math.toRadians((134.963 + 13.064993 * n) % 360.0);
        double Ms  = Math.toRadians((357.528 +  0.9856003 * n) % 360.0);
        double elong = Math.acos(Math.max(-1.0, Math.min(1.0,
                Math.sin(Ls + 1.915*Math.sin(Ms)) * Math.sin(Lm + 6.289*Math.sin(Mm))
                        + Math.cos(Ls + 1.915*Math.sin(Ms)) * Math.cos(Lm + 6.289*Math.sin(Mm)))));
        return (1.0 - Math.cos(elong)) / 2.0;
    }

    /**
     * Builds the 72-character sky timeline string for the given date.
     * Timeline runs noon HST to noon HST next day (midnight at center).
     * Each character represents 20 minutes.
     *
     * Character key (priority order, dark to light):
     *   .  full daylight, moon not notable
     *   ,  daylight, moon above horizon but below 30 deg
     *   '  daylight, moon above 30 deg elevation
     *   -  civil twilight (sun 0 to -6 deg)
     *   =  nautical twilight (sun -6 to -12 deg)
     *   /  astronomical twilight (sun -12 to -18 deg)
     *   *  astronomical dark, moon below horizon (Milky Way viable)
     *   m  dark, moon above horizon, illumination >50pct
     *   n  dark, moon above horizon, illumination <50pct
     *
     * @param date date string yyyy-MM-dd in HST.
     * @return 72-character sky bar string.
     */
    private static String calcSkyBar(String date) {
        int y  = Integer.parseInt(date.substring(0, 4));
        int mo = Integer.parseInt(date.substring(5, 7));
        int d  = Integer.parseInt(date.substring(8, 10));

        // noon HST = UT - 10h.  julianDay() returns integer JD at noon UT.
        // noon HST as fractional JD = julianDay(y,mo,d) - 10.0/24.0
        double jdStart = julianDay(y, mo, d) - 10.0 / 24.0;

        StringBuilder sb = new StringBuilder(36);
        for (int i = 0; i < 36; i++) {
            // center of this 40-min slot in fractional JD (UT)
            double jd = jdStart + (i * 40.0 + 20.0) / 1440.0;

            double sunAlt  = calcSunAltitude(jd,  SKY_LAT, SKY_LON);
            double moonAlt = calcMoonAltitude(jd, SKY_LAT, SKY_LON);
            double moonIll = calcMoonIllumination(jd);

            char c;
            if (sunAlt >= 6.0) {
                // full daylight
                if      (moonAlt > 30.0) c = '\'';
                else if (moonAlt >  0.0) c = ',';
                else                     c = '.';
            } else if (sunAlt >=  0.0)  { c = '-'; // civil twilight
            } else if (sunAlt >= -6.0)  { c = '='; // nautical twilight
            } else if (sunAlt >= -12.0) { c = '/'; // astronomical twilight
            } else {
                // astronomical darkness
                if      (moonAlt <= 0.0) c = '*';
                else if (moonIll >= 0.5) c = 'm';
                else                     c = 'n';
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Updates the sky bar lines list and triggers overlay rebuild.
     * In live mode: single entry, always today.
     * In replay mode: accumulates up to (overlayLines - 5) entries,
     * newest at bottom, oldest dropped from top.
     *
     * @param date date string yyyy-MM-dd HST.
     */
    public static void updateSkyOverlay(String date) {
        String bar  = calcSkyBar(date);
        String day  = date.substring(8, 10);
        String line = day + "|" + bar;

        int maxLines = Math.max(1, overlayLines - 5);

        if (!replayInProgress) {
            skyBarLines.clear();
            skyBarLines.add(line);
        } else {
            if (skyBarLines.isEmpty() ||
                    !skyBarLines.getLast().startsWith(day + "|")) {
                skyBarLines.add(line);
                if (skyBarLines.size() > maxLines) skyBarLines.removeFirst();
            }
        }
    }

    /**
     * Rebuilds and sets gpsInfoOverlay text from cached fields.
     * Also rebuilds skyBarBox text from skyBarLines.
     * Called by updateGpsInfoOverlay(), updateCenterOverlay(), and
     * the MapListener on scroll/zoom.
     *
     * gpsInfoOverlay layout (zero-based):
     *   0: "Hansel v0.987" left, zoomerLine right-justified with monospace spaces
     *   1: mostCurrentGPS (timestamp first, lat, lon, alt, spd)
     *
     * skyBarBox layout:
     *   0: header "   12    16    20    00    04    08    12"
     *   1+: DD|<36-char sky bar>, newest at bottom, grows upward during replay
     */
    public static void rebuildGpsInfoOverlay() {
        if (gpsInfoOverlay == null) return;

        // zoomerLine: lat, lon, alt, zoom - right-justified on line 0
        String altStr = (centerAlt > 0)
                ? String.format(java.util.Locale.US, "%dft", (int) centerAlt)
                : "alt:?";
        String zoomer = String.format(java.util.Locale.US,
                "%.6f %.6f %s Z:%d",
                centerLat, centerLon, altStr, centerZoom);
        String label = "Hansel v0.987";
        // pad between label and zoomer to fill overlayChars
        int spaces = Math.max(1, overlayChars - label.length() - zoomer.length());
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < spaces; i++) pad.append(' ');
        String line0 = label + pad + zoomer;

        String gpsText = line0 + "\n" + statusLine1;

        int color = liveFollowMode ? Color.GREEN : Color.GRAY;
        gpsInfoOverlay.post(() -> {
            gpsInfoOverlay.setText(gpsText);
            gpsInfoOverlay.setTextColor(color);
        });

        // rebuild skyBarBox
        if (skyBarBox == null) return;
        List<String> snapshot = new java.util.ArrayList<>(skyBarLines);
        StringBuilder sb = new StringBuilder();
        sb.append(SKY_HEADER);
        for (String l : snapshot) {
            sb.append("\n").append(l);
        }
        final String skyText = sb.toString();
        skyBarBox.post(() -> skyBarBox.setText(skyText));
    }

}