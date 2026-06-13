import subprocess
import datetime
import time
import random
import signal
import sys
from zoneinfo import ZoneInfo

# ---------------------------------------------------------------------------
# USAGE
#   python kilauea.py [cam] [mode]
#   cam  : v3 (default) or v1
#   mode : fast (default) or normal
#
# Examples:
#   python kilauea.py              # v3 fast
#   python kilauea.py v1           # v1 fast
#   python kilauea.py v3 normal    # v3 normal
#   python kilauea.py v1 normal    # v1 normal
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# PARSE ARGS
# ---------------------------------------------------------------------------

cam  = sys.argv[1].lower() if len(sys.argv) > 1 else "v3"
mode = sys.argv[2].lower() if len(sys.argv) > 2 else "fast"

if cam not in ("v1", "v3"):
    print(f"ERROR: cam must be v1 or v3, got '{cam}'")
    sys.exit(1)

if mode not in ("normal", "fast"):
    print(f"ERROR: mode must be normal or fast, got '{mode}'")
    sys.exit(1)

# ---------------------------------------------------------------------------
# CONFIG TABLE
#
# Backoff start and ceiling are staggered so four simultaneous instances
# do not all hammer the yt-dlp retry loop in lockstep.
#
# yt-dlp fetch times (seconds before the hour):
#   v3 fast   :59:05   ffmpeg fires :59:45
#   v3 normal :59:20   ffmpeg fires :59:59
#   v1 fast   :59:35   ffmpeg fires :59:45
#   v1 normal :59:40   ffmpeg fires :59:59
# ---------------------------------------------------------------------------

CONFIGS = {
    ("v3", "fast"): {
        "youtube_id"      : "gXKuUyKt8mc",
        "cam_name"        : "v3cam",
        "dir_out"         : "hourly-v3cam-x64",
        "backoff_start"   : 61,
        "backoff_ceiling" : 600,
        "ytdlp_lead_secs" : 55,   # seconds before hour to fetch URL (:59:05)
        "ffmpeg_lead_secs": 15,   # seconds before hour to fire ffmpeg (:59:45)
    },
    ("v3", "normal"): {
        "youtube_id"      : "gXKuUyKt8mc",
        "cam_name"        : "v3cam",
        "dir_out"         : "hourly-v3cam",
        "backoff_start"   : 62,
        "backoff_ceiling" : 700,
        "ytdlp_lead_secs" : 40,   # :59:20
        "ffmpeg_lead_secs": 1,    # :59:59
    },
    ("v1", "fast"): {
        "youtube_id"      : "HggWKlZv9yk",
        "cam_name"        : "v1cam",
        "dir_out"         : "hourly-v1cam-x64",
        "backoff_start"   : 63,
        "backoff_ceiling" : 800,
        "ytdlp_lead_secs" : 25,   # :59:35
        "ffmpeg_lead_secs": 15,   # :59:45
    },
    ("v1", "normal"): {
        "youtube_id"      : "HggWKlZv9yk",
        "cam_name"        : "v1cam",
        "dir_out"         : "hourly-v1cam",
        "backoff_start"   : 64,
        "backoff_ceiling" : 900,
        "ytdlp_lead_secs" : 20,   # :59:40
        "ffmpeg_lead_secs": 1,    # :59:59
    },
}

CFG = CONFIGS[(cam, mode)]

YOUTUBE_URL       = "https://www.youtube.com/watch?v=" + CFG["youtube_id"]
CAM_NAME          = CFG["cam_name"]
DIR_OUT           = CFG["dir_out"]
BACKOFF_START     = CFG["backoff_start"]
BACKOFF_CEILING   = CFG["backoff_ceiling"]
YTDLP_LEAD_SECS   = CFG["ytdlp_lead_secs"]
FFMPEG_LEAD_SECS  = CFG["ffmpeg_lead_secs"]

YTDLP_BIN  = "yt-dlp"
FFMPEG_BIN = "ffmpeg"

# Fast mode settings
TIMELAPSE_FACTOR  = 64
FAST_INPUT_SECS   = 3635   # input window - pushed away from hour boundary
FAST_OUTPUT_SECS  = FAST_INPUT_SECS // TIMELAPSE_FACTOR

# Normal mode settings
NORMAL_SECS       = 3601   # just over 60 minutes - no gap between files

