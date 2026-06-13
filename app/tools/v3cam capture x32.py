import subprocess
import datetime
import time
import random
import signal
import sys
from zoneinfo import ZoneInfo

# ---------------------------------------------------------------------------
# CONFIG
# ---------------------------------------------------------------------------

YOUTUBE_URL      = "https://www.youtube.com/watch?v=gXKuUyKt8mc"
CAM_NAME         = "v3cam"

YTDLP_BIN        = "yt-dlp"
FFMPEG_BIN       = "ffmpeg"

# Subfolders (created automatically if missing)
DIR_NORMAL       = r"hourly-v3cam"
DIR_FAST         = r"hourly-v3cam-x32"

# Full segment length for a normal cycle (55 minutes)
SEGMENT_SECONDS  = 3300

# How many seconds before the hour to fire ffmpeg (targets :00:00 start)
# 11 seconds of lead time puts ffmpeg connecting at :59:49 so first frame
# lands right at :00:00.  Tune this constant if drift is observed.
FFMPEG_LEAD_SECS = 11

# Timelapse factor -- change this one number to switch 16 / 30 / 32 / 60
TIMELAPSE_FACTOR = 32

# Lie-low window after :55:00 stop, before next yt-dlp fetch + ffmpeg start
LIELOW_MIN_SECS  = 60    # 1 minute
LIELOW_MAX_SECS  = 240   # 4 minutes

# yt-dlp flags -- edit -f value here to change quality (94 = 480p)
YTDLP_CMD = [
    YTDLP_BIN,
    "-f", "94",
    "-g",
    "--no-playlist",
    "--retries", "2",
    "--extractor-args", "youtube:player_client=android",
]

# Exponential backoff for yt-dlp fetch failures
BACKOFF_BASE_SECS = 15
BACKOFF_MAX_SECS  = 300
BACKOFF_FACTOR    = 2

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
    os.makedirs(DIR_NORMAL, exist_ok=True)
    os.makedirs(DIR_FAST,   exist_ok=True)

def ts_filename(dt, suffix=""):
    base = dt.strftime("%Y-%m-%d_%H-%M-%S")
    return f"{base}_{CAM_NAME}{suffix}.mp4"

def seconds_until_next_hour():
    t = now_hst()
    secs_past = t.minute * 60 + t.second + t.microsecond / 1_000_000
    return 3600.0 - secs_past

def seconds_until_55():
    """Seconds until :55:00 of the current hour.  Negative if already past."""
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
# yt-dlp URL FETCH  (with exponential backoff)
# ---------------------------------------------------------------------------

