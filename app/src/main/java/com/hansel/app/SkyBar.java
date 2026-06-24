/*
 * Hansel - GPS breadcrumb logger v0.987
 * Copyright (C) 2026 GrimmsTales
 * GNU General Public License v3 - https://www.gnu.org/licenses/gpl-3.0.html
 */
package com.hansel.app;

import android.util.Log;

/**
 * SKYBAR DESIGN CONTRACT
 *
 *
 * SkyBar is a photography decision aid.
 *
 * SkyBar is NOT an astronomy application.
 *
 * SkyBar is NOT a scientific instrument.
 *
 * SkyBar is NOT an ephemeris.
 *
 * SkyBar is NOT intended to accurately describe the sky.
 *
 * SkyBar exists to help a photographer answer questions such as:
 *
 * - Should I start gathering equipment?
 * - Is there likely to be a useful dark-sky opportunity tonight?
 * - Should I stay a little longer after sunset?
 * - Should I stay a little longer before leaving at sunrise?
 * - Is the moon likely to interfere?
 *
 * The output is a compact reminder bar.
 *
 * The output is guidance.
 *
 * The output is NOT a prediction suitable for scientific use.
 */
public class SkyBar {

    /**
     * Sky bar header line - constant, 3 leading spaces align DD| prefix.
     */
    public static final String SKY_HEADER = "   12    16    20    00    04    08    12";
    /**
     * Accumulated sky bar lines for replay mode (up to 10).
     */
    public static final java.util.LinkedList<String> skyBarLines =
            new java.util.LinkedList<>();
    /**
     * Lookup table for sunrise/sunset interpolation - see sunBar-style design.
     */
    public static final double[] SKY_LAT_TABLE = {0, 10, 20, 30, 40, 50, 60, 70, 80, 90};
    public static final double[] SKY_MAX_SHIFT = {0, 0.77, 1.53, 2.32, 3.14, 4.35, 6.50, 9.50, 11.5, 12.0};
    /**
     * Width in minutes of each civil/nautical/astronomical twilight band.
     */
    private static final double SKY_BAND_MIN = 20.0;
    /**
     * Usable character width of skyBarBox - measured after layout, like overlayChars.
     */
    public static int skyBarChars = 48; // 2-digit day + "|" + 48 slots + "|", until measured
    /**
     * cached values - recalculate only when date changes
     */
    public static String lastCalcDate = "";
    public static String cachedMoon = "";
    public static String[] cachedSun = new String[6];
    /**
     * Hardcoded Kilauea center coordinates for sky calculations, map re-centering.
     */
    public static double SKY_LAT = 19.411411;
    public static double SKY_LON = -155.269269;

    /**
     * say() convenience debug method because LOGCAT fails most
     * of the time on Android Studio Bumblebee.
     */
    private static void say(String something) {
            LocationService.say(something, "skyBar.");
    }