# Lie-low window after segment ends (identical for all modes)
LIELOW_MIN_SECS   = 60
LIELOW_MAX_SECS   = 240

# Watchdog poll interval
WATCHDOG_INTERVAL = 5

# yt-dlp command - format differs by mode
if mode == "normal":
    YTDLP_FORMAT = "best"
else:
    YTDLP_FORMAT = "94"

YTDLP_CMD = [
    YTDLP_BIN,
    "-f", YTDLP_FORMAT,
    "-g",
    "--no-playlist",
    "--retries", "2",
    "--cookies-from-browser", "chrome",
    "--extractor-args", "youtube:player_client=android",
]

# ---------------------------------------------------------------------------
# TIMEZONE
# ---------------------------------------------------------------------------

HST = ZoneInfo("Pacific/Honolulu")

def now_hst():
    return datetime.datetime.now(HST)

# ---------------------------------------------------------------------------
# HELPERS
# ---------------------------------------------------------------------------

def log(msg):
    ts = now_hst().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{ts} HST]  {msg}", flush=True)

def make_dirs():
    import os
    os.makedirs(DIR_OUT, exist_ok=True)

def ts_filename(dt):
    base = dt.strftime("%Y-%m-%d_%H-%M-%S")
    if mode == "fast":
        return f"{base}_{CAM_NAME}_x{TIMELAPSE_FACTOR}.mp4"
    return f"{base}_{CAM_NAME}.mp4"

def seconds_until_next_hour():
    t = now_hst()
    secs_past = t.minute * 60 + t.second + t.microsecond / 1_000_000
    return 3600.0 - secs_past

def seconds_until_55():
    t = now_hst()
    secs_past = t.minute * 60 + t.second + t.microsecond / 1_000_000
    return 3300.0 - secs_past

def wait_until_epoch(target):
    while True:
        remaining = target - time.time()
        if remaining <= 0:
            break
        time.sleep(min(0.05, remaining))

# ---------------------------------------------------------------------------
# yt-dlp URL FETCH (exponential backoff)
# ---------------------------------------------------------------------------

def fetch_stream_url():
    backoff = BACKOFF_START
    attempt = 0
    while True:
        attempt += 1
        try:
            result = subprocess.run(
                YTDLP_CMD + [YOUTUBE_URL],
                capture_output=True, text=True, timeout=30,
            )
            if result.returncode != 0:
                log(f"yt-dlp attempt {attempt} rc={result.returncode}: "
                    f"{result.stderr.strip()[:200]}")
            else:
                url = result.stdout.strip().split("\n")[-1]
                if url.startswith("http"):
                    log(f"yt-dlp URL acquired (attempt {attempt})")
                    return url
                else:
                    log(f"yt-dlp attempt {attempt}: empty or bad URL output")
        except subprocess.TimeoutExpired:
            log(f"yt-dlp attempt {attempt}: timeout")
        except Exception as exc:
            log(f"yt-dlp attempt {attempt}: {exc}")
        log(f"Backoff {backoff}s before retry...")
        time.sleep(backoff)
        backoff = min(backoff * 2, BACKOFF_CEILING)

# ---------------------------------------------------------------------------
# FFMPEG LAUNCH
# ---------------------------------------------------------------------------

def start_ffmpeg(stream_url, outfile):
    if mode == "fast":
        cmd = [
            FFMPEG_BIN,
            "-loglevel", "warning",
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_delay_max", "30",
            "-i", stream_url,
            "-t", str(FAST_OUTPUT_SECS),
            "-vf", f"setpts=PTS/{TIMELAPSE_FACTOR}",
            "-r", "30",
            "-an",
            "-movflags", "+faststart",
            "-y",
            outfile,
        ]
        log(f"ffmpeg x{TIMELAPSE_FACTOR} out={FAST_OUTPUT_SECS}s -> {outfile}")
    else:
        cmd = [
            FFMPEG_BIN,
            "-loglevel", "warning",
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_delay_max", "30",
            "-i", stream_url,
            "-t", str(NORMAL_SECS),
            "-c:v", "copy",
            "-movflags", "+faststart",
            "-y",
            outfile,
        ]
        log(f"ffmpeg normal {NORMAL_SECS}s -> {outfile}")

    return subprocess.Popen(cmd, stdin=subprocess.PIPE,
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL)

