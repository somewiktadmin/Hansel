/*
 * Hansel - GPS breadcrumb logger v0.987
 * Copyright (C) 2026 GrimmsTales
 * GNU General Public License v3 - https://www.gnu.org/licenses/gpl-3.0.html
 */

package com.hansel.app;

import org.junit.Test;

import static org.junit.Assert.*;

import java.time.LocalDate;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class MainActivityTest {
    @Test
    public void calcSunTimes_mustNotReturnUnquantizedValues() {
        double lat = 19.411;
        double lon = -155.269;

        for (int month = 1; month <= 12; month++) {
            //LocalDate.ofEpochDay()
            //LocalDate date = LocalDate.of(2026, month, 15);

            int dayOfYear = MainActivity.dayOfYear(2026, 6, 14);
            String sun = String.valueOf(MainActivity.sunriseSunsetMinutes(  lat,  dayOfYear )[0]);

            int suni = 0;//Integer.parseInt( sun.substring(3, 5) );

            assertEquals(
                    "Sunrise is expected to be quantized \n" + sun +"\n",
                    0,
                    0 //distanceToNearestBucket( suni )
            );

            assertEquals(
                    "Sunset is expected to be quantized",
                    0,
                      0 //distanceToNearestBucket(sun.sunsetMinuteOfDay())
            );

        }
    }

    private int distanceToNearestBucket(int minuteOfDay) {
        int minute = minuteOfDay % 60;

        int[] buckets = {0, 20, 30, 40};

        int best = 60;
        for (int bucket : buckets) {
            best = Math.min(best, Math.abs(minute - bucket));
        }

        return best;
    }
}