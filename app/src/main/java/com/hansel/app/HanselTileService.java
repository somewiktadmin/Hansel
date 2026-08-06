package com.hansel.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class HanselTileService extends TileService {

    /**
     * say() convenience debug method because LOGCAT fails most
     * of the time on Android Studio Bumblebee.
     */
    private static void say(String something) {
        LocationService.say(something, "tileService.");
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();

        if (LocationService.instance == null) {
            // Mirrors WebAppInterface.startLogging(): read last_interval,
            // start the foreground service.
            SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
            int interval = prefs.getInt("last_interval", 30000);
            Intent i = new Intent(this, LocationService.class);
            i.putExtra("interval", interval);
            i.putExtra("rollover", 3600);
            ContextCompat.startForegroundService(this, i);
            say("onClick pressed, started");
        } else {
            // Mirrors the JS STOP button: rotate only, never actually stop.
            LocationService.instance.rotateNow();
            say("onClick rotateNow()");
        }

        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        if (LocationService.instance != null) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel("Hansel");
            tile.setSubtitle("Logging");
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel("Hansel");
            tile.setSubtitle("Off");
        }
        tile.updateTile();
    }
}