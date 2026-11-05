#!/usr/bin/env python3
"""Minimal PGS (Blu-ray .sup) subtitle writer for test clips.

Renders each cue as white text with a black outline into a 1920x1080 graphics plane and writes the
Presentation Graphics Stream segments (PCS, WDS, PDS, ODS, END) plus a clearing display set at the end of
each cue. Forced cues set the object's forced-on flag.

Usage: make_pgs.py out.sup "start-end|text" ["start-end|text|forced" ...]   (times in seconds)
"""
import struct
import sys

from PIL import Image, ImageDraw, ImageFont

PLANE_W, PLANE_H = 1920, 1080
FONT_CANDIDATES = [
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/System/Library/Fonts/Supplemental/Verdana Bold.ttf",
    "/Library/Fonts/Arial Bold.ttf",
]


def font(size):
    for path in FONT_CANDIDATES:
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


# Palette: index 0 transparent, then luminance x alpha levels (white text, black outline, anti-aliasing)
LUMA = [16 + round(i * (235 - 16) / 7) for i in range(8)]
ALPHA = [round(i * 255 / 7) for i in range(1, 8)]


def palette_index(l, a):
    return 1 + l * len(ALPHA) + (a - 1)


def segment(kind, pts, payload):
    return b"PG" + struct.pack(">IIBH", pts, 0, kind, len(payload)) + payload


def rle(img):
    out = bytearray()
    w, h = img.size
    px = img.load()
    for y in range(h):
        x = 0
        while x < w:
            c = px[x, y]
            run = 1
            while x + run < w and px[x + run, y] == c and run < 16383:
                run += 1
            if c == 0:
                out += bytes([0, run]) if run < 64 else bytes([0, 0x40 | (run >> 8), run & 0xFF])
            elif run < 3:
                out += bytes([c]) * run
            elif run < 64:
                out += bytes([0, 0x80 | run, c])
            else:
                out += bytes([0, 0xC0 | (run >> 8), run & 0xFF, c])
            x += run
        out += b"\x00\x00"
    return bytes(out)


def render(text):
    f = font(54)
    stroke = 4
    probe = Image.new("L", (1, 1))
    box = ImageDraw.Draw(probe).textbbox((0, 0), text, font=f, stroke_width=stroke)
    w, h = box[2] - box[0] + 8, box[3] - box[1] + 8
    rgba = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(rgba).text((4 - box[0], 4 - box[1]), text, font=f, fill=(255, 255, 255, 255),
                              stroke_width=stroke, stroke_fill=(0, 0, 0, 255))
    indexed = Image.new("P", (w, h), 0)
    src, dst = rgba.load(), indexed.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = src[x, y]
            ai = round(a * 7 / 255)
            if ai == 0:
                continue
            li = round(((r + g + b) / 3) * 7 / 255)
            dst[x, y] = palette_index(li, ai)
    return indexed


def display_set(number, start, end, text, forced):
    obj = render(text)
    w, h = obj.size
    x, y = (PLANE_W - w) // 2, PLANE_H - 140 - h
    pts_on, pts_off = int(start * 90000), int(end * 90000)
    out = bytearray()
    # PCS: epoch start, one composition object
    flags = 0x40 if forced else 0x00
    pcs = struct.pack(">HHBHBBBB", PLANE_W, PLANE_H, 0x10, number * 2, 0x80, 0, 0, 1)
    pcs += struct.pack(">HBBHH", 0, 0, flags, x, y)
    out += segment(0x16, pts_on, pcs)
    out += segment(0x17, pts_on, struct.pack(">BBHHHH", 1, 0, x, y, w, h))
    pds = struct.pack(">BB", 0, 0)
    for li, l in enumerate(LUMA):
        for ai, a in enumerate(ALPHA, start=1):
            pds += struct.pack(">BBBBB", palette_index(li, ai), l, 128, 128, a)
    out += segment(0x14, pts_on, pds)
    data = struct.pack(">HH", w, h) + rle(obj)
    total = len(data)
    first, chunk = True, 0xFFE0 - 11
    while data:
        part, data = data[:chunk], data[chunk:]
        seq = (0x80 if first else 0) | (0x40 if not data else 0)
        head = struct.pack(">HBB", 0, 0, seq)
        if first:
            head += (total).to_bytes(3, "big")
        out += segment(0x15, pts_on, head + part)
        first = False
    out += segment(0x80, pts_on, b"")
    # Clearing display set
    pcs_off = struct.pack(">HHBHBBBB", PLANE_W, PLANE_H, 0x10, number * 2 + 1, 0x00, 0, 0, 0)
    out += segment(0x16, pts_off, pcs_off)
    out += segment(0x17, pts_off, struct.pack(">BBHHHH", 1, 0, x, y, w, h))
    out += segment(0x80, pts_off, b"")
    return bytes(out)


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    with open(sys.argv[1], "wb") as fh:
        for n, cue in enumerate(sys.argv[2:]):
            parts = cue.split("|")
            start, end = (float(t) for t in parts[0].split("-"))
            fh.write(display_set(n, start, end, parts[1], len(parts) > 2 and parts[2] == "forced"))


if __name__ == "__main__":
    main()
