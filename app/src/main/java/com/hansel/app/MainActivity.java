/*
 * Hansel - GPS breadcrumb logger v0.987
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
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.TilesOverlay;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.File;

/**
 * Hansel v0.987 main activity.
 *
 * TODO: Rewrite this Javadoc block once OSMDroid integration and replay are stable.
 *

 ===========================================================================
 HANSEL project-wide DATETIME STANDARD (HDS-7)
 ===========================================================================

 All user-visible dates/times, filenames, directory names, temporary names,
 and generated media names SHALL use LOCAL HST time.  UTC/Unix timestamps
 are NOT permitted in names.  When external interfaces (USGS) require UTC/
 epoch, convert immediately to HDS-7 on ingress.

 Format:	    yyyy-MM-dd_HH-mm-ss		where "_" may be " " for display

 Python:        dt.strftime("%Y-%m-%d_%H-%M-%S")
 Java:          SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")
 JavaScript:    return `${Y}-${M}-${D}_${h}-${m}-${s}`;
 bash:          date +"%Y-%m-%d_%H-%M-%S"
 Windows:       Get-Date -Format 'yyyy-MM-dd_HH-mm-ss'
 Caché:         $ZD($H,3)_"_"_$ZT($P($H,",",2))


 *
 * Future: MainActivity and the WebView UI are destined to become a
 * separate viewer app (Gretel).  The logging core - LocationService and
 * its file I/O - will move to a standalone headless Hansel app.  That
 * split is post-v1.0.
 *
 * TODO Consolidate the two MANAGE_ALL_FILES blocks into one, in the right
 * place, and confirm it actually works on the target devices.
 * TODO: Add a "change folder" button or menu item so the user can re-pick
 * the working directory without reinstalling.
 * todo: Add BootReceiver to auto-resume LocationService after reboot, then
 * remove startLoggingDefault() from this class entirely.
 */
public class MainActivity extends Activity {

    static final String PREFS_NAME = "HanselPrefs";
    static final String PREF_TREE_URI = "tree_uri"; //Hansel ndJSON logs
    static final String PREF_TILE_URI = "3rd tile cache layer"; // supplemental tile cache on SD card
    static final String PREF_SPOOL_URI = "cloud uploads"; // cloud upload spool directory
    private static final int REQUEST_TREE = 1094; // At Hansel v1 and 0.94, started using the number 1094
    //public static  SeekBar zoomSlider;
    //public static  boolean sliderTracking = false; // suppress map->slider echo
    private static final int REQUEST_TILE = 1095;
    private static final int REQUEST_SPOOL = 1096;
    // breadcrumb dot color - toasted white bread gold
    private static final int REPLAY_DOT_COLOR = 0xFFD4A017;
    // TODO: make this a SharedPreferences user setting, range 100-600000
    private static final int MAX_REPLAY_POINTS = 525000;
    // tracks all breadcrumb dot markers for pruning and clearing
    private static final java.util.List<org.osmdroid.views.overlay.Marker>
            replayDots = new java.util.ArrayList<>();
    public static WebView webView;
    public static org.osmdroid.views.MapView mapView;

    //private CompassOverlay compassOverlay;
    public static TextView replayPausedFloatie;
    public static MyLocationNewOverlay locationOverlay;
    public static TextView gpsInfoOverlay;
    public static TextView skyBarBox;

    /** Cached center overlay data - updated on pan/zoom/replay.*/
    public static double zoomerLat = 0;
    public static double zoomerLon = 0;
    public static double zoomerAlt = 0;
    public static int zoomerZoom = 0;

    /** Buttons on right column */
    public static Button btnMe;
    public static Button btnHMM;
    public static Button btnZoomIn;
    public static Button btnZoomOut;

    /** Cached status overlay data - updated 1Hz from handleLocation(). */
    public static String statusLine1 = "";

    /** Usable line count in gpsInfoOverlay - measured after layout. */
    public static int overlayLines = 21;

    /** Usable character width of gpsInfoOverlay - measured after layout. */
    public static int overlayChars = 48;
    public static boolean replayFollowMode = true;
    public static boolean updatingMap = false;
    public static boolean replayInProgress = false;
    public static org.osmdroid.views.overlay.Marker replayHeadMarker;
    public static boolean liveFollowMode = true;
    public static TextView liveUpdatesPausedFloatie;
    public static boolean programmingScroll = false; //for animateTo() scrolling
    private static android.graphics.drawable.Drawable replayDotDrawable = null;

