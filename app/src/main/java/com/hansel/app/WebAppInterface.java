// Hansel - GPS breadcrumb logger v0.986
// Copyright (C) 2026 GrimmsTales
// GNU General Public License v3 - https://www.gnu.org/licenses/gpl-3.0.html

package com.hansel.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.webkit.JavascriptInterface;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;

import java.io.File;
import java.util.ArrayList;

/**
 * Hansel GPS breadcrumb logger - v0.986.
 *
 * <p>WebAppInterface is the bridge between index.html (JavaScript) and the
 * Android Java layer.  Every method annotated @JavascriptInterface is
 * callable from JS as AndroidBridge.methodName().  AndroidBridge is the
 * name assigned in MainActivity.onCreate() when this object is attached
 * to the WebView.</p>
 *
 * <p>All file I/O here uses direct File access via resolveTreeUriToFile(),
 * not SAF DocumentFile.  resolveTreeUriToFile() hand-parses the SAF tree
 * URI stored in HanselPrefs to recover a plain filesystem path.  This is
 * fragile by design - it depends on the /storage/volumeId/path convention
 * holding across Android versions - but it works on all target devices and
 * is far simpler than the SAF alternative for read operations.</p>
 *
 * <p>LocationService.instance is used directly throughout this class to
 * call say(), mark(), rotateNow(), and consolidateOldFiles().  The service
 * is either running or it is not - if it is not running, the null checks
 * short-circuit cleanly.</p>
 *
 * TODO: Remove or replace setInterval() once updateInterval() is fully
 *       retired from LocationService.
 * TODO: Remove getTreeDir() - it is never called.  All file access goes
 *       through resolveTreeUriToFile().  Retained only because removing
 *       dead SAF code requires confirming nothing else sneaks back to it.
 */
public class WebAppInterface {

    Context context;

    /**
     * Constructs the bridge with the Activity context from MainActivity.
     * The context is used for SharedPreferences access and ContentResolver
     * calls in resolveTreeUriToFile() and getTreeDir().
     */
    public WebAppInterface(Context context) { this.context = context; }

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
            LocationService.instance.consolidateOldFiles();
        }
    }

    /**
     * Sets the GPS logging interval.  Delegates to LocationService.updateInterval().
     *
     * TODO: updateInterval() is guarded by if(false) in LocationService and
     *       does nothing.  This method is therefore also a no-op.  Both should
     *       be removed or properly implemented when interval adjustment is
     *       revisited post-v1.0.
     * @param interval requested interval in milliseconds.
     */
    @JavascriptInterface
    public void setInterval(int interval) {
        if (LocationService.instance != null) {
            LocationService.instance.updateInterval( interval );
        }
    }

    /**
     * Returns a DocumentFile handle to the working directory from the SAF
     * tree URI stored in HanselPrefs.  Never called - all file access goes
     * through resolveTreeUriToFile() instead.
     *
     * TODO: Remove once confirmed nothing will revert to SAF access.
     */
    private DocumentFile getTreeDir() {
        SharedPreferences prefs = context.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String uriString = prefs.getString(MainActivity.PREF_TREE_URI, null);
        if (uriString == null) {
            LocationService.instance.say("No folder selected - open app to pick one");
            return null;
        }
        return DocumentFile.fromTreeUri(context, Uri.parse(uriString));
    }

    /**
     * Starts LocationService with the given interval and rollover values.
     * Called from index.html start() on page load.
     *
     * <p>The double-start guard checks LocationService.instance - if the
     * service is already running, the call is silently ignored.  This is
     * the correct behavior: the JS side calls startLogging() on every page
     * load, and the service may already be running from MainActivity's
     * startLoggingDefault() on subsequent launches.  The commented-out
     * say() below was the diagnostic that confirmed the guard was firing
     * correctly during hell week.</p>
     *
     * <p>The interval is persisted to HanselPrefs as last_interval for
     * startLoggingDefault() to read on the next launch.  In practice this
     * will always be 30_000.</p>
     *
     * @param interval logging interval in milliseconds.
     * @param rollover file rotation interval in seconds.
     */
    @JavascriptInterface
    public void startLogging(int interval, int rollover) {
        //Prevent doubletap
        if (LocationService.instance != null) {
            //LocationService.instance.say("startLogging: service already running, skipping");
            return;
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
     * <p>The parsing assumes the URI contains a /tree/ segment followed
     * by a docId of the form volumeId:path.  This convention holds on
     * all target devices but is not guaranteed by the Android API.  If
     * the URI format ever changes - different Android version, different
     * manufacturer - this method will return null and file listing will
     * silently fail.  All intermediate steps are logged via say() to
     * make failures visible in the scrollbox.</p>
     *
     * <p>This fragility is an acceptable tradeoff.  The SAF alternative
     * for recursive directory listing requires DocumentFile.listFiles()
     * which cannot traverse subdirectories, making the ./backup/
     * implementation impossible without this approach.</p>
     */
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

    /**
     * Returns a JSON array string of absolute paths to all .ndjson files
     * in the working directory, sorted chronologically by filename.
     * Called from index.html replay() and replay72h().
     *
     * <p>Sorting by filename is correct because the filename format
     * yyyy-MM-dd_HH-mm-ss sorts lexicographically in chronological order.
     * No date parsing needed.</p>
     *
     * <p>MTP duplicate files named "datetime.ndjson (1)" are excluded by
     * the endsWith(".ndjson") filter - they accumulate harmlessly on the
     * SD card and are invisible to replay.</p>
     *
     * @return JSON array string of absolute file paths, or "[]" on failure.
     */
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

    /**
     * Reads the full contents of a file and returns it as a string.
     * Called from index.html replay() and replay72h() for each file
     * in the replay list.
     *
     * <p>Absolute paths (starting with "/") are read via FileInputStream
     * directly.  All paths returned by getFileList() are absolute, so the
     * ContentResolver branch is a fallback that should never fire in
     * normal operation.</p>
     *
     * <p>All errors are caught, logged via say(), and return an empty
     * string.  A failed read silently skips that file in replay rather
     * than crashing.  The say() output makes the failure visible in the
     * scrollbox.</p>
     *
     * @param path absolute filesystem path or content URI string.
     * @return file contents as a string, or empty string on failure.
     */
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
