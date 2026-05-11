package com.example.hansel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public class NotificationHelper {

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

        return new NotificationCompat.Builder(ctx, channelId)
                .setContentTitle("Breadcrumb Logger")
                .setContentText("Logging GPS in background")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();
    }
}