    /**
     * SKYBAR DESIGN CONTRACT
     *
     *
     * SkyBar is a photography decision aid.
     *
     * SkyBar is NOT an astronomy application.
     *
     * SkyBar is NOT a scientific instrument.
     *
     * SkyBar is NOT an ephemeris.
     *
     * SkyBar is NOT intended to accurately describe the sky.
     *
     * SkyBar exists to help a photographer answer questions such as:
     *
     * - Should I start gathering equipment?
     * - Is there likely to be a useful dark-sky opportunity tonight?
     * - Should I stay a little longer after sunset?
     * - Should I stay a little longer before leaving at sunrise?
     * - Is the moon likely to interfere?
     *
     * The output is a compact reminder bar.
     *
     * The output is guidance.
     *
     * The output is NOT a prediction suitable for scientific use.
     *
     *
     *
     * OPERATING ASSUMPTIONS
     *
     *
     * SkyBar is intended for ordinary populated Earth locations.
     *
     * SkyBar is NOT intended for:
     *
     * - Polar regions
     * - Antarctica
     * - Arctic expeditions
     * - Submarines beneath polar ice
     * - The International Space Station
     * - Other unusual environments
     *
     * Sunrise is assumed to occur during the morning.
     *
     * Sunset is assumed to occur during the evening.
     *
     * Code below intentionally relies on those assumptions.
     *
     * Unsupported operating environments are not a design target.
     *
     *
     *
     * DISPLAY MODEL
     *
     *
     * The display covers:
     *
     * Noon HST -> Noon HST
     *
     * The display is intentionally quantized.
     *
     * Typical resolutions are:
     *
     * 6 slots
     * 12 slots
     * 24 slots
     * 48 slots
     * 72 slots
     *
     * At the highest resolution each slot is approximately 20 minutes.
     *
     * The display itself is therefore low precision.
     *
     * Calculations only need sufficient fidelity to support the displayed slots.
     *
     * Accuracy beyond display resolution is wasted complexity.
     *
     *
     *
     * EXTREMELY IMPORTANT
     *
     *
     * MORE ACCURATE IS NOT BETTER.
     *
     * MORE COMPLEX IS NOT BETTER.
     *
     * MORE ASTRONOMICALLY CORRECT IS NOT BETTER.
     *
     * Future maintainers and future AI tools are strongly warned:
     *
     * Do NOT replace coarse approximations merely because a more accurate
     * astronomical calculation exists.
     *
     * Do NOT replace simple logic merely because a more mathematically precise
     * solution exists.
     *
     * Do NOT import astronomy libraries merely because they are available.
     *
     * Do NOT introduce solar geometry merely because it can be done.
     *
     * Do NOT introduce lunar geometry merely because it can be done.
     *
     * Do NOT introduce ephemeris-grade calculations.
     *
     * Accuracy improvements are defects.
     *
     * Complexity increases are defects.
     *
     *
     *
     * TESTING CONTRACT
     *
     *
     * Portions of the project intentionally retain more mathematical reference
     * calculations for comparison purposes.
     *
     * This is deliberate.
     *
     * The existence of a more mathematical implementation elsewhere in the project
     * does NOT imply SkyBar should become more accurate.
     *
     * SkyBar and mathematical reference calculations serve different purposes.
     *
     *
     *
     * VISUAL LANGUAGE
     *
     *
     * SkyBar communicates photographer-relevant conditions.
     *
     * Symbols represent guidance.
     *
     * Symbols do NOT attempt to provide a complete astronomical description.
     *
     * The exact glyph meanings may evolve, but the overall purpose remains:
     *
     * Help the photographer decide when to start.
     *
     *
     *
     * RENDERING MODEL
     *
     *
     * SkyBar is intentionally rendered in multiple passes.
     *
     * Readability is more important than reducing the number of passes.
     *
     * Readability is more important than reducing the number of loops.
     *
     * Readability is more important than micro-optimizing a 72-character array.
     *
     * Additional passes are acceptable when they improve clarity.
     *
     * Example pass sequence:
     *
     * Pass 1 - Establish baseline day/night state.
     * Pass 2 - Paint sunset twilight markers.
     * Pass 3 - Paint sunrise twilight markers.
     * Pass 4 - Apply moon effects.
     * Pass 5 - Mark optimal viewing window.
     * Pass 6 - Convert char[] to String.
     *
     * Future passes are acceptable.
     *
     * Examples:
     *
     * - Meteor shower emphasis
     * - Comets
     * - Planetary events
     * - Other photographer guidance
     *
     *
     *
     * PASS ORDER IS INTENTIONAL
     *
     *
     * Later passes may overwrite earlier passes.
     *
     * This is deliberate.
     *
     * Overwrite precedence is part of the design.
     *
     * Example:
     *
     * Twilight may overwrite baseline state.
     *
     * Moon effects may overwrite twilight.
     *
     * Optimal viewing markers may overwrite moon or twilight markers.
     *
     * The rendering order defines visual priority.
     *
     * Do NOT merge passes merely to reduce line count.
     *
     * Do NOT merge passes merely to reduce loop count.
     *
     *
     *
     * OPTIMAL VIEWING WINDOW
     *
     *
     * The optimal viewing window is the focus of this entire subsystem.
     *
     * Favorable viewing markers take precedence over decorative detail.
     *
     * The favorable '*' region is never shortened by rendering passes.
     *
     * Guidance ramps are added outside the favorable region.
     *
     * Example:
     *
     * for ****
     *
     * /****\
     *
     * NOT:
     *
     * /**\
     *
     * The asterixes remain visible.
     *
     * The ramps provide additional preparation and wrap-up guidance.
     *
     *
     *
     * DOMAIN ASSUMPTIONS
     *
     *
     * For supported operating regions:
     *
     * Sunrise occurs in the sunrise bucket.
     *
     * Sunset occurs in the sunset bucket.
     *
     * Favorable viewing can only occur during nighttime buckets.
     *
     * These assumptions are part of the design.
     *
     * The code intentionally relies on them.
     *
     * Defensive checks for impossible states may be omitted when they obscure
     * the intent of the rendering logic.
     *
     *
     *
     * FINAL REMINDER
     *
     *
     * If a future modification makes SkyBar resemble professional astronomy
     * software, the modification has almost certainly moved the implementation
     * away from its intended purpose.
     *
     * SkyBar is a photographer reminder bar.
     *
     * Keep it simple.
     *
     * Keep it readable.
     *
     * Keep it useful.
     *
     * Do not turn it into an observatory.
     */
    private static void recalcDailyIfNeeded(String t, double lat, double lon) {
        String date = t.substring(0, 10); // "yyyy-MM-dd"
        if (date.equals(lastCalcDate)) return;
        lastCalcDate = date;
        cachedMoon = calcMoonPhaseString(date);
        cachedSun = calcSunTimes(date, lat, lon);
    }

