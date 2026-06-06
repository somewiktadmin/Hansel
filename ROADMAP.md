# Hansel / Gretel - Project Roadmap

Hansel is a GPS breadcrumb logger for field research at Kilauea volcano,
Big Island, Hawaii.  HST timezone.

## v0.986 - COMPLETED
- OSMDroid baseline: MAPNIK tiles, INVERT_COLORS, 60/40 layout
- Map controls: [ME], [VH removed], [HMM], [+], [-]
- Status overlay: lat/lon/alt/spd/crs/zoom/moon/sunrise/sunset
- Replay overlay: Polyline segments, gap dots, 2500 point cap
- OSMDroid tile caching: confirmed working, internal storage
- MANAGE_ALL_FILES: startActivityForResult fix, initOsmdroid()

## v0.99 - NEXT
- SAF migration: replace all direct File I/O in LocationService
  and WebAppInterface with DocumentFile/ContentResolver
- Remove godmode (MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
- BootReceiver: auto-resume LocationService after reboot
- consolidateOldFiles() backup move
- import external files (with long explanation)
- Supplemental tile cache: IFilesystemCache implementation
  flat SAF directory, .z{z}.{x}.{y}.png naming, 365-day expiry
- Elevation cache: USGS DEM + collected Hansel points,
  prefer own data over USGS

## v1.0 - Headless logger
- Hansel becomes headless, no UI
- Remove MainActivity WebView UI entirely
- BootReceiver owns auto-start

## v2.0 - Gretel viewer
- Separate app, full native Android UI
- Reads Hansel NDJSON logs
- Full replay, elevation cache, tile cache UI
- Import files from the OS that SAF gets stubborn about not recognizing,
  like email-saved ndjson files, saved inside the Gretel directory.



## v0.99 - housekeeping before the split
- BootReceiver: auto-resume LocationService after phone reboot & unlock
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
