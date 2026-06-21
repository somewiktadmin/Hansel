/*
 * Hansel - GPS breadcrumb logger v0.987
 * Copyright (C) 2026 GrimmsTales
 * GNU General Public License v3 - https://www.gnu.org/licenses/gpl-3.0.html
 */
package com.hansel.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * Hansel GPS breadcrumb logger - v0.987.
 *
 * Builds the foreground service notification required by Android SDK 26+
 * for any service that runs while the app is in the background.  On SDK 26+
 * a NotificationChannel must be created before the notification can be
 * posted - createNotificationChannel() is idempotent so it is safe to call
 * on every service start.
 *
 * IMPORTANCE_LOW suppresses the notification sound.  The user does not
 * need to be alerted every time the logger starts - they just need the
 * persistent icon confirming it is running.
 *
 * The small icon uses the system built-in ic_menu_mylocation.  A custom
 * Hansel icon is a post-v1.0 cosmetic item.
 *
 * TODO: Replace ic_menu_mylocation with a custom Hansel app icon.
 */
public class NotificationHelper {

    /**
     * say() convenience debug method because LOGCAT fails most
     * of the time on Android Studio Bumblebee.
     */
    private static void say(String something) {
        LocationService.say(something, "notiHlpr.");
    }

    /**
     * Builds and returns the foreground notification for LocationService.
     * Creates the notification channel on SDK 26+ before building.
     *
     * @param ctx the service context, passed from LocationService.onStartCommand().
     * @return the built Notification, ready for startForeground().
     */
    public static Notification build(Context ctx) {

        String channelId = "gps_logger";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "GPS Logger",
                    NotificationManager.IMPORTANCE_LOW
            );

            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

            nm.createNotificationChannel(channel);
        }

        say(channelId);
        return new NotificationCompat.Builder(ctx, channelId)
                .setContentTitle("Breadcrumb Logger")
                .setContentText("Logging GPS in background")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();
    }
}
