#!/usr/bin/env python3
"""Generate the diary launcher icon: ivory paper, a flowing R, a red margin."""
from PIL import Image, ImageDraw, ImageFont
import os

BASE = "/var/minis/workspace/riddle-android/app/src/main/res"
TTF = "/var/minis/workspace/riddle-android/app/src/main/assets/DancingScript.ttf"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
S = 1024  # master canvas

img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# rounded ivory card
d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=(250, 247, 239, 255))
# subtle inner border
d.rounded_rectangle([int(S * 0.045)] * 2 + [S - 1 - int(S * 0.045)] * 2,
                    radius=int(S * 0.18), outline=(213, 206, 187, 255), width=int(S * 0.012))
# red margin line (echoes the page)
d.line([(int(S * 0.22), int(S * 0.10)), (int(S * 0.22), int(S * 0.90))],
       fill=(228, 174, 176, 255), width=int(S * 0.016))
# faint rules
for i in range(1, 6):
    y = int(S * (0.20 + 0.13 * i))
    d.line([(int(S * 0.30), y), (int(S * 0.86), y)], fill=(233, 227, 211, 255),
           width=int(S * 0.008))

# the flowing R
font = ImageFont.truetype(TTF, int(S * 0.72))
bbox = d.textbbox((0, 0), "R", font=font)
w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
d.text((int(S * 0.54) - w / 2 - bbox[0], int(S * 0.52) - h / 2 - bbox[1]), "R",
       font=font, fill=(23, 23, 23, 255))

for dpi, px in SIZES.items():
    out = os.path.join(BASE, "mipmap-" + dpi)
    os.makedirs(out, exist_ok=True)
    img.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"))
    print("wrote", out, px)