# ---------------------------------------------------------------------------
# PROCESS TRACKING
#
# _active  : list of {"proc": Popen, "filename": str} for all living children.
#            older fast-mode encodes may still be running from prior cycles.
#            pruned on obituary detection only, never on spawn.
#
# _latest  : the most recently spawned child, stored separately.
#            never pruned, always overwritten on new spawn.
#            watchdog checks _latest to detect premature death of current job.
#            _active[-1] cannot be used for this because pruning shifts it.
# ---------------------------------------------------------------------------

_active  = []
_latest  = None   # {"proc": Popen, "filename": str}
_stop    = False

# ---------------------------------------------------------------------------
# WATCHDOG - call inside any sleep/wait loop
# ---------------------------------------------------------------------------

def watchdog_poll():
    """Check all active children.  Log obituaries.  Return True if _latest died."""
    global _active

    latest_died = False
    still_alive = []

    for entry in _active:
        rc = entry["proc"].poll()
        if rc is not None:
            log(f"ffmpeg for {entry['filename']} finished rc={rc}")
            if _latest and entry["proc"] is _latest["proc"]:
                latest_died = True
        else:
            still_alive.append(entry)

    _active[:] = still_alive
    return latest_died

# ---------------------------------------------------------------------------
# WATCHDOG SLEEP - replaces bare time.sleep in wait loops
# polls every WATCHDOG_INTERVAL seconds, returns True if _latest died
# ---------------------------------------------------------------------------

def watchdog_sleep(total_secs):
    """Sleep for total_secs, polling every WATCHDOG_INTERVAL.
    Returns True immediately if _latest child dies during the wait."""
    deadline = time.time() + total_secs
    while time.time() < deadline:
        time.sleep(min(WATCHDOG_INTERVAL, max(0, deadline - time.time())))
        if watchdog_poll():
            return True
    return False

# ---------------------------------------------------------------------------
# SPAWN - fetch URL and start ffmpeg, update _active and _latest
# ---------------------------------------------------------------------------

def spawn(label=""):
    global _latest
    if label:
        log(f"Spawning new child ({label})...")
    stream_url = fetch_stream_url()
    label_dt   = now_hst()
    outfile    = DIR_OUT + "\\" + ts_filename(label_dt)
    proc       = start_ffmpeg(stream_url, outfile)
    entry      = {"proc": proc, "filename": outfile}
    _active.append(entry)
    _latest = entry   # always points at most recently spawned child
    return entry

# ---------------------------------------------------------------------------
# GRACEFUL KILL
# ---------------------------------------------------------------------------

def kill_proc(proc, label):
    if proc is None or proc.poll() is not None:
        return
    log(f"Stopping {label} (pid {proc.pid})")
    try:
        proc.stdin.write(b"q\n")
        proc.stdin.flush()
    except Exception:
        pass
    try:
        proc.wait(timeout=20)
        return
    except subprocess.TimeoutExpired:
        pass
    log(f"Terminating {label} (pid {proc.pid})")
    proc.terminate()
    try:
        proc.wait(timeout=10)
        return
    except subprocess.TimeoutExpired:
        pass
    log(f"Killing {label} (pid {proc.pid})")
    proc.kill()
    proc.wait()

# ---------------------------------------------------------------------------
# SHUTDOWN HANDLER
# ---------------------------------------------------------------------------

def _shutdown(sig, frame):
    global _stop
    _stop = True
    log("Shutdown signal received - cleaning up")
    for entry in _active:
        kill_proc(entry["proc"], entry["filename"])
    sys.exit(0)

signal.signal(signal.SIGINT,  _shutdown)
signal.signal(signal.SIGTERM, _shutdown)

# ---------------------------------------------------------------------------
# WAIT FOR TRIGGER - waits until YTDLP_LEAD_SECS before next hour.
# watchdog runs during the wait.  if _latest dies, respawn immediately.
# ---------------------------------------------------------------------------

