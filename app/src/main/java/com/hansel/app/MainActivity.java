/*
 * Hansel - GPS breadcrumb logger
 * Copyright (C) 2026 GrimmsTales
 * GNU General Public License v3 -- https://www.gnu.org/licenses/gpl-3.0.html
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

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import android.os.Environment;
import android.provider.Settings;
import android.view.Window;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

public class MainActivity extends Activity {

    public static WebView webView;

    // 2026-05-10  for Hansel-v0.97 yay
    // At Hansel v1 and 0.94, start using the number 1094
    private static final int REQUEST_TREE = 1094;

    static final String PREFS_NAME   = "HanselPrefs";
    static final String PREF_TREE_URI = "tree_uri";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        if (Build.VERSION.SDK_INT >= 30
                && !Environment.isExternalStorageManager()) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        }

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
            Toast.makeText(this, "isManager=" + Environment.isExternalStorageManager(), Toast.LENGTH_LONG).show();
        }

        webView = new WebView(this);
        setContentView(webView);

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
            // first launch -- ask user to pick a folder
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, REQUEST_TREE);
        } else {
            // already have a folder, go straight to UI
            webView.loadUrl("file:///android_asset/index.html");
            startLoggingDefault();
        }
    }

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

            //startLoggingDefault();

        }
    }

    private void startLoggingDefault() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int interval = prefs.getInt("last_interval", 30000);
        Intent i = new Intent(this, LocationService.class);
        i.putExtra("interval", interval);
        i.putExtra("rollover", 3600);
        ContextCompat.startForegroundService(this, i);
    }

}
