# Hansel / Gretel - Project Roadmap

Hansel is a GPS breadcrumb logger for field research at Kilauea volcano,
Big Island, Hawaii.  HST timezone throughout.  Always.

## v0.985 - current
- Javadoc pass: MainActivity, LocationService, WebAppInterface,
  NotificationHelper, AndroidManifest, build.gradle, index.html
- scheduleTopOfHourRotation() - clock-driven hourly file rotation,
  replacing accidental GPS-callback-driven rotation
- main() / window.addEventListener("load") fix for WebView canvas
  sizing race condition
- NDJSON file format v0.931: alt_units feet, spd_units MPH in header

## v0.99 - housekeeping before the split
- BootReceiver: auto-resume LocationService after phone reboot
- consolidateOldFiles(): move consumed files to ./backup/ instead
  of deleting them
- consolidateOldFiles(): version existing monthly files before
  overwriting (slots 61-99)
- consolidateOldFiles(): convert from SAF to direct File access
- Sort and exact-line deduplicate on consolidation
- Remove startLoggingDefault() from MainActivity once BootReceiver
  is in place
- Working directory change UI - user can re-pick folder without
  reinstalling

## v1.0 - headless Hansel logger app
- Split LocationService and file I/O into a standalone headless
  background app: com.hansel.logger
- No UI whatsoever
- Fires broadcast intents for position updates and file events
- BootReceiver in the headless app
- NotificationTile on/off
- com.hansel.app becomes the viewer app shell pending Gretel

## v2.0 - Gretel viewer app
- New app: com.gretel.app
- OSMDroid native map replacing WebView canvas
  - 9-tile display, 25-tile fetch buffer
  - LIFO tile fetch queue, backfills on wifi
  - Bundled APK tiles: Z14/Z15/Z16 covering Kilauea caldera + 3mi
  - Offline-first: display only from cache, never incoming tile
  - (c) OpenStreetMap contributors on every tile
- Native Android UI replacing WebView entirely
  - Altitude graph with min/max sliders
  - Sound event buttons with drag-and-drop editor
  - say() scrollbox
  - Mark / panic button
- Listens to Hansel v1.0 broadcast intents
- Replay and Replay 72h
- iPhone port: CoreLocation, same NDJSON file format

## Post-2.0 / research goals
- Audio waveform correlation with sound marks (primary research goal)
- USGS 3DEP reference terrain for altitude sanity check
- Kalman-style outlier rejection for altitude
- sanctify_v1.py: rule-based bad data removal
- last_rites_v1.py: deadband pass, final canonical monthly
- Four output views: everything, GPX, sounds, landmarks
- Mode profiles: Volcano House, hiking, driving
- adb push instead of MTP for file transfers
