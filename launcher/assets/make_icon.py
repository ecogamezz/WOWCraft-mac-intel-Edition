"""Draws the launcher's icon (a pixel-art grass block in a gold frame) without any image library:
icon.rgba (64x64 raw RGBA, the window icon) and icon.iconset (16..1024 px PNGs, which macOS'
iconutil turns into the app's icon.icns).
    python3 assets/make_icon.py && iconutil -c icns assets/icon.iconset -o assets/icon.icns
"""
import os
import random
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
random.seed(7)

GOLD = (255, 205, 80, 255)
GOLD_DARK = (176, 128, 30, 255)
GRASS = [(106, 170, 64, 255), (92, 152, 54, 255), (122, 186, 76, 255)]
DIRT = [(134, 96, 67, 255), (116, 82, 56, 255), (150, 108, 76, 255), (96, 68, 46, 255)]


def art():
    """16x16: a one-pixel gold frame around a grass block face."""
    px = [[(0, 0, 0, 0)] * 16 for _ in range(16)]
    # Grass reaches down unevenly, as on a Minecraft block's side.
    drip = [5, 6, 5, 4, 6, 7, 5, 5, 6, 4, 5, 7, 6, 5, 4, 6]
    for y in range(16):
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                px[y][x] = GOLD if (x + y) % 5 else GOLD_DARK
            elif y < drip[x]:
                px[y][x] = random.choice(GRASS)
            else:
                px[y][x] = random.choice(DIRT)
    # Rounded corners.
    for x, y in ((0, 0), (15, 0), (0, 15), (15, 15)):
        px[y][x] = (0, 0, 0, 0)
    return px


def scaled(px, size):
    return [[px[y * 16 // size][x * 16 // size] for x in range(size)] for y in range(size)]


def png(img):
    """A PNG of RGBA rows: the signature, IHDR, one zlib'd IDAT (filter 0 per row), IEND."""
    n = len(img)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    raw = b"".join(b"\0" + b"".join(bytes(c) for c in row) for row in img)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


px = art()
with open(os.path.join(HERE, "icon.rgba"), "wb") as f:
    f.write(b"".join(bytes(c) for row in scaled(px, 64) for c in row))

iconset = os.path.join(HERE, "icon.iconset")
os.makedirs(iconset, exist_ok=True)
for size in (16, 32, 128, 256, 512):
    for scale in (1, 2):
        name = f"icon_{size}x{size}{'@2x' if scale == 2 else ''}.png"
        with open(os.path.join(iconset, name), "wb") as f:
            f.write(png(scaled(px, size * scale)))
print("icon.rgba, icon.iconset written")
