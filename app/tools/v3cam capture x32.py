import subprocess
import datetime
import time
import random
import signal
import sys
from zoneinfo import ZoneInfo

# ---------------------------------------------------------------------------
# USAGE
#   python vXcam_capture_v06.py [cam] [mode]
#   cam  : v3 (default) or v1
#   mode : fast (default) or normal
#
# Examples:
#   python vXcam_capture_v06.py              # v3 fast
#   python vXcam_capture_v06.py v1           # v1 fast
#   python vXcam_capture_v06.py v3 normal    # v3 normal
#   python vXcam_capture_v06.py v1 normal    # v1 normal
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

YOUTUBE_URL      = "https://www.youtube.com/watch?v=" + CFG["youtube_id"]
CAM_NAME         = CFG["cam_name"]
DIR_OUT          = CFG["dir_out"]
BACKOFF_START    = CFG["backoff_start"]
BACKOFF_CEILING  = CFG["backoff_ceiling"]
YTDLP_LEAD_SECS  = CFG["ytdlp_lead_secs"]
FFMPEG_LEAD_SECS = CFG["ffmpeg_lead_secs"]

YTDLP_BIN  = "yt-dlp"
FFMPEG_BIN = "ffmpeg"

# Fast mode settings
TIMELAPSE_FACTOR = 64
FAST_INPUT_SECS  = 3635   # input window - pushed away from top-of-hour boundary
FAST_OUTPUT_SECS = FAST_INPUT_SECS // TIMELAPSE_FACTOR

# Normal mode settings
NORMAL_SECS = 3601   # just over 60 minutes - no gap between files

# Lie-low window after segment ends (identical for all modes)
LIELOW_MIN_SECS = 60
LIELOW_MAX_SECS = 240

# Watchdog poll interval in seconds
POLL_INTERVAL = 5

# yt-dlp format differs by mode
YTDLP_FORMAT = "best" if mode == "normal" else "94"