def fetch_stream_url():
    backoff = BACKOFF_BASE_SECS
    attempt = 0

    while True:
        attempt += 1
        try:
            result = subprocess.run(
                YTDLP_CMD + [YOUTUBE_URL],
                capture_output=True,
                text=True,
                timeout=30,
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
        backoff = min(backoff * BACKOFF_FACTOR, BACKOFF_MAX_SECS)

# ---------------------------------------------------------------------------
# FFMPEG LAUNCH
# ---------------------------------------------------------------------------

def start_ffmpeg_normal(stream_url, outfile, duration):
    cmd = [
        FFMPEG_BIN,
        "-loglevel", "warning",
        "-reconnect", "1",
        "-reconnect_streamed", "1",
        "-reconnect_delay_max", "30",
        "-i", stream_url,
        "-t", str(int(duration)),
        "-c:v", "copy",
        "-an",
        "-movflags", "+faststart",
        "-y",
        outfile,
    ]
    log(f"ffmpeg normal  {int(duration)}s -> {outfile}")
    return subprocess.Popen(cmd, stdin=subprocess.PIPE,
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL)

def start_ffmpeg_fast(stream_url, outfile, duration):
    duration = ( duration / TIMELAPSE_FACTOR )
    cmd = [
        FFMPEG_BIN,
        "-loglevel", "warning",
        "-reconnect", "1",
        "-reconnect_streamed", "1",
        "-reconnect_delay_max", "30",
        "-i", stream_url,
        "-t", str(int(duration)),
        "-vf", f"setpts=PTS/{TIMELAPSE_FACTOR}",
        "-r", "30",
        "-an",
        "-movflags", "+faststart",
        "-y",
        outfile,
    ]
    log(f"ffmpeg x{TIMELAPSE_FACTOR}    {int(duration)}s -> {outfile}")
    return subprocess.Popen(cmd, stdin=subprocess.PIPE,
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL)

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

_procs = []
_stop  = False

def _shutdown(sig, frame):
    global _stop
    _stop = True
    log("Shutdown signal received -- cleaning up")
    for label, proc in _procs:
        kill_proc(proc, label)
    sys.exit(0)

signal.signal(signal.SIGINT,  _shutdown)
signal.signal(signal.SIGTERM, _shutdown)

# ---------------------------------------------------------------------------
# ONE CYCLE
# ---------------------------------------------------------------------------

def run_cycle(stream_url, immediate=False):
    global _procs

    if immediate:
        # Start right now, run until :55:00 of the current hour
        duration = max(1.0, seconds_until_55())
        label_dt = now_hst()
        log(f"Immediate start -- recording {int(duration)}s until :55:00")
    else:
        # Wait for :59:49 (FFMPEG_LEAD_SECS before the next hour)
        secs_to_hour = seconds_until_next_hour()
        ffmpeg_epoch = time.time() + secs_to_hour - FFMPEG_LEAD_SECS

        # If already inside the lead window, push to the hour after next
        if secs_to_hour < FFMPEG_LEAD_SECS + 5:
            log(f"Too close to boundary ({secs_to_hour:.1f}s) -- targeting next hour")
            ffmpeg_epoch += 3600.0

        target_dt = datetime.datetime.fromtimestamp(ffmpeg_epoch, tz=HST)
        log(f"Waiting for ffmpeg start at {target_dt.strftime('%H:%M:%S')} HST")
        wait_until_epoch(ffmpeg_epoch)

        duration = SEGMENT_SECONDS
        label_dt = now_hst()

    f_normal = DIR_NORMAL + "\\" + ts_filename(label_dt)
    f_fast   = DIR_FAST   + "\\" + ts_filename(label_dt,
                                               suffix=f"_x{TIMELAPSE_FACTOR}")

    #proc_normal = start_ffmpeg_normal(stream_url, f_normal, duration)
    proc_fast   = start_ffmpeg_fast(stream_url,   f_fast,   duration)
    _procs = [  #("ffmpeg-normal", proc_normal),
        ("ffmpeg-fast", proc_fast)]

    #proc_normal.wait()
    proc_fast.wait()

    #rc_n = proc_normal.returncode
    rc_f = proc_fast.returncode
    log(f"ffmpeg normal rc={rc_f}") #n}  fast rc={rc_

    _procs = []
    return rc_f == 0 #and rc_n == 0

# ---------------------------------------------------------------------------
# MAIN LOOP
# ---------------------------------------------------------------------------

def main():
    make_dirs()
    log(f"=== Hansel & Gretel  {CAM_NAME} capture starting ===")
    log(f"Normal -> {DIR_NORMAL}   Fast x{TIMELAPSE_FACTOR} -> {DIR_FAST}")

    first_run = True

    while not _stop:
        log("Fetching stream URL via yt-dlp...")
        stream_url = fetch_stream_url()

        success = run_cycle(stream_url, immediate=first_run)
        first_run = False

        pause = random.randint(LIELOW_MIN_SECS, LIELOW_MAX_SECS)
        if not success:
            pause = max(pause, 30)
        log(f"Lie-low pause: {pause}s")
        time.sleep(pause)

    log(f"=== {CAM_NAME} capture stopped ===")


if __name__ == "__main__":
    main()
