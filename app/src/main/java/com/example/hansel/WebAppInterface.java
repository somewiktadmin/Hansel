// Hansel - GPS breadcrumb logger v0.97
// Copyright (C) 2026 GrimmsTales
// GNU General Public License v3 -- https://www.gnu.org/licenses/gpl-3.0.html

package com.example.hansel;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.JavascriptInterface;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

public class WebAppInterface {

    Context context;

    public WebAppInterface(Context context) {
        this.context = context;
    }

    @JavascriptInterface
    public void setInterval(String interval) {
        if (LocationService.instance != null) {
            LocationService.instance.updateInterval(Integer.parseInt(interval));
        }
    }

    // === get SAF tree dir from SharedPreferences ===
    private DocumentFile getTreeDir() {
        SharedPreferences prefs = context.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String uriString = prefs.getString(MainActivity.PREF_TREE_URI, null);
        if (uriString == null) {
            LocationService.instance.say("No folder selected -- open app to pick one");
            return null;
        }
        return DocumentFile.fromTreeUri(context, Uri.parse(uriString));
    }

    @JavascriptInterface
    public void startLogging(String interval, String rollover) {
        Intent i = new Intent(context, LocationService.class);
        i.putExtra("interval", Integer.parseInt(interval));
        i.putExtra("rollover", Integer.parseInt(rollover));
        ContextCompat.startForegroundService(context, i);
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt("last_interval", Integer.parseInt(interval))
                .apply();
    }

    @JavascriptInterface
    public void mark(String json) {
        if (LocationService.instance != null) {
            LocationService.instance.mark(json);
        }
    }

    @JavascriptInterface
    public void stopLogging() {
        Intent i = new Intent(context, LocationService.class);
        i.setAction(LocationService.ACTION_STOP);
        context.startService(i);
    }

    @JavascriptInterface
    public String getFileList() {
        org.json.JSONArray arr = new org.json.JSONArray();

        DocumentFile dir = getTreeDir();
        if (dir == null) return "[]";

        DocumentFile[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            LocationService.instance.say("getFileList: no files found");
            return "[]";
        }

        // filter to .ndjson, sort by name
        java.util.ArrayList<DocumentFile> ndjson = new java.util.ArrayList<>();
        for (DocumentFile f : files) {
            String name = f.getName();
            if (name != null && name.endsWith(".ndjson")) {
                ndjson.add(f);
            }
        }

        /* Excuse you?  no sorting OUT of chronological order, tyvm
        ndjson.sort((a, b) -> {
            String na = a.getName() != null ? a.getName() : "";
            String nb = b.getName() != null ? b.getName() : "";
            return na.compareTo(nb);
        });
        */

        LocationService.instance.say("getFileList: " + ndjson.size() + " files");

        for (DocumentFile f : ndjson) {
            LocationService.instance.say("  found: " + f.getName());
            // pass URI string -- readFile() will open it via ContentResolver
            arr.put(f.getUri().toString());
        }

        return arr.toString();
    }

    @JavascriptInterface
    public String readFile(String uriString) {
        try {
            Uri uri = Uri.parse(uriString);
            InputStream is = context.getContentResolver().openInputStream(uri);
            if (is == null) return "";

            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            return sb.toString();

        } catch (Exception e) {
            LocationService.instance.say("readFile error: " + e.getMessage());
            return "";
        }
    }
}
