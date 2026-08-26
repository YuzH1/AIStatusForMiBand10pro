import os
import struct
import zlib

ACCENT = (46, 160, 255)
BG = (15, 17, 21)


def make_png(size, path):
    scale = size / 192.0
    rows = []
    for y in range(size):
        row = bytearray()
        row.append(0)
        for x in range(size):
            cx = x / scale
            cy = y / scale
            r = 82.0
            r_in = 54.0
            d = ((cx - 96) ** 2 + (cy - 96) ** 2) ** 0.5
            if d < 62.0:
                color = BG
                alpha = 0 if d < 62.0 - 6.0 else int((d - (62.0 - 6.0)) / 6.0 * 255)
            else:
                color = BG
                alpha = 0
            if r_in <= d <= r:
                color = ACCENT
                alpha = 255
            row.extend((color[0], color[1], color[2], alpha))
        rows.append(bytes(row))

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    raw = b"".join(rows)
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as f:
        f.write(png)
    print("wrote", path, size, "px")


root = r"D:\GithubProgram\ai-quota-watch"
make_png(192, os.path.join(root, "quickapp", "src", "common", "logo.png"))

mipmaps = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
for folder, size in mipmaps.items():
    make_png(size, os.path.join(root, "companion", "app", "src", "main", "res", folder, "ic_launcher.png"))