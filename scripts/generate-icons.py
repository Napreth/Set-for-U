"""Mechanical export of the approved bitmap into platform icon sizes. Requires Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
source = Image.open(ROOT / "assets/icon/set4u-icon.png").convert("RGBA")

def png(relative, size):
    target = ROOT / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    source.resize((size, size), Image.Resampling.LANCZOS).save(target)

png("assets/icon/set4u-512.png", 512)
source.save(ROOT / "assets/icon/set4u.ico", sizes=[(n, n) for n in (16, 24, 32, 48, 64, 128, 256)])
png("set4u/desktop/src/main/resources/icons/set4u.png", 512)
png("set4u/android/src/main/res/drawable-nodpi/app_icon.png", 512)
for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    png(f"set4u/android/src/main/res/mipmap-{density}/ic_launcher.png", size)

preview = Image.new("RGB", (780, 310), "#111d29")
draw = ImageDraw.Draw(preview)
x = 20
for size in (24, 32, 48, 64, 128, 256):
    icon = source.resize((size, size), Image.Resampling.LANCZOS)
    preview.paste(icon, (x, 25), icon)
    draw.text((x, 285), f"{size}px", fill="white")
    x += size + 25
target = ROOT / "build/reports/icon-preview.png"
target.parent.mkdir(parents=True, exist_ok=True)
preview.save(target)