    static final String PREF_MAX_REPLAY_POINTS = "max_replay_points";
    public static int maxReplayPoints = 25000;

    /** say() convenience debug method because LOGCAT fails most
     *  of the time on Android Studio Bumblebee. */
    public void say(String something) { LocationService.say(something, "mainAct."); }

    /**
     * todo update this comment
     */
    private static android.graphics.drawable.Drawable getReplayDot() {
        if (replayDotDrawable != null) return replayDotDrawable;
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(
                8, 8, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(REPLAY_DOT_COLOR);
        c.drawCircle(4, 4, 3f, p);
        replayDotDrawable = new android.graphics.drawable.BitmapDrawable(
                mapView.getResources(), bm);
        return replayDotDrawable;
    }

    // resumeLive: re-enable live map following.  Does NOT touch log files,
    // does NOT call JS stop() - that caused log rotation on every pan.
    // Replay is stopped by setting replayInProgress=false only; the JS
    // replay interval will exhaust naturally or be stopped separately.
    public static void resumeLive() {
        replayInProgress = false;
        replayFollowMode = true;
        liveFollowMode = true;
        btnMe.setTextColor(Color.GREEN);
        liveUpdatesPausedFloatie.setVisibility(View.GONE);
        replayPausedFloatie.setVisibility(View.GONE);
        if (locationOverlay.getMyLocation() != null) {
            mapView.getController().setZoom(15);
            programmingScroll = true;
            //mapView.getController().animateTo( locationOverlay.getMyLocation() );
            mapView.getController().setCenter(locationOverlay.getMyLocation());
        }
    }

    /**
     * "Don't jump when not nearby"
     *
     * Returns true if the last 2 replay points are "nearby" the given point -
     * defined as matching truncated lat and lon to 3 decimal places.
     * Used to gate animateTo() so we only follow when the device is on the
     * same side of the island (sorted inputs from multiple phones can
     * report from distant locations.)
     */
    private static boolean replayIsNearby(GeoPoint pt) {
        if (replayDots.size() < 2) return false;
        String tLat = truncate3(pt.getLatitude());
        String tLon = truncate3(pt.getLongitude());
        // check last 2 dots
        for (int i = replayDots.size() - 2; i < replayDots.size(); i++) {
            GeoPoint p = replayDots.get(i).getPosition();
            if (!truncate3(p.getLatitude()).equals(tLat)) return false;
            if (!truncate3(p.getLongitude()).equals(tLon)) return false;
        }
        return true;
    }

    /** truncate lat,lon values to three decimal places without rounding */
    private static String truncate3(double v) {
        long shifted = (long) (v * 1000);
        return Long.toString(shifted);
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
     * Plots a breadcrumb dot, prunes oldest if over MAX_REPLAY_POINTS,
     * and recenters only when last 2 points are nearby (3 decimal truncation).
     *
     * @param data JSON string from JS, contains t, lat, lon, etc.
     */
    public static void handleReplayPoint(String data) {
        mapView.post(() -> {
            try {
                org.json.JSONObject d = new org.json.JSONObject(data);
                if (!d.has("t")) return;
                double lat = d.getDouble("lat");
                double lon = d.getDouble("lon");
                GeoPoint pt = new GeoPoint(lat, lon);

                // plot breadcrumb dot
                org.osmdroid.views.overlay.Marker dot =
                        new org.osmdroid.views.overlay.Marker(mapView);
                dot.setPosition(pt);
                dot.setAnchor(
                        org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                        org.osmdroid.views.overlay.Marker.ANCHOR_CENTER);
                dot.setIcon(getReplayDot());
                dot.setTitle("");
                mapView.getOverlays().add(dot);
                replayDots.add(dot);

                // prune oldest dot if over limit
                if (replayDots.size() > maxReplayPoints) {
                    org.osmdroid.views.overlay.Marker oldest = replayDots.remove(0);
                    mapView.getOverlays().remove(oldest);
                }

                replayHeadMarker.setPosition(pt);

                if ( replayFollowMode && replayIsNearby(pt) ) {
                    programmingScroll = true;
                    //mapView.getController().animateTo(pt);
                    mapView.getController().setCenter(pt);
                }

                mapView.invalidate();
                zoomerLat = lat;
                zoomerLon = lon;
                zoomerAlt = d.optDouble("alt", 0);
                zoomerZoom = (int) Math.round(mapView.getZoomLevelDouble());
                rebuildGpsInfoOverlay();
                SkyBar.updateSkyOverlay(d.getString("t").substring(0, 10));

            } catch (Exception e) {
                Log.e("Hansel",
                        "replayPoint error: " + e.getMessage());
            }
        }); //post to main UI thread, because this originates from javascript
    }

    /**
     * Clears all replay overlays and resets state.
     * Call on stop, rewind, or new file load.
     */
    public static void clearReplay() {
        for (org.osmdroid.views.overlay.Marker dot : replayDots) {
            mapView.getOverlays().remove(dot);
        }
        replayDots.clear();
        liveFollowMode = true;
        mapView.invalidate();
    }

    /**
     * Creates a filled circle bitmap for breadcrumb dots.
     * Toasted-bread gold, fixed screen size, zoom-invariant.
     *
     private static android.graphics.drawable.Drawable makeReplayDot() {
     android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(
     10, 10, android.graphics.Bitmap.Config.ARGB_8888);
     android.graphics.Canvas c = new android.graphics.Canvas(bm);
     android.graphics.Paint p = new android.graphics.Paint(
     android.graphics.Paint.ANTI_ALIAS_FLAG);
     p.setColor(REPLAY_DOT_COLOR);
     c.drawCircle(5, 5, 3.5f, p);
     return new android.graphics.drawable.BitmapDrawable(
     mapView.getResources(), bm);
     }
     */

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

        SkyBar.updateSkyOverlay(fixTime.substring(0, 10));
        rebuildGpsInfoOverlay();
    }

    /**
     * Returns OSMDroid cache hit rate as "nn.n%" or "n/a" if unavailable.
     * TODO: find a public API for OSMDroid 6.1.18 cache hit rate.
     * TODO: cache my files atop of their bad caching and calculate my own success rate
     */
    private static String getCacheRate() {
        return "n/a";
    }

    /**
     * Reads the first cpu thermal zone and converts to Fahrenheit.
     * Supposedly manufacturer-specific code, but doesn't work on any phone on earth.
     */
    private static float getCpuTempF() {
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader(
                            "/sys/class/thermal/thermal_zone0/temp"));
            float raw = Float.parseFloat(br.readLine().trim());
            br.close();
            // most devices report millidegrees C, some report degrees C
            float c = raw > 1000 ? raw / 1000f : raw;
            return c * 9f / 5f + 32f;
        } catch (Exception e) {
            return 0f;
        }
    }

    private static boolean verifyCacheWritable(java.io.File cacheDir) {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs();
            java.io.File test = new java.io.File(cacheDir, ".writetest");
            test.createNewFile();
            test.delete();
            return true;
        } catch (Exception e) {
            Log.e("Hansel", "cache not writable: "
                    + e.getMessage());
            return false;
        }
    }

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
        zoomerLat = lat;
        zoomerLon = lon;
        zoomerAlt = altFt;
        zoomerZoom = zoom;
        GeoPoint pt = new GeoPoint(lat, lon);
        if (liveFollowMode) mapView.getController().setCenter(pt);
        rebuildGpsInfoOverlay();
    }


