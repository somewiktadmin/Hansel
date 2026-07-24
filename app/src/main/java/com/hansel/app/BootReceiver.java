/*
 * Hansel - GPS breadcrumb logger v0.988
 * Copyright (C) 2026 GrimmsTales
 * GNU General Public License v3 - https://www.gnu.org/licenses/gpl-3.0.html
 */
package com.hansel.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The point of hearing that Android has successfully rebooted, is to now
 * go ahead and finish restarting things that need to run all the time,
 * like this breadcrumb location logger.  Remember the point is not security;
 * if your wife (now exwife) finds your visits to your mistress, that's your
 * fault anyway.  The point of this utility is for me to have a fraction of the
 * information the government has about me and where I've been (and when) to
 * satisfy my curiosity.
 *
 * More realistically, to plot out the exact altitudes around the crater rim
 * at Kilauea, Hawaii - information that is not attainable except 8 year old
 * nonsense, from before the last 52 eruptions reshaped the whole shebang.
 *
 * So, if you want encryption or security or other BS, go somewhere else.  Use at
 * your own risk, yada yada yada
 */
public class BootReceiver extends BroadcastReceiver {

    /**
     * say() convenience debug method because LOGCAT fails most
     * of the time on Android Studio Bumblebee.
     */
    private static void say(String something) {
            LocationService.say(something, "bootRec.");
    }

    /**
     * Startup sequence notes:
     *   1) Boot --> BootReceiver.onReceive() fires --> relaunches MainActivity
     *   2) MainActivity.onCreate() --> webView.loadUrl("file:///android_asset/index.html")
     *   3) index.html's window.addEventListener("load", main) --> main() --> start() calls
     *   4) AndroidBridge.startLogging(interval, rollover) calls
     *   5) WebAppInterface.startLogging() --> guards against double-start, then calls
     *   6) ContextCompat.startForegroundService() with the saved interval/rollover from HanselPrefs
     *
     * @param context
     * @param intent
     */
    @Override
    public void onReceive(Context context, Intent intent) {

        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {

            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(launch);

            say("onReceive now restarting Hansel" );
        }
    }
}
