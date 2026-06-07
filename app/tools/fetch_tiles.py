#
# Downloads OSM tile bundle for Hansel APK assets.
# Fetches 25 tiles (5x5 grid) centered on Kilauea north vent
# at zoom levels 14, 15, and 16.
# Output: tiles/z/x/y.png - drop the tiles/ folder into
# android/app/src/main/assets/
#
# Run once on laptop with wifi.  Do not run repeatedly - OSM
# rate limits aggressive fetchers.  One tile per second.
#
# (c) OpenStreetMap contributors - tiles fetched from
# tile.openstreetmap.org per OSM tile usage policy.

import math
import os
import time
import urllib.request

USER_AGENT = "Hansel/0.986 personal field logger - single user, Kilauea HI"
ZOOM_LEVELS = [8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19]
GRID = 5  # 5x5 = 25 tiles per zoom level
CENTER_LAT = 19.402
CENTER_LON = -155.29
DELAY = 1.0  # seconds between requests - be polite


def lat_lon_to_tile(lat, lon, zoom):
    n = 2**zoom
    x = int((lon + 180.0) / 360.0 * n)
    y = int(
        (
            1.0
            - math.log(math.tan(math.radians(lat)) + 1.0 / math.cos(math.radians(lat)))
            / math.pi
        )
        / 2.0
        * n
    )
    return x, y


def fetch_tile(z, x, y):
    path = os.path.join("tiles", str(z), str(x), str(y) + ".png")
    if os.path.exists(path):
        print(f"  skip {z}/{x}/{y} (exists)")
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    url = f"https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            with open(path, "wb") as f:
                f.write(resp.read())
        print(f"  fetched {z}/{x}/{y}")
    except Exception as e:
        print(f"  FAILED {z}/{x}/{y}: {e}")
    time.sleep(DELAY)


def main():
    print(f"Fetching {GRID}x{GRID} tile grid at zoom levels {ZOOM_LEVELS}")
    print(f"Center: {CENTER_LAT}, {CENTER_LON}")
    print(f"User-Agent: {USER_AGENT}\n")

    total = 0
    for z in ZOOM_LEVELS:
        cx, cy = lat_lon_to_tile(CENTER_LAT, CENTER_LON, z)
        half = GRID // 2
        print(f"Z{z}: center tile {cx},{cy}")
        for dx in range(-half, half + 1):
            for dy in range(-half, half + 1):
                fetch_tile(z, cx + dx, cy + dy)
                total += 1

    print(f"\nDone.  {total} tiles attempted.")
    print("Drop the tiles/ folder into app/src/main/assets/")


if __name__ == "__main__":
    main()