    public static String getMoonPhase(String t, double lat, double lon) {
        recalcDailyIfNeeded(t, lat, lon);
        return cachedMoon;
    }

    public static String[] getSunTimes(String t, double lat, double lon) {
        recalcDailyIfNeeded(t, lat, lon);
        return cachedSun;
    }

    /**
     * Returns moon phase
     * Reference new moon: 2000-01-06 - a known NM date, no time needed.
     * Cycle = 29.53059 days.
     */
    private static long calcMoonPhase(String date) {
        // parse date digits directly
        int y = Integer.parseInt(date.substring(0, 4));
        int m = Integer.parseInt(date.substring(5, 7));
        int d = Integer.parseInt(date.substring(8, 10));

        // days since reference new moon 2000-01-06 using integer day arithmetic
        // Julian Day Number - no time component needed, day resolution is fine
        double jd = julianDay(y, m, d);
        double jd0 = julianDay(2000, 1, 6); // reference New Moon
        double age = ((jd - jd0) % 29.53059 ) ; // 0..29.53
        //say("calcMoonPhase: jd: " + jd/1.0 + " jd2000: " + jd0/1.0 + " age: " + age/1.0);
        return (long) age;
    }

    /**
     * Returns moon phase label and days to next FM or NM.
     * e.g. "WG FM in 4d"
     * Reference new moon: 2000-01-06 - a known NM date, no time needed.
     * Cycle = 29.53059 days.
     */
    private static String calcMoonPhaseString(String date) {
        // parse date digits directly
        int y = Integer.parseInt(date.substring(0, 4));
        int m = Integer.parseInt(date.substring(5, 7));
        int d = Integer.parseInt(date.substring(8, 10));

        // days since reference new moon 2000-01-06 using integer day arithmetic
        // Julian Day Number - no time component needed, day resolution is fine
        long jd = julianDay(y, m, d);
        long jd0 = julianDay(2000, 1, 6); // reference New Moon
        double age = ((jd - jd0) % 29.53059 + 29.53059) % 29.53059; // 0..29.53

        // 8 named phases, 28-step label array
        String phase;
        double daysToFM;
        double daysToNM;
        if      (age <  1.85) { phase = "NM"; }
        else if (age <  7.38) { phase = "WC"; }
        else if (age < 11.07) { phase = "FQ"; }
        else if (age < 14.77) { phase = "WG"; }
        else if (age < 16.61) { phase = "FM"; }
        else if (age < 22.15) { phase = "WG"; } // waning gibbous
        else if (age < 25.84) { phase = "LQ"; }
        else                  { phase = "WC"; } // waning crescent

        // days to next FM and NM
        daysToFM = (14.765 - age + 29.53059) % 29.53059;
        daysToNM = (29.53059 - age) % 29.53059;

        if (phase.equals("FM")) return "FM NM in " + (int) Math.ceil(daysToNM) + "d";
        if (phase.equals("NM")) return "NM FM in " + (int) Math.ceil(daysToFM) + "d";

        // approaching FM or NM - show whichever is closer
        if (daysToFM <= daysToNM) {
            return phase + " FM in " + (int) Math.ceil(daysToFM) + "d";
        } else {
            return phase + " NM in " + (int) Math.ceil(daysToNM) + "d";
        }
    }

    /**
     * Integer Julian Day Number from calendar date.
     * No time component - day resolution only.
     * Standard formula, no library needed.
     */
    private static long julianDay(int y, int m, int d) {
        int a = (14 - m) / 12;
        int yy = y + 4800 - a;
        int mm = m + 12 * a - 3;
        return d + (153 * mm + 2) / 5 + 365L * yy + yy / 4 - yy / 100 + yy / 400 - 32045;
    }