YTDLP_CMD = [
    YTDLP_BIN,
    "-f", YTDLP_FORMAT,
    "-g",
    "--no-playlist",
    "--retries", "2",
    "--cookies-from-browser", "chrome",
    "--extractor-args", "youtube:player_client=tv_embedded",
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
    log(f"Trying to fetch url for {YOUTUBE_URL}")
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

def start_ffmpeg_normal(stream_url, outfile):
    duration = max(1.0, seconds_until_next_hour() + 1)
    cmd = [
        FFMPEG_BIN,
        "-loglevel", "warning",
        "-reconnect", "1",
        "-reconnect_streamed", "1",
        "-reconnect_delay_max", "30",
        "-i", stream_url,
        "-t", str(int(duration)),
        "-c", "copy",
        #"-movflags", "+faststart",
        "-y",
        outfile,
    ]
    log(f"ffmpeg normal {int(duration)}s -> {outfile}")
    return subprocess.Popen(cmd, stdin=subprocess.PIPE,
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL)

def start_ffmpeg_fast(stream_url, outfile):
    duration = max(1.0, seconds_until_next_hour() + 1)
    FAST_OUTPUT_SECS = duration //TIMELAPSE_FACTOR
    cmd = [
        FFMPEG_BIN,
        "-loglevel", "warning",
        "-reconnect", "1",
        "-reconnect_streamed", "1",
        "-reconnect_delay_max", "30",
        "-i", stream_url,
        "-vf", f"setpts=PTS/{TIMELAPSE_FACTOR}",
        "-t", str(FAST_OUTPUT_SECS),
        "-r", "30",
        "-an",
        #"-movflags", "+faststart",
        "-y",
        outfile,
    ]
    log(f"ffmpeg x{TIMELAPSE_FACTOR} out={FAST_OUTPUT_SECS}s -> {outfile}")
    return subprocess.Popen(cmd, stdin=subprocess.PIPE,
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL)

# ---------------------------------------------------------------------------
# PROCESS TRACKING
#
# _active  : list of {"proc": Popen, "filename": str} for all living children.
#            older fast-mode encodes may still be running from prior cycles.
#            entries are pruned only when their obituary is logged.
#
# _latest  : the most recently spawned child {"proc": Popen, "filename": str}.
#            never pruned, always overwritten on each new spawn.
#            _active[-1] cannot be used for this purpose because pruning
#            shifts the list and would lose the reference to the current job.
# ---------------------------------------------------------------------------

_active = []
_latest = None
_stop   = False

# ---------------------------------------------------------------------------
# SPAWN - fetch URL, start ffmpeg, update _active and _latest
# ---------------------------------------------------------------------------

def spawn(label=""):
    global _latest
    if label:
        log(f"Spawning new child ({label})...")
    stream_url = fetch_stream_url()
    label_dt   = now_hst()
    outfile    = DIR_OUT + "\\" + ts_filename(label_dt)
    if mode == "fast":
        proc = start_ffmpeg_fast(stream_url, outfile)
    else:
        proc = start_ffmpeg_normal(stream_url, outfile)
    entry   = {"proc": proc, "filename": outfile}
    _active.append(entry)
    _latest = entry
    return entry

# ---------------------------------------------------------------------------
# POLL - sweep _active, log obituaries, return True if _latest has exited
# ---------------------------------------------------------------------------

def poll():
    global _active
    latest_gone = False
    still_alive = []
    for entry in _active:
        rc = entry["proc"].poll()
        if rc is not None:
            log(f"ffmpeg for {entry['filename']} finished rc={rc}")
            if _latest and entry["proc"] is _latest["proc"]:
                latest_gone = True
        else:
            still_alive.append(entry)
    _active[:] = still_alive
    return latest_gone

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
# WAIT FOR TRIGGER
#
# Waits until YTDLP_LEAD_SECS before the next hour so fetch_stream_url()
# can run and complete before ffmpeg needs to fire.
#
# Polls every POLL_INTERVAL seconds.  If _latest has exited prematurely,
# a panic respawn fires immediately.  The trigger wait then continues for
# the same hour boundary - the panic child covers the gap.
# ---------------------------------------------------------------------------

def wait_for_trigger():
    secs_to_hour  = seconds_until_next_hour()
    trigger_epoch = time.time() + secs_to_hour - YTDLP_LEAD_SECS
    target_dt     = datetime.datetime.fromtimestamp(trigger_epoch, tz=HST)
    log(f"Next yt-dlp fetch at {target_dt.strftime('%H:%M:%S')} HST  "
        f"(ffmpeg fires {FFMPEG_LEAD_SECS}s before hour)")

    while time.time() < trigger_epoch:
        sleep_for = min(POLL_INTERVAL, max(1, trigger_epoch - time.time()))
        time.sleep(sleep_for)
        if poll():
            log("Latest child exited prematurely - panic respawn")
            spawn("panic respawn")

# ---------------------------------------------------------------------------
# LIE-LOW - deliberate rest period between cycles.
# Polls for obituaries only - no respawn logic here.
# The main loop handles the next cycle naturally after lie-low ends.
# ---------------------------------------------------------------------------

def lie_low():
    pause = random.randint(LIELOW_MIN_SECS, LIELOW_MAX_SECS)
    log(f"Lie-low pause: {pause}s")
    deadline = time.time() + pause
    while time.time() < deadline:
        sleep_for = min(POLL_INTERVAL, max(1, deadline - time.time()))
        time.sleep(sleep_for)
        poll()   # log obituaries only, no action taken

# ---------------------------------------------------------------------------
# MAIN LOOP
# ---------------------------------------------------------------------------

def main():
    global _latest

    make_dirs()
    log(f"=== Hansel and Gretel  {CAM_NAME} {mode} capture starting ===")
    log(f"Output   -> {DIR_OUT}")
    log(f"YouTube  -> {YOUTUBE_URL}")
    if mode == "fast":
        log(f"Timelapse x{TIMELAPSE_FACTOR}  "
            f"input={FAST_INPUT_SECS}s  output={FAST_OUTPUT_SECS}s")
    log(f"Backoff start={BACKOFF_START}s  ceiling={BACKOFF_CEILING}s")

    # First run - start immediately.
    # Fast mode: fire and forget, fall into hourly loop.
    # Normal mode: record until :55:00 of current hour, then lie-low.
    log("First run - starting immediately")
    stream_url = fetch_stream_url()
    spawn("Initial spawn")

    # Hourly loop
    while not _stop:
        wait_for_trigger()
        stream_url = fetch_stream_url()
        lie_low()
        spawn("Hourly restart")

    log(f"=== {CAM_NAME} {mode} capture stopped ===")


if __name__ == "__main__":
    main()
