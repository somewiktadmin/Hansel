/*
 * Hansel - GPS breadcrumb logger v0.985
 * Copyright (C) 2026 GrimmsTales
 * GNU General Public License v3 - https://www.gnu.org/licenses/gpl-3.0.html
 */

package com.hansel.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Window;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.core.content.ContextCompat;

import org.osmdroid.views.overlay.TilesOverlay;

/**
 * Hansel GPS breadcrumb logger - v0.985.
 * File format: NDJSON v0.93 (v0.931 pending).
 *
 * <p>MainActivity is the single Activity for the Hansel app.  It owns the
 * WebView that hosts index.html, and it owns the startup permission sequence.
 * Everything the user sees is rendered inside that WebView.  The Java layer
 * exists to broker file I/O, GPS, and Android permissions that JavaScript
 * cannot touch directly.</p>
 *
 * <p>Intended startup flow:
 * <ol>
 *   <li>Request MANAGE_APP_ALL_FILES_ACCESS (SDK 30+) so LocationService and
 *       WebAppInterface can read and write the flat log directory via direct
 *       File access, bypassing SAF entirely.</li>
 *   <li>Request POST_NOTIFICATIONS (SDK 33+) for the foreground service
 *       notification.</li>
 *   <li>Request ACCESS_FINE_LOCATION.</li>
 *   <li>On first launch, open ACTION_OPEN_DOCUMENT_TREE so the user picks a
 *       working directory.  Persist the URI in HanselPrefs so subsequent
 *       launches skip the picker.</li>
 *   <li>Load index.html into the WebView and start LocationService.</li>
 * </ol>
 * </p>
 *
 * <p>Current reality: the app is sideloaded via Android Studio and all
 * permissions are granted manually at install time.  The MANAGE_ALL_FILES
 * request blocks are not reliably firing.  There is no mechanism for the
 * user to change the working directory after first launch.
 * See todos below.</p>
 *
 * <p>Future: MainActivity and the WebView UI are destined to become a
 * separate viewer app (Gretel).  The logging core - LocationService and
 * its file I/O - will move to a standalone headless Hansel app.  That
 * split is post-v1.0.</p>
 *
 * @todo Consolidate the two MANAGE_ALL_FILES blocks into one, in the right
 *       place, and confirm it actually works on the target devices.
 * @todo Add a "change folder" button or menu item so the user can re-pick
 *       the working directory without reinstalling.
 * @todo Add BootReceiver to auto-resume LocationService after reboot, then
 *       remove startLoggingDefault() from this class entirely.
 */
public class MainActivity extends Activity {

    public static WebView webView;
    public static org.osmdroid.views.MapView mapView;

    // 2026-05-10  for Hansel-v0.97 yay
    // At Hansel v1 and 0.94, start using the number 1094
    private static final int REQUEST_TREE = 1094;

    static final String PREFS_NAME   = "HanselPrefs";
    static final String PREF_TREE_URI = "tree_uri";

    /**
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
     * @todo Runtime permission requests are currently bypassed by manual grant
     *       at sideload time.  Before any Google Play submission these blocks
     *       must be tested cold - Google Play requires that the app request
     *       only the permissions it genuinely needs, at the moment it needs
     *       them, with a rationale shown to the user.
     * @param savedInstanceState Android Activity saved state bundle.  Not used
     *       here because the WebView reconstructs cleanly from its asset on every
     *       start.  Would matter if the app needed to restore scroll position,
     *       form state, or other UI context across Activity recreation events
     *       such as screen rotation or system memory reclaim.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

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

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        mapView  = findViewById(R.id.mapView);

        org.osmdroid.config.Configuration.getInstance()
                .setUserAgentValue("Hansel/0.985 personal field logger - single user, Kilauea HI");
        mapView.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK);
        mapView.getController().setZoom(15.0);
        mapView.getController().setCenter(
                new org.osmdroid.util.GeoPoint(19.402, -155.29));
        mapView.getOverlayManager().getTilesOverlay()
                .setColorFilter(TilesOverlay.INVERT_COLORS);

        WebView.setWebContentsDebuggingEnabled(true);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);

        webView.addJavascriptInterface(
                new WebAppInterface(this),
                "AndroidBridge"
        );

        // check if user has already picked a folder
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUri = prefs.getString(PREF_TREE_URI, null);

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

        if (savedUri == null) {
            // first launch - ask user to pick a folder
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, REQUEST_TREE);
        } else {
            // already have a folder, go straight to UI
            webView.loadUrl("file:///android_asset/index.html");
            startLoggingDefault();
        }

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
     * @todo This is currently the only path for setting the working directory.
     *       There is no way to change it after first launch without clearing app
     *       data.  A "change folder" option needs to be added before this app
     *       leaves the sideload-only stage.
     * @param requestCode the request that triggered this result.
     * @param resultCode  RESULT_OK on success, RESULT_CANCELED if user dismissed.
     * @param data        the Intent carrying the selected tree URI, or null.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_TREE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();

            // persist read+write permission across reboots
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            // store for LocationService and WebAppInterface to read
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

        }
    }

    /**
     * Starts LocationService with the default 30-second interval and 3600-second
     * (one hour) rollover.  Called from onCreate() on subsequent launches only,
     * after the WebView is loaded and the JS side is ready to receive events.
     *
     * <p>The interval is read from HanselPrefs "last_interval", defaulting to
     * 30000ms.  There is no UI to change the interval - the preference key is
     * a fossil from an earlier design that had an interval selector.  The value
     * will always be 30000 in practice.</p>
     *
     * <p>ContextCompat.startForegroundService() is used instead of startService()
     * so the call is safe on SDK 26+ where background service starts are
     * restricted.</p>
     *
     * @todo Remove this method entirely once BootReceiver is implemented.
     *       BootReceiver will own the auto-start responsibility.  At that point
     *       the JS side's startLogging() call is the only legitimate way to
     *       start the service from this Activity.
     */
    private void startLoggingDefault() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int interval = prefs.getInt("last_interval", 30000);
        Intent i = new Intent(this, LocationService.class);
        i.putExtra("interval", interval);
        i.putExtra("rollover", 3600);
        ContextCompat.startForegroundService(this, i);
    }

}