def wait_for_trigger():
    while True:
        secs_to_hour  = seconds_until_next_hour()
        trigger_epoch = time.time() + secs_to_hour - YTDLP_LEAD_SECS
        target_dt     = datetime.datetime.fromtimestamp(trigger_epoch, tz=HST)
        log(f"Next yt-dlp fetch at {target_dt.strftime('%H:%M:%S')} HST  "
            f"(ffmpeg fires {FFMPEG_LEAD_SECS}s before hour)")

        # wait until trigger, polling watchdog every 5 seconds
        deadline = trigger_epoch
        while time.time() < deadline:
            sleep_for = min(WATCHDOG_INTERVAL, max(0, deadline - time.time()))
            time.sleep(sleep_for)
            if watchdog_poll():
                log("Latest child died during trigger wait - virgin respawn")
                spawn("emergency respawn")
                # recalculate trigger for next hour and keep waiting
                break

        if time.time() >= deadline:
            return   # trigger time reached, proceed to fetch + fire

# ---------------------------------------------------------------------------
# MAIN LOOP
# ---------------------------------------------------------------------------

def main():
    make_dirs()
    log(f"=== Hansel and Gretel  {CAM_NAME} {mode} capture starting ===")
    log(f"Output -> {DIR_OUT}")
    log(f"YouTube  -> {YOUTUBE_URL}")
    if mode == "fast":
        log(f"Timelapse x{TIMELAPSE_FACTOR}  input={FAST_INPUT_SECS}s  "
            f"output={FAST_OUTPUT_SECS}s")
    log(f"Backoff start={BACKOFF_START}s  ceiling={BACKOFF_CEILING}s")

    # First run - start immediately, duration until :55:00 of current hour
    log("First run - starting immediately")
    stream_url = fetch_stream_url()
    label_dt   = now_hst()
    outfile    = DIR_OUT + "\\" + ts_filename(label_dt)

    if mode == "fast":
        # fire and forget - do not block on encoding
        proc  = start_ffmpeg(stream_url, outfile)
        entry = {"proc": proc, "filename": outfile}
        _active.append(entry)
        global _latest
        _latest = entry
    else:
        # normal mode - block until segment finishes, then lie low
        duration = max(1.0, seconds_until_55())
        log(f"Recording {int(duration)}s until :55:00")
        # rebuild cmd with immediate duration override
        cmd = [
            FFMPEG_BIN,
            "-loglevel", "warning",
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_delay_max", "30",
            "-i", stream_url,
            "-t", str(int(duration)),
            "-c:v", "copy",
            "-movflags", "+faststart",
            "-y",
            outfile,
        ]
        log(f"ffmpeg normal {int(duration)}s -> {outfile}")
        proc  = subprocess.Popen(cmd, stdin=subprocess.PIPE,
                                 stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL)
        entry = {"proc": proc, "filename": outfile}
        _active.append(entry)
        _latest = entry
        proc.wait()
        watchdog_poll()

        pause = random.randint(LIELOW_MIN_SECS, LIELOW_MAX_SECS)
        log(f"Lie-low pause: {pause}s")
        if watchdog_sleep(pause):
            log("Latest child died during lie-low - virgin respawn")
            spawn("emergency respawn in lie-low")

    # Main hourly loop
    while not _stop:
        wait_for_trigger()

        # fetch URL then wait for ffmpeg fire time
        stream_url   = fetch_stream_url()
        secs_to_hour = seconds_until_next_hour()
        ffmpeg_epoch = time.time() + secs_to_hour - FFMPEG_LEAD_SECS
        target_dt    = datetime.datetime.fromtimestamp(ffmpeg_epoch, tz=HST)
        log(f"Waiting for ffmpeg start at {target_dt.strftime('%H:%M:%S')} HST")
        wait_until_epoch(ffmpeg_epoch)

        label_dt = now_hst()
        outfile  = DIR_OUT + "\\" + ts_filename(label_dt)
        proc     = start_ffmpeg(stream_url, outfile)
        entry    = {"proc": proc, "filename": outfile}
        _active.append(entry)
        _latest = entry

        if mode == "normal":
            # block until segment finishes
            proc.wait()
            watchdog_poll()

        # lie-low
        pause = random.randint(LIELOW_MIN_SECS, LIELOW_MAX_SECS)
        log(f"Lie-low pause: {pause}s")
        if watchdog_sleep(pause):
            log("Latest child died during lie-low - virgin respawn")
            spawn("emergency respawn in lie-low")

    log(f"=== {CAM_NAME} {mode} capture stopped ===")


if __name__ == "__main__":
    main()