    /**
     * Returns [astroRise, nautRise, civilRise, civilSet, nautSet, astroSet]
     * as "HH:mm" strings in HST.
     *
     * Solar noon is calculated from longitude only - no TimeZone object,
     * no UTC conversion.  HST = UTC-10, so solar noon in HST minutes from
     * midnight = 720 - 4*lon - eot + 600  (the +600 converts UTC noon to HST).
     * lon is negative for west, e.g. -155.29 for Kilauea.
     *
     * Depression angles: astronomical=18, nautical=12, civil=6 degrees.
     */
    @Deprecated
    public static String[] calcSunTimes(String date, double lat, double lon) {
        int y = Integer.parseInt(date.substring(0, 4));
        int m = Integer.parseInt(date.substring(5, 7));
        int d = Integer.parseInt(date.substring(8, 10));

        // day of year
        int doy = dayOfYear(y, m, d);

        // solar declination (degrees)
        double decl = 23.45 * Math.sin(Math.toRadians(360.0 / 365.0 * (doy - 81)));

        // equation of time (minutes) - Spencer formula
        double b = Math.toRadians(360.0 / 365.0 * (doy - 81));
        double eot = 9.87 * Math.sin(2 * b) - 7.53 * Math.cos(b) - 1.5 * Math.sin(b);

        // solar noon in minutes from HST midnight
        // UTC solar noon = 720 - 4*lon - eot  (lon negative for west)
        // HST = UTC - 600 minutes
        double solarNoonHST = 720.0 - 4.0 * lon - eot - 600.0;

        double[] depressions = {18.0, 12.0, 6.0};
        String[] result = new String[6];

        for (int i = 0; i < 3; i++) {
            double cosH =
                    (Math.cos(Math.toRadians(90.0 + depressions[i]))
                            - Math.sin(Math.toRadians(lat)) * Math.sin(Math.toRadians(decl)))
                            / (Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(decl)));

            if (cosH < -1.0 || cosH > 1.0) {
                result[i] = "--:--";
                result[5 - i] = "--:--";
                continue;
            }

            double hMin = Math.toDegrees(Math.acos(cosH)) * 4.0; // minutes
            double riseMin = solarNoonHST - hMin;
            double setMin = solarNoonHST + hMin;

            result[i] = minsToHHMM(riseMin);
            result[5 - i] = minsToHHMM(setMin);
        }
        return result;
    }

    /** Converts minutes-from-midnight to "HH:mm" string. Wraps at 0 and 1440. */
    private static String minsToHHMM(double mins) {
        int total = (int) Math.round(mins) % 1440;
        if (total < 0) total += 1440;
        return String.format(java.util.Locale.US, "%02d:%02d",
                total / 60, total % 60);
    }

    /** Day of year, 1-based. Accounts for leap years. */
    public static int dayOfYear(int y, int m, int d) {
        int[] dim = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) dim[1] = 29;
        int doy = d;
        for (int i = 0; i < m - 1; i++) doy += dim[i];
        return doy;
    }

    /**
     * Returns one of 28 moon phase characters based on age of moon at fixTime.
     * Cycle length 29.53 days.  Index 0 = new moon, 14 = full moon.
     * Uses Unicode 0x1F311-0x1F318 cycled - falls back to ASCII label if
     * rendering is a concern.
     *
     * NOTE: returns a plain ASCII abbreviation for now pending font confirmation.
     * Replace chars[] entries with Unicode moon emoji if device renders them.
     */
    private static String calcMoonPhase(long fixTime) {
        // known new moon reference: 2000-01-06 18:14 UTC = 947182440000L ms
        final long NEW_MOON_REF_MS = 947182440000L;
        final double CYCLE_MS = 29.530588 * 24 * 60 * 60 * 1000.0;
        double age = ((fixTime - NEW_MOON_REF_MS) % CYCLE_MS + CYCLE_MS) % CYCLE_MS;
        // 28 steps
        String[] chars = {
                "NM", "WC1", "WC2", "WC3", "WC4", "WC5", "WC6",
                "FQ", "WG1", "WG2", "WG3", "WG4", "WG5", "WG6",
                "FM", "WG7", "WG8", "WG9", "WGA", "WGB", "WGC",
                "LQ", "WC7", "WC8", "WC9", "WCA", "WCB", "WCC"
        };
        int idx = (int) (age / CYCLE_MS * 28) % 28;
        return chars[idx];
    }

    /**
     * Returns [astroRise, nautRise, civilRise, civilSet, nautSet, astroSet]
     * as HH:mm strings in HST for the date of fixTime at the given lat/lon.
     * Uses simple declination/hour-angle calculation - accuracy within 1-2 min.
     */
    private static String[] calcSunTimes(double lat, double lon, long fixTime) {
        // depression angles in degrees: astronomical=18, nautical=12, civil=6
        double[] depressions = {18.0, 12.0, 6.0};
        String[] result = new String[6];

        java.util.Calendar cal = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Pacific/Honolulu"));
        cal.setTimeInMillis(fixTime);
        int doy = cal.get(java.util.Calendar.DAY_OF_YEAR);
        int year = cal.get(java.util.Calendar.YEAR);

        // solar declination (degrees)
        double decl = 23.45 * Math.sin(Math.toRadians(360.0 / 365.0 * (doy - 81)));

        // equation of time approximation (minutes)
        double b = Math.toRadians(360.0 / 365.0 * (doy - 81));
        double eot = 9.87 * Math.sin(2 * b) - 7.53 * Math.cos(b) - 1.5 * Math.sin(b);

        // solar noon in HST minutes from midnight
        // HST = UTC-10, lon correction: 4 min per degree
        double solarNoonMin = 720 - 4 * lon - eot - (-10 * 60);

        java.text.SimpleDateFormat hhmm =
                new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        hhmm.setTimeZone(java.util.TimeZone.getTimeZone("Pacific/Honolulu"));

        for (int i = 0; i < 3; i++) {
            double cosH = (Math.cos(Math.toRadians(90.0 + depressions[i]))
                    - Math.sin(Math.toRadians(lat)) * Math.sin(Math.toRadians(decl)))
                    / (Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(decl)));
            if (cosH < -1 || cosH > 1) {
                // sun never rises/sets at this depression - polar condition
                result[i] = "--:--";
                result[5 - i] = "--:--";
                continue;
            }
            double hDeg = Math.toDegrees(Math.acos(cosH));
            double riseMin = solarNoonMin - hDeg * 4;
            double setMin = solarNoonMin + hDeg * 4;
            java.util.Date riseDate = new java.util.Date(
                    cal.getTimeInMillis()
                            - (cal.get(java.util.Calendar.HOUR_OF_DAY) * 3600000L
                            + cal.get(java.util.Calendar.MINUTE) * 60000L
                            + cal.get(java.util.Calendar.SECOND) * 1000L)
                            + (long) (riseMin * 60000));
            java.util.Date setDate = new java.util.Date(
                    cal.getTimeInMillis()
                            - (cal.get(java.util.Calendar.HOUR_OF_DAY) * 3600000L
                            + cal.get(java.util.Calendar.MINUTE) * 60000L
                            + cal.get(java.util.Calendar.SECOND) * 1000L)
                            + (long) (setMin * 60000));
            result[i] = hhmm.format(riseDate);
            result[5 - i] = hhmm.format(setDate);
        }
        return result;
    }

    /**
     * Returns solar altitude in degrees for a given fractional Julian Day (UT)
     * at the given coordinates.  Accurate to ~1 degree - sufficient for
     * twilight zone boundaries.
     *
     * @param jd  fractional Julian Day in UT.
     * @param lat latitude in decimal degrees.
     * @param lon longitude in decimal degrees.
     * @return solar altitude in degrees, negative below horizon.
     */
    @Deprecated
    private static double calcSunAltitude(double jd, double lat, double lon) {
        double n = jd - 2451545.0;
        double L = (280.460 + 0.9856474 * n) % 360.0;
        double g = Math.toRadians((357.528 + 0.9856003 * n) % 360.0);
        double lam = Math.toRadians(L + 1.915 * Math.sin(g)
                + 0.020 * Math.sin(2 * g));
        double eps = Math.toRadians(23.439 - 0.0000004 * n);
        double sinDec = Math.sin(eps) * Math.sin(lam);
        double decl = Math.asin(sinDec);
        double ra = Math.atan2(Math.cos(eps) * Math.sin(lam), Math.cos(lam));
        double gmst = (280.46061837 + 360.98564736629 * n) % 360.0;
        double ha = Math.toRadians(gmst + lon - Math.toDegrees(ra));
        double sinAlt = Math.sin(Math.toRadians(lat)) * Math.sin(decl)
                + Math.cos(Math.toRadians(lat)) * Math.cos(decl) * Math.cos(ha);
        return Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, sinAlt))));
    }

    /**
     * Returns lunar altitude in degrees for a given fractional Julian Day (UT)
     * at the given coordinates.  Low-precision (~1-2 degrees), sufficient for
     * above/below horizon and 30-degree elevation checks.
     *
     * @param jd  fractional Julian Day in UT.
     * @param lat latitude in decimal degrees.
     * @param lon longitude in decimal degrees.
     * @return lunar altitude in degrees, negative below horizon.
     */
    @Deprecated
    private static double calcMoonAltitude(double jd, double lat, double lon) {
        double n = jd - 2451545.0;
        double Lm = (218.316 + 13.176396 * n) % 360.0;
        double Mm = Math.toRadians((134.963 + 13.064993 * n) % 360.0);
        double Fm = Math.toRadians((93.272 + 13.229350 * n) % 360.0);
        double lam = Math.toRadians(Lm
                + 6.289 * Math.sin(Mm)
                - 1.274 * Math.sin(2 * Math.toRadians(Lm) - Mm)
                + 0.658 * Math.sin(2 * Math.toRadians(Lm)));
        double beta = Math.toRadians(5.128 * Math.sin(Fm));
        double eps = Math.toRadians(23.439 - 0.0000004 * n);
        double sinDec = Math.sin(eps) * Math.sin(lam) * Math.cos(beta)
                + Math.cos(eps) * Math.sin(beta);
        double decl = Math.asin(Math.max(-1.0, Math.min(1.0, sinDec)));
        double ra = Math.atan2(
                Math.sin(lam) * Math.cos(eps) * Math.cos(beta)
                        - Math.sin(beta) * Math.sin(eps),
                Math.cos(lam) * Math.cos(beta));
        double gmst = (280.46061837 + 360.98564736629 * n) % 360.0;
        double ha = Math.toRadians(gmst + lon - Math.toDegrees(ra));
        double sinAlt = Math.sin(Math.toRadians(lat)) * Math.sin(decl)
                + Math.cos(Math.toRadians(lat)) * Math.cos(decl) * Math.cos(ha);
        return Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, sinAlt))));
    }

    /**
     * Returns lunar illumination fraction 0.0-1.0 for a given Julian Day.
     * 0.0 = new moon, 1.0 = full moon.
     *
     * @param jd fractional Julian Day in UT.
     * @return illumination fraction 0.0 to 1.0.
     */
    @Deprecated
    private static double calcMoonIllumination(double jd) {
        double n = jd - 2451545.0;
        double Lm = Math.toRadians((218.316 + 13.176396 * n) % 360.0);
        double Ls = Math.toRadians((280.460 + 0.9856474 * n) % 360.0);
        double Mm = Math.toRadians((134.963 + 13.064993 * n) % 360.0);
        double Ms = Math.toRadians((357.528 + 0.9856003 * n) % 360.0);
        double elong = Math.acos(Math.max(-1.0, Math.min(1.0,
                Math.sin(Ls + 1.915 * Math.sin(Ms)) * Math.sin(Lm + 6.289 * Math.sin(Mm))
                        + Math.cos(Ls + 1.915 * Math.sin(Ms)) * Math.cos(Lm + 6.289 * Math.sin(Mm)))));
        return (1.0 - Math.cos(elong)) / 2.0;
    }

    /** Interpolated max day-length shift (hours) from the lookup table, clamped past 90. */
    public static double maxShiftForLat(double latitude) {
        double absLat = Math.abs(latitude);
        if (absLat >= 90) return SKY_MAX_SHIFT[SKY_MAX_SHIFT.length - 1];
        int i = 0;
        while (SKY_LAT_TABLE[i + 1] < absLat) i++;
        double frac = (absLat - SKY_LAT_TABLE[i]) / (SKY_LAT_TABLE[i + 1] - SKY_LAT_TABLE[i]);
        return SKY_MAX_SHIFT[i] + frac * (SKY_MAX_SHIFT[i + 1] - SKY_MAX_SHIFT[i]);
    }

    /**
     * Interpolated sunrise/sunset, in minutes from midnight, via simple seasonal
     * interpolation - no expensive unnecessary solar-position math.  Good to roughly
     * twenty minutes, which is the target accuracy for this whole display.
     *
     * @return double[]{sunriseMin, sunsetMin}
     */
    public static double[] sunriseSunsetMinutes(double latitude, int dayOfYear) {
        double maxShift = maxShiftForLat(latitude);
        int winterSolstice = latitude >= 0 ? 355 : 172;
        int daysSinceWinter = (dayOfYear - winterSolstice + 365) % 365;
        double seasonalFactor = daysSinceWinter <= 182
                ? daysSinceWinter / 182.0
                : (365 - daysSinceWinter) / 183.0;
        double shift = maxShift * seasonalFactor;
        double dayLength = 12.0 - maxShift + shift * 2.0;
        double sunriseHr = 12.0 - dayLength / 2.0;
        double sunsetHr = 12.0 + dayLength / 2.0;
        return new double[]{sunriseHr * 60.0, sunsetHr * 60.0};
    }

    /**
     * Picks how many sky-bar slots fit in the measured character width.
     * Only two candidates are real options - 72 slots (20 min each) or
     * 48 slots (30 min each).  32 and 24 were tried previously and didn't
     * read well, so they're not in the running.  Falls back to 48 if even
     * that doesn't fit.
     */
    public static int pickSkySlots(int availableChars) {
        int overhead = 4; // 2-digit day + 2 pipes
        if (availableChars >= (72 + overhead)) return 72;
        //say("pickSkySlots avail: " + availableChars + " returning 48");
        return 48;
    }

    /** Minutes per slot for a given skySlots day (1440 minutes / slots). */
    public static int slotWidthMin(int skySlots) {
        return 1440 / skySlots;
    }

    /**
     * Builds the header line to match whatever skySlots/slotWidth was chosen.
     * Hour labels in 24hr clock, starting at 12 (noon).  Normally one label
     * per hour, but if that leaves no room for a separating space (true at
     * 48 slots/30 min, where an hour is only 2 characters wide - exactly
     * the label's own length), the label interval widens to 2 hours so each
     * label still gets at least one space of breathing room.  3 leading
     * spaces align the labels above the bar body, which starts after "DD|".
     */
    private static String buildSkyHeader(int skySlots, int slotWidthMin) {
        int slotsPerHour = Math.max(1, 60 / slotWidthMin);
        int hoursPerLabel = 1;
        while (slotsPerHour * hoursPerLabel < 3) hoursPerLabel++; // need room for "NN" + >=1 space
        int slotsPerLabel = slotsPerHour * hoursPerLabel;

        StringBuilder header = new StringBuilder("   ");
        int hour = 12;
        int pos = 0;
        while (pos < skySlots) {
            String label = String.format(java.util.Locale.US, "%02d", hour % 24);
            header.append(label);
            int gap = slotsPerLabel - label.length();
            for (int i = 0; i < gap; i++) header.append(' ');
            hour += hoursPerLabel;
            pos += slotsPerLabel;
        }
        return header.toString();
    }

    /**
     * Builds the sky timeline string for the given date, sized dynamically
     * to fit the measured width of skyBarBox (72 slots at 20 min, or 48
     * slots at 30 min, or most important, 24 slots at 60 min.)
     *
     * Timeline runs noon HST to noon HST the next day.
     *
     * Code simplicity outweight celestial accuracy.  Julian day nonsense
     * has cause many bugs to be introduced.  Calculating this 10 separate
     * lines 60 times per second, we CANNOT affor any heavyweight math
     * library calls here.  The estimates herein actually ARE accurate to
     * within the 20 minute minimum accuracy.
     *
     * Going for microsecond accuracy isn't just mental masturbation that
     * adds bugs to the code, it also adds calculation weight here that
     * cannot be afforded.  Especially when I am 450 yards away from a
     * lava fountain suddenly worrying about my phone overheating because
     * some AI decided to violate the coding standards here, completely
     * ignore the requirements and design contracts, instead reasserting
     * the AI stupidity of looking smart by being impossibly stupid.
     *
     * Real lookup-table sunrise/sunset, then civil/nautical/astronomical
     * bands approximated as flat 20-minute steps outward from those
     * times - the math was done outside and does not need to be redone.
     *
     * Moon layer is correctly calculated from moon phase.  That's all.
     * Any additional calculations are additional "woke" AI attempts to
     * sabotage this, because I am a "breeder."
     *
     *
     * Character key (priority order, dark to light):
     * .  full daylight, moon not notable
     * ,  daylight, moon above horizon but below 30 deg
     * '  daylight, moon high above horizon
     * n  dark, moon above horizon, illumination <50pct
     * N  dark, moon high above horizon, illumination <50pct
     * m  dark, moon above horizon, illumination >50pct
     * M  dark, moon high above horizon, illumination >50pct
     * _  near civil twilight ( ~1 deg)
     * -  civil twilight ( ~6 deg)
     * =  nautical twilight ( ~12 deg)
     * /  astronomical twilight ( ~18 deg)
     * \  astronomical twilight ( ~18 deg)
     * *  astronomical dark, moon below horizon (Milky Way viable)
     *
     * @param date date string yyyy-MM-dd in HST.
     * @return sky bar string, length = pickSkySlots(skyBarChars).
     */
    private static String calcSkyBar(String date) {

        int y = Integer.parseInt(date.substring(0, 4));
        int mo = Integer.parseInt(date.substring(5, 7));
        int d = Integer.parseInt(date.substring(8, 10));

        //moved here from onCreate, perhaps setSkyBarChars()
        if (MainActivity.skyBarBox.getPaint() != null && MainActivity.skyBarBox.getWidth() > 0) {
            float skyCharW = MainActivity.skyBarBox.getPaint().measureText("M");
            if (skyCharW > 0)
                skyBarChars = (int) ((MainActivity.skyBarBox.getWidth() - 60) / skyCharW);
        }
        //say("Hansel.calcSkyBar.skyBarChars: " + skyBarChars + " width: " + MainActivity.skyBarBox.getWidth());

        int skySlots = pickSkySlots(skyBarChars);
        int slotMin = slotWidthMin(skySlots);
        //say("calcSkyBar skySlots: " + skySlots + " slotMin: " + slotMin);

        int dayOfYear = dayOfYear(y, mo, d);

        double phase = calcMoonPhase(date);
        double dayPhase = (phase - 20 + 29) % 29;
        //say("calcSkyBar phase: " + phase + " dayPhase: " + dayPhase);

        double[] rs = sunriseSunsetMinutes(SKY_LAT, dayOfYear);
        double sunrise = rs[0];
        double sunset = rs[1];
        //say("calcSkyBar sunrise: " + (int) sunrise + " sunset: " + (int) sunset);

        double moonrise = ( (24 * dayPhase) / 29 ) % 24;
        double moonset = (moonrise + 12) % 24;
        //say("calcSkyBar moonrise: " + (int) moonrise + " moonset: " + (int) moonset);
        moonrise = moonrise * 60; //minutes
        moonset = moonset * 60;
        //say("calcSkyBar moonrise: " + (int) moonrise + " moonset: " + (int) moonset);

        char[] sky = new char[skySlots];
        int firstStar = -1;
        int lastStar = -1;

        /**
         * Pass 1.  Paint baseline sky state.
         * This intentionally uses the coarse classifySun() model.
         *
         * DO NOT replace with solar geometry.
         * DO NOT add astronomy libraries.
         * DO NOT increase precision.
         */
        for (int slot = 0; slot < skySlots; slot++) {
            double t = (12 * 60) + (slot * slotMin);
            sky[slot] = classifySun(t, sunrise, sunset);
        }

        /**  Pass 2. Sunset marker.  Sunset always occurs in the evening bucket.
         * If that assumption fails, the location is outside the acceptable operating area. */
        int sunsetSlot = (int) ( (sunset - 720) / slotMin);
        sky[sunsetSlot - 1] = '_';
        sky[sunsetSlot + 0] = '-';
        sky[sunsetSlot + 1] = '=';

        /**  Pass 3.  Sunrise marker. */
        int sunriseSlot = (int) ( (sunrise + 720.0) / slotMin);
        sky[sunriseSlot - 1] = '=';
        sky[sunriseSlot + 0] = '-';
        sky[sunriseSlot + 1] = '_';

        /** Pass 4.  Moon layer.  Moon may overwrite twilight.
         *  Photographer usefulness matters more than astronomical correctness. */
        for (int slot = 0; slot < skySlots; slot++) {
            double t = (12 * 60) + (slot * slotMin);
            if (!moonVisibleAt(t, moonrise, moonset))
                continue;

            boolean brightMoon = ((dayPhase >= 9.0) && (dayPhase <= 21.0));
            //say("skyBar slot: " + slot );
            boolean horizon = moonNearHorizon(t/slotMin, moonrise / slotMin, moonset / slotMin);
            char moonChar;

            if (brightMoon) {
                moonChar = horizon ? 'm' : 'M';
            } else {
                moonChar = horizon ? 'n' : 'N';
            }

            if (sky[slot] == '*') {
                sky[slot] = moonChar;
            } else if (sky[slot] == '.') {
                sky[slot] = horizon ? ',' : '\'';
            } else if ( (sky[slot] == '=') || (sky[slot] == '-') || (sky[slot] == '_') )
                sky[slot] = moonChar;
        }

        /** Pass 5.  Photographer viewing window. There are zero to one "optimal"
         * segments.  Stars remain primary information.  Brackets may overwrite anything.  */
        for  (int slot = 1; slot < skySlots; slot++) {
            if (sky[slot] == '*') {
                lastStar = slot;
                if (firstStar < 0) firstStar = slot;
            }
        }
        if (firstStar >= 0) {
            sky[firstStar - 1] = '/';
            sky[lastStar + 1] = '\\';
        }

        /**  Pass 6.  Render. */
        //say("hansel calcSkyBar date:" + date + " sky: " + new String(sky));
        return new String(sky);
    }


    /** True when near horizon */
    private static boolean moonNearHorizon(double t, double moonrise, double moonset) {
        //say("moonNearHorizon t: " + t/1.0 + " mrise: " + moonrise/1.0 + " mset: " + moonset/1.0);
        return ( ( Math.abs( ((t%1440)-moonrise)) < 4 ) || ( (Math.abs((t%1440)-moonset)) < 4 ) );
    }

    /** Is moon above horizon?  We know for certain because of lon & phase */
    private static boolean moonVisibleAt(double t, double moonrise, double moonset) {
        // for moonset before midnight
        if (moonset > moonrise) return (((t % 1440) > moonrise) && ((t % 1440) < moonset));
        // for moonset after midnight
        return (((t % 1440) < moonset) || ((t % 1440) > moonrise));
    }

    /** return a char indicating sunshine or darkness */
    private static char classifySun(double t, double sunrise, double sunset) {
        //say( "classifySun: t=" + t + " sunrise: "+ sunrise + " sunset: " + sunset);
        if (((t % 1440) >= sunset) || ((t % 1440) < sunrise)) return '*';
        return '.';
    }

    /**
     * Updates the sky bar overlay for the given date - single line, always
     * just today's bar.  No history accumulation in replay mode; an earlier
     * version tried scrolling up previous days during replay, but that
     * covered map information the bar should never obscure, so it's gone.
     *
     * Called from handleLocation() (live mode) and handleReplayPoint() (replay).
     *
     * @param date date string yyyy-MM-dd HST.
     */
    public static void updateSkyOverlay(String date) {
        if (MainActivity.skyBarBox == null) return;
        String bar = calcSkyBar(date);
        String day = date.substring(8, 10);
        int skySlots = pickSkySlots(skyBarChars);
        String header = buildSkyHeader(skySlots, slotWidthMin(skySlots));
        final String skyText = header + "\n" + day + "|" + bar + "|";
        MainActivity.skyBarBox.post(() -> MainActivity.skyBarBox.setText(skyText));

    }

}