//
// Call this from the location callback whenever a new fix arrives.
// All values reflect the same instant - timestamp is honest last-fix time.
//

    /**
     * Rebuilds and sets gpsInfoOverlay text from cached zoomer fields.
     * Called by updateGpsInfoOverlay(), updateCenterOverlay(), and
     * the MapListener on scroll/zoom.
     * skyBarBox is updated separately by updateSkyOverlay().
     *
     * gpsInfoOverlay layout (zero-based):
     * 0: "Hansel v0.987" left, zoomerLine right-justified with monospace spaces
     * 1: mostCurrentGPS (timestamp first, lat, lon, alt, spd)
     */
    public static void rebuildGpsInfoOverlay() {
        if (gpsInfoOverlay == null) return;

        // zoomerLine: lat, lon, alt, zoom - right-justified on line 0
        String altStr = (zoomerAlt > 0)
                ? String.format(java.util.Locale.US, "%dft", (int) zoomerAlt)
                : "alt:?";
        String zoomer = String.format(java.util.Locale.US,
                "%.6f %.6f %s Z:%d",
                zoomerLat, zoomerLon, altStr, zoomerZoom);
        String label = "Hansel v0.987     ";
        // TODO: pad between label and zoomer to fill overlayChars
        int spaces = Math.max( 1, overlayChars - label.length() - zoomer.length() - 1 );
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < spaces; i++) pad.append(' ');
        String line0 = label + pad + zoomer;

        String gpsText = line0 + "\n" + statusLine1;

        int color = liveFollowMode ? Color.GREEN : Color.WHITE;
        gpsInfoOverlay.post(() -> {
            gpsInfoOverlay.setText(gpsText);
            gpsInfoOverlay.setTextColor(color);
        });
    }

