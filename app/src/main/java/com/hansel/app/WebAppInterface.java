// Hansel - GPS breadcrumb logger v0.97
// Copyright (C) 2026 GrimmsTales
// GNU General Public License v3 -- https://www.gnu.org/licenses/gpl-3.0.html

package com.hansel.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.webkit.JavascriptInterface;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class WebAppInterface {

    Context context;

    public WebAppInterface(Context context) {
        this.context = context;
    }

    @JavascriptInterface
    public void consolidateNow() {
        if (LocationService.instance != null) {
            LocationService.instance.consolidateOldFiles();
        }
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

    private File resolveTreeUriToFile() {
        SharedPreferences prefs = context.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String uriString = prefs.getString(MainActivity.PREF_TREE_URI, null);
        LocationService.instance.say("resolveTreeUri: input=" + uriString);

        if (uriString == null) {
            LocationService.instance.say("resolveTreeUri: no URI in prefs");
            return null;
        }

        String decoded = Uri.decode(uriString);
        int treeIdx = decoded.indexOf("/tree/");
        if (treeIdx < 0) {
            LocationService.instance.say("resolveTreeUri: no /tree/ found");
            return null;
        }

        String docId   = decoded.substring(treeIdx + 6);
        int colonIdx   = docId.indexOf(":");
        if (colonIdx < 0) {
            LocationService.instance.say("resolveTreeUri: no colon in docId=" + docId);
            return null;
        }

        String volumeId = docId.substring(0, colonIdx);
        String path     = docId.substring(colonIdx + 1);
        File result     = new File("/storage/" + volumeId + "/" + path);

        LocationService.instance.say("resolveTreeUri: resolved=" + result.getAbsolutePath());
        return result;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @JavascriptInterface
    public String getFileList() {
        JSONArray arr = new JSONArray();

        File dir = resolveTreeUriToFile();
        if (dir == null) {
            LocationService.instance.say("getFileList: could not resolve directory");
            return "[]";
        }

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            LocationService.instance.say("getFileList: no files found in " + dir.getAbsolutePath());
            return "[]";
        }

        ArrayList<File> ndjson = new ArrayList<>();
        for (File f : files) {
            if (f.getName().endsWith(".ndjson")) ndjson.add(f);
        }

        ndjson.sort((a, b) -> a.getName().compareTo(b.getName()));

        LocationService.instance.say("getFileList: " + ndjson.size() + " files");
        for (File f : ndjson) {
            LocationService.instance.say("  found: " + f.getName());
            arr.put(f.getAbsolutePath());
        }

        return arr.toString();
    }

    @JavascriptInterface
    public String readFile(String path) {
        LocationService.instance.say("readFile: " + path);
        try {
            java.io.InputStream is;
            if (path.startsWith("/")) {
                is = new java.io.FileInputStream(path);
            } else {
                is = context.getContentResolver().openInputStream(Uri.parse(path));
            }
            if (is == null) {
                LocationService.instance.say("readFile: null stream for " + path);
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
            LocationService.instance.say("readFile: done, " + sb.length() + " chars");
            return sb.toString();

        } catch (Exception e) {
            LocationService.instance.say("readFile error: " + e.getMessage());
            return "";
        }
    }
}
