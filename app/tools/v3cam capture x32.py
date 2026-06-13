import subprocess
import datetime
import time
import signal
import sys
from zoneinfo import ZoneInfo

# ---------------------------------------------------------------------------
# CONFIG  (the laboratory)
# ---------------------------------------------------------------------------

YOUTUBE_URL      = "https://www.youtube.com/watch?v=gXKuUyKt8mc"
CAM_NAME         = "v3cam"
DIR_FAST         = r"hourly-v3cam-x32"

YTDLP_BIN        = "yt-dlp"
FFMPEG_BIN       = "ffmpeg"

# Each cycle reads 1 hour + 15 seconds from the stream.
# Output duration is divided by TIMELAPSE_FACTOR to keep ffmpeg honest.
SEGMENT_SECONDS  = 3615          # 60 min + 15 sec input window

# Fire exactly 15 seconds before the hour  (e.g. 23:59:45)
TRIGGER_LEAD_SECS = 15

TIMELAPSE_FACTOR = 32            # change to 16 / 30 / 60 as needed

YTDLP_CMD = [
    YTDLP_BIN,
    "-f", "94",
    "-g",
    "--no-playlist",
    "--retries", "2",
    "--extractor-args", "youtube:player_client=android",
]

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
    os.makedirs(DIR_FAST, exist_ok=True)

def ts_filename(dt):
    return dt.strftime("%Y-%m-%d_%H-%M-%S") + f"_{CAM_NAME}_x{TIMELAPSE_FACTOR}.mp4"

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
# yt-dlp URL FETCH
# ---------------------------------------------------------------------------

def fetch_stream_url():
    backoff = BACKOFF_BASE_SECS
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
        backoff = min(backoff * BACKOFF_FACTOR, BACKOFF_MAX_SECS)

# ---------------------------------------------------------------------------
# FFMPEG  (fire and forget -- re-encode takes as long as it takes)
# ---------------------------------------------------------------------------

def spawn_ffmpeg(stream_url, outfile):
    out_duration = SEGMENT_SECONDS // TIMELAPSE_FACTOR
    cmd = [
        FFMPEG_BIN,
        "-loglevel", "warning",
        "-reconnect", "1",
        "-reconnect_streamed", "1",
        "-reconnect_delay_max", "30",
        "-i", stream_url,
        "-t", str(out_duration),
        "-vf", f"setpts=PTS/{TIMELAPSE_FACTOR}",
        "-r", "30",
        "-an",
        "-movflags", "+faststart",
        "-y",
        outfile,
    ]
    log(f"ffmpeg x{TIMELAPSE_FACTOR} out={out_duration}s -> {outfile}  (fire and forget)")
    return subprocess.Popen(cmd, stdin=subprocess.PIPE,
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL)

# ---------------------------------------------------------------------------
# SHUTDOWN
# ---------------------------------------------------------------------------

_active = []
_stop   = False

def _shutdown(sig, frame):
    global _stop
    _stop = True
    log("Shutdown signal received -- sending q to any active ffmpeg processes")
    for p in _active:
        if p.poll() is None:
            try:
                p.stdin.write(b"q\n")
                p.stdin.flush()
            except Exception:
                pass
    sys.exit(0)

signal.signal(signal.SIGINT,  _shutdown)
signal.signal(signal.SIGTERM, _shutdown)

# ---------------------------------------------------------------------------
# WAIT FOR TRIGGER  (:59:45 each hour, fixed, no jitter)
# ---------------------------------------------------------------------------

def wait_for_trigger():
    secs_to_hour = seconds_until_next_hour()
    trigger_epoch = time.time() + secs_to_hour - TRIGGER_LEAD_SECS

    target_dt = datetime.datetime.fromtimestamp(trigger_epoch, tz=HST)
    log(f"Waiting for trigger at {target_dt.strftime('%H:%M:%S')} HST "
        f"({TRIGGER_LEAD_SECS}s before hour)")
    wait_until_epoch(trigger_epoch)

# ---------------------------------------------------------------------------
# MAIN LOOP
# ---------------------------------------------------------------------------

def main():
    make_dirs()
    log(f"=== Hansel and Gretel  {CAM_NAME} x{TIMELAPSE_FACTOR} laboratory starting ===")
    log(f"Output -> {DIR_FAST}")
    log(f"Trigger: {TRIGGER_LEAD_SECS}s before each hour  |  "
        f"Input: {SEGMENT_SECONDS}s  |  "
        f"Output: {SEGMENT_SECONDS // TIMELAPSE_FACTOR}s  |  "
        f"Overlaps allowed")

    # First run -- start immediately, no boundary wait
    log("First run -- starting immediately")
    log("Fetching stream URL via yt-dlp...")
    stream_url = fetch_stream_url()
    label_dt = now_hst()
    outfile = DIR_FAST + "\\" + ts_filename(label_dt)
    proc = spawn_ffmpeg(stream_url, outfile)
    _active.append(proc)

    # All subsequent runs -- fire at :59:45 each hour
    while not _stop:
        wait_for_trigger()
        _active[:] = [p for p in _active if p.poll() is None]

        log("Fetching stream URL via yt-dlp...")
        stream_url = fetch_stream_url()

        label_dt = now_hst()
        outfile = DIR_FAST + "\\" + ts_filename(label_dt)
        proc = spawn_ffmpeg(stream_url, outfile)
        _active.append(proc)

    log(f"=== {CAM_NAME} x{TIMELAPSE_FACTOR} laboratory stopped ===")

if __name__ == "__main__":
    main()