//
// Helpers
//


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
     * >>> Hybrid mapView / webView split this version <<<
     *
     * currently playing with mapView experiments...older webView narrative is still
     * somewhat relevant (gosh, two days ago?) as I try different things.
     *
     * Initializes the activity, requests permissions, builds the WebView, and
     * either prompts for a working directory (first launch) or loads the UI and
     * starts the location service (subsequent launches).
     *
     * Permission requests fire in this order: MANAGE_APP_ALL_FILES_ACCESS,
     * POST_NOTIFICATIONS, ACCESS_FINE_LOCATION.  All three are currently granted
     * manually at install time via Android Studio, so the runtime request blocks
     * are present but not reliably exercised.
     *
     * The WebView is configured with JavaScript, DOM storage, and file access
     * enabled.  WebAppInterface is attached as "AndroidBridge", which is the
     * name index.html uses for all Java callbacks.
     *
     * If no working directory URI is stored in HanselPrefs, ACTION_OPEN_DOCUMENT_TREE
     * fires and onActivityResult() handles the response.  If a URI is already
     * stored, the UI loads immediately and startLoggingDefault() starts the
     * service.
     *
     * TODO: Runtime permission requests are currently bypassed by manual grant
     * at sideload time.  Before any Google Play submission these blocks
     * must be tested cold - Google Play requires that the app request
     * only the permissions it genuinely needs, at the moment it needs
     * them, with a rationale shown to the user.
     *
     * @param savedInstanceState Android Activity saved state bundle.  Not used
     *                           here because the WebView reconstructs cleanly from its asset on every
     *                           start.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        say("onCreate starting");

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

        // OSMDroid init - NOTE: This MUST be before the first mapView reference
        initOsmdroid();
        Configuration.getInstance()
                .setUserAgentValue(
                        "Hansel/0.987 personal field logger - single user, multiple test devices, Kilauea HI");
        say( Configuration.getInstance().getOsmdroidBasePath().getAbsolutePath() );
        say( Configuration.getInstance().getOsmdroidTileCache().getAbsolutePath() );

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int interval = prefs.getInt("last_interval", 5000); //30_000 30000
        maxReplayPoints = prefs.getInt(PREF_MAX_REPLAY_POINTS, 25000);

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        mapView = findViewById(R.id.mapView);

        mapView.setTileSource(
                TileSourceFactory.MAPNIK);
        mapView.setBuiltInZoomControls(false);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(14);
        mapView.getController().setCenter(new GeoPoint(19.411411, -155.269269));

        mapView.getOverlayManager().getTilesOverlay()
                .setColorFilter(TilesOverlay.INVERT_COLORS);
        //mapView.getOverlayManager().getTilesOverlay()
        //        .setLoadingBackgroundColor(Color.BLACK);
        mapView.getOverlayManager().getTilesOverlay()
                .setLoadingLineColor(Color.argb(255, 0, 255, 0));

        replayPausedFloatie = findViewById(R.id.replayPausedFloatie);
        liveUpdatesPausedFloatie =
                findViewById(R.id.liveUpdatesPausedFloatie);

        gpsInfoOverlay = findViewById(R.id.gpsInfoOverlay);
        //gpsInfoOverlay.setShadowLayer(2f, 1f, 1f, 0xFF000000);

        skyBarBox = findViewById(R.id.skyBarBox);
        skyBarBox.setShadowLayer(2f, 1f, 1f, 0xFF000000);

        //Typeface courierPrime = getResources().getFont(R.font.courier_prime_regular);
        //Typeface courierPrime = Typeface.createFromAsset(getAssets(), "courier_prime_regular.ttf");
        Typeface courierPrime = Typeface.createFromAsset(getAssets(), "courier_prime_regular.ttf");
        gpsInfoOverlay.setTypeface(courierPrime);
        liveUpdatesPausedFloatie.setTypeface(courierPrime);
        replayPausedFloatie.setTypeface(courierPrime);
        skyBarBox.setTypeface(courierPrime);

        say("screen FONT " + gpsInfoOverlay.getTypeface().toString());
        gpsInfoOverlay.post(() -> {
            if (gpsInfoOverlay.getLineHeight() > 0) {
                overlayLines = gpsInfoOverlay.getHeight() / gpsInfoOverlay.getLineHeight();
            }
            if (gpsInfoOverlay.getPaint() != null && gpsInfoOverlay.getWidth() > 0) {
                float charW = gpsInfoOverlay.getPaint().measureText("M");
                float charsW = gpsInfoOverlay.getPaint().measureText("I");
                if (charsW != charW) {
                    say("Monospace font has been microsofted");
                } else {
                    say("charsW " + charsW + " is good");
                }

                if (charW > 0) overlayChars = (int) (gpsInfoOverlay.getWidth() / charW);
                say("overlayChars screen width chars: " + overlayChars );
            }
        });

        btnMe = findViewById(R.id.btnMe);
        btnHMM = findViewById(R.id.btnHMM);
        btnZoomIn = findViewById(R.id.btnZoomIn);
        btnZoomOut = findViewById(R.id.btnZoomOut);

        btnMe.setTypeface(courierPrime);
        btnHMM.setTypeface(courierPrime);
        btnZoomIn.setTypeface(courierPrime);
        btnZoomOut.setTypeface(courierPrime);

        // [ME] - resume live follow, stop replay, snap to phone position
        btnMe.setOnClickListener(v -> resumeLive());

        // gpsInfoOverlay tap - same as [ME]
        gpsInfoOverlay.setOnClickListener(v -> resumeLive());

        // [HISTORY REPLAYING] tap - stop replay:
        liveUpdatesPausedFloatie.setOnClickListener(v -> {
            if (replayInProgress) {
                webView.post(() ->
                        webView.evaluateJavascript("stopReplay()", null));
            } else {
                resumeLive();
            }
        });

        // [REPLAY PAUSED] tap - resume replay:
        replayPausedFloatie.setOnClickListener(v -> {
            replayFollowMode = true;
            replayPausedFloatie.setVisibility(View.GONE);
            webView.post(() ->
                    webView.evaluateJavascript("resumeReplay()", null));
        });

        // [HMM] - Halemaumau
        btnHMM.setOnClickListener(v -> {
            if (replayInProgress) {
                replayFollowMode = false;
                replayPausedFloatie.setVisibility(View.VISIBLE);
                webView.post(() -> webView.evaluateJavascript("pauseReplay()", null));
            }

            mapView.getController().setZoom(14);
            // adding a little debug info here because this is a relatively harmless
            // place for it, that only appears AFTER all setup is definitely finished.
            //I can re-trigger debug here as often as needed
            say(gpsInfoOverlay.getTypeface().toString());
            if (Build.VERSION.SDK_INT >= 34) {
                say("hmm pressed ");
            }
            programmingScroll = true;
            //mapView.getController().animateTo(new GeoPoint(19.411, -155.269));
            mapView.getController().setCenter(new GeoPoint(19.411, -155.269));
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
                if (programmingScroll) {
                    programmingScroll = false;
                    return false;
                }
                liveUpdatesPausedFloatie.setVisibility(View.VISIBLE);
                liveFollowMode = false;
                if (btnMe != null) btnMe.setTextColor(Color.BLACK);
                // [HISTORY REPLAYING] only shown during replay, not during live pan
                if (replayInProgress) {
                    replayFollowMode = false;
                    liveUpdatesPausedFloatie.setVisibility(View.VISIBLE);
                    replayPausedFloatie.setVisibility(View.VISIBLE);
                    webView.post(() ->
                            webView.evaluateJavascript("pauseReplay()", null));
                }
                IGeoPoint gs = mapView.getMapCenter();
                zoomerZoom = (int) Math.round(mapView.getZoomLevelDouble());
                zoomerLat = gs.getLatitude();
                zoomerLon = gs.getLongitude();
                //zoomerAlt = 0; //TODO get nearest 3 decimal lat,lon altitude or 4 decimal? From my collected data
                rebuildGpsInfoOverlay();
                return false;
            }

            @Override
            public boolean onZoom(ZoomEvent event) {
                return false;
                // zoom in or out is not the same as panning around
                /*
                liveUpdatesPausedFloatie.setVisibility(View.VISIBLE);
                liveFollowMode = false;
                if (btnMe != null) btnMe.setTextColor(Color.WHITE);
                if (replayInProgress) {
                    replayFollowMode = false;
                    liveUpdatesPausedFloatie.setVisibility(View.VISIBLE);
                    replayPausedFloatie.setVisibility(View.VISIBLE);
                }
                IGeoPoint gs = mapView.getMapCenter();
                zoomerZoom = (int) Math.round(mapView.getZoomLevelDouble());
                zoomerLat  = gs.getLatitude();
                zoomerLon  = gs.getLongitude();
                //zoomerAlt  = 0;
                rebuildGpsInfoOverlay();
                return false;
                */
            }
        });


        /*
        compassOverlay = new CompassOverlay(
                this, new InternalCompassOrientationProvider(this), mapView);
        compassOverlay.enableCompass();
        mapView.getOverlays().add(compassOverlay);
         */

        // replay overlays - added to map but empty until replay starts

        replayHeadMarker = new Marker(mapView);
        replayHeadMarker.setAnchor(
                Marker.ANCHOR_CENTER,
                Marker.ANCHOR_CENTER);
        mapView.getOverlays().add(replayHeadMarker);

        // WebView init
        WebView.setWebContentsDebuggingEnabled(true);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");

        // TODO REMOVE THIS
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

        //prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUri = prefs.getString(PREF_TREE_URI, null);

        if (savedUri == null) {
            // first launch - ask user to pick a folder
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, REQUEST_TREE);
        } else {
            // already have a folder, go straight to UI
            webView.loadUrl("file:///android_asset/index.html");
            //startLoggingDefault();
        }
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
        say("initOsmDroid() firing" );
        // TODO wth, this was supposed to be REQUEST_TILE and that was supposed to have a better name
        File cacheDir = new File(getExternalFilesDir(null), "tiles");
        say("tile cache path: " + cacheDir.getAbsolutePath()
                + " exists=" + cacheDir.exists()
                + " writable=" + cacheDir.canWrite() );
        org.osmdroid.config.Configuration.getInstance()
                .setOsmdroidBasePath(getExternalFilesDir(null));
        org.osmdroid.config.Configuration.getInstance()
                .setOsmdroidTileCache(cacheDir);
        org.osmdroid.config.Configuration.getInstance()
                .setExpirationOverrideDuration(1000L * 60 * 60 * 24 * 365);
    }

    /**
     * Receives the result of the ACTION_OPEN_DOCUMENT_TREE folder picker launched
     * on first run.  On a successful pick, persists read/write URI permission
     * across reboots via takePersistableUriPermission(), stores the URI string
     * in HanselPrefs, and loads index.html.
     *
     * Only REQUEST_TREE (1094) is handled here.  The 1094 value is a mnemonic
     * for the version milestone where this code solidified: v1.0 meets v0.94.
     * Lame but memorable.
     *
     * startLoggingDefault() is intentionally commented out here.  The folder
     * picker fires only on first launch, and at that point index.html has not
     * finished loading yet.  The JS side calls startLogging() once it is ready.
     * On subsequent launches startLoggingDefault() fires from onCreate() instead,
     * where the timing is safe.
     *
     * TODO: Add a "change folder" option before leaving sideload-only stage.
     *
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
             * startLoggingDefault() is commented out here and must stay that way.
             * When it was active on this path, the service started twice: once here
             * before index.html had loaded, and again when the JS called startLogging()
             * on page load.  The double-start caused the location listener to register
             * twice, producing duplicate trackpoints and unpredictable rotation behavior.
             * Days of debugging.  The commented line is a tombstone - do not resurrect
             * it without also adding a double-start guard in LocationService
             *  and index.html.
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
     * Interval defaults to 30_000ms.  The HanselPrefs key is a fossil from an
     * earlier interval-selector design.  Value will always be 30_000 in practice.
     *
     * TODO: Remove once BootReceiver is implemented (v0.99).
     */
    private void startLoggingDefault() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int interval = prefs.getInt("last_interval", 5000); //30_000 30000
        Intent i = new Intent(this, LocationService.class);
        i.putExtra("interval", interval);
        i.putExtra("rollover", 3600);
        ContextCompat.startForegroundService(this, i);
    }
}
