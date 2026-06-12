from PIL import Image, ImageDraw
from datetime import datetime

img = Image.open('UWEV-CRIM-GPS-1yr.png').convert('RGB')
W, H = img.size
draw = ImageDraw.Draw(img)

PLOT = {'left': 0.069, 'right': 0.978, 'top': 0.055, 'bottom': 0.818}
T0 = datetime(2025, 6, 10)
T1 = datetime(2026, 6, 10)
TSPAN = (T1 - T0).total_seconds()
Y_MAX = 0.15
Y_MIN = -0.15
YSPAN = Y_MAX - Y_MIN

def px(d):
    t = (datetime.strptime(d, '%Y-%m-%d') - T0).total_seconds()
    return int((PLOT['left'] + t/TSPAN*(PLOT['right']-PLOT['left'])) * W)

def py(m):
    return int((PLOT['top'] + (Y_MAX-m)/YSPAN*(PLOT['bottom']-PLOT['top'])) * H)

# each episode: (start_date, bottom_value, end_date, top_value)
# YOU SUPPLY THESE
episodes = [
    ('2025-07-03', -0.07, '2025-07-18', -0.02)
    ]
/*
ep24  start=2025-06-04  bot=?       top=?
ep25  start=2025-06-11  bot=?       top=-0.007
ep26  start=2025-06-20  bot=-0.072  top=0.127   <- suspect, probably a dangler
ep27  start=2025-06-29  bot=-0.084  top=-0.013
ep28  start=2025-07-09  bot=-0.079  top=-0.001
ep29  start=2025-07-20  bot=-0.085  top=0.003
ep30  start=2025-08-06  bot=-0.079  top=0.013
ep31  start=2025-08-22  bot=-0.072  top=0.010
ep32  start=2025-09-02  bot=-0.096  top=0.012
ep33  start=2025-09-19  bot=-0.059  top=0.020
ep34  start=2025-10-01  bot=-0.080  top=0.021
ep35  start=2025-10-17  bot=-0.084  top=0.030
ep36  start=2025-11-09  bot=-0.045  top=0.051
ep37  start=2025-11-25  bot=-0.098  top=0.037
ep38  start=2025-12-06  bot=-0.060  top=0.048
ep39  start=2025-12-24  bot=-0.013  top=0.071
ep40  start=2026-01-12  bot=-0.055  top=0.046
ep41  start=2026-01-24  bot=-0.003  top=0.060
ep42  start=2026-02-15  bot=-0.001  top=0.051
ep43  start=2026-03-10  bot=-0.001  top=0.067
ep44  start=2026-04-09  bot=0.024   top=0.088
ep45  start=2026-04-23  bot=0.021   top=0.100
ep46  start=2026-05-05  bot=0.041   top=0.075
ep47  start=2026-05-14  bot=0.029   top=?
ep48  start=2026-06-01  bot=?       top=?
*/

COLOR = (204, 24, 0)
LW = max(2, int(W * 0.0022))

for start_d, bot_v, end_d, top_v in episodes:
    x0, y0 = px(start_d), py(bot_v)
    x1, y1 = px(end_d),   py(top_v)
    for t in range(-LW//2, LW//2+1):
        draw.line([(x0, y0+t),(x1, y1+t)], fill=COLOR)

img.save('uwev_chopsticks.png')
