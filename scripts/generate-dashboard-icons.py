#!/usr/bin/env python3
"""Rebuild original dashboard icons with Pillow: python3 scripts/generate-dashboard-icons.py.

All artwork is drawn from geometry. GIFs use a shared, explicit transparent
palette entry; PNGs retain antialiased alpha. No downloaded assets are used.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

from PIL import Image, ImageDraw


SIZE = 128
SCALE = 4
FRAMES = 24
DURATION = 80
OUTPUT = Path(__file__).resolve().parents[1] / "ruoyi-ui/public/dashboard/assets/icon-library"
CYAN = "#25d5ed"
BLUE = "#298be8"
NAVY = "#16486c"
LIGHT = "#a4f3fa"
MID = "#3287a8"


def xy(point):
    return tuple(round(value * SCALE) for value in point)


class Canvas:
    def __init__(self):
        self.image = Image.new("RGBA", (SIZE * SCALE, SIZE * SCALE))
        self.draw = ImageDraw.Draw(self.image)

    def line(self, points, fill=CYAN, width=3):
        self.draw.line([xy(point) for point in points], fill=fill,
                       width=max(1, round(width * SCALE)), joint="curve")

    def ellipse(self, bounds, fill=None, outline=None, width=2):
        self.draw.ellipse(xy(bounds), fill=fill, outline=outline,
                          width=max(1, round(width * SCALE)))

    def rect(self, bounds, fill=None, outline=None, width=2, radius=0):
        self.draw.rounded_rectangle(xy(bounds), radius=round(radius * SCALE),
                                    fill=fill, outline=outline,
                                    width=max(1, round(width * SCALE)))

    def poly(self, points, fill=CYAN, outline=NAVY, width=1.5):
        self.draw.polygon([xy(point) for point in points], fill=fill)
        if outline:
            self.line([*points, points[0]], outline, width)

    def arc(self, bounds, start, end, fill=CYAN, width=3):
        self.draw.arc(xy(bounds), start, end, fill=fill,
                      width=max(1, round(width * SCALE)))

    def finish(self):
        return self.image.resize((SIZE, SIZE), Image.Resampling.LANCZOS)


def polar(center, radius, angle):
    radians = math.radians(angle)
    return center[0] + math.cos(radians) * radius, center[1] + math.sin(radians) * radius


def rotate(point, angle, center=(64, 64)):
    radians = math.radians(angle)
    dx, dy = point[0] - center[0], point[1] - center[1]
    return (center[0] + dx * math.cos(radians) - dy * math.sin(radians),
            center[1] + dx * math.sin(radians) + dy * math.cos(radians))


def blend(first, second, progress):
    a, b = [bytes.fromhex(color.lstrip("#")) for color in (first, second)]
    return tuple(round(x + (y - x) * progress) for x, y in zip(a, b)) + (255,)


def fan(progress):
    c = Canvas()
    c.rect((14, 14, 114, 114), outline=NAVY, width=5, radius=17)
    c.rect((15, 15, 113, 113), outline=BLUE, width=2, radius=16)
    for x, y in ((24, 24), (104, 24), (24, 104), (104, 104)):
        c.ellipse((x - 2, y - 2, x + 2, y + 2), fill=LIGHT, outline=NAVY, width=1)
    c.ellipse((25, 25, 103, 103), outline=MID, width=2)
    blade = [(61, 58), (66, 45), (76, 33), (84, 32), (90, 37), (91, 44),
             (85, 53), (76, 60), (69, 64)]
    for angle in range(0, 360, 90):
        c.poly([rotate(point, angle + progress * 90) for point in blade], fill=CYAN)
    c.ellipse((54, 54, 74, 74), fill=BLUE, outline=NAVY, width=2)
    c.ellipse((60, 60, 68, 68), fill=LIGHT)
    return c.finish()


def gear(progress):
    c = Canvas()
    points = []
    for tooth in range(10):
        for offset, radius in ((-18, 38), (-12, 38), (-9, 48), (9, 48), (12, 38)):
            points.append(polar((64, 64), radius, tooth * 36 + offset + progress * 36))
    c.poly(points, fill=BLUE, outline=NAVY, width=2)
    c.ellipse((34, 34, 94, 94), fill=CYAN, outline=NAVY, width=2)
    c.ellipse((48, 48, 80, 80), fill=NAVY, outline=LIGHT, width=3)
    for index in range(5):
        x, y = polar((64, 64), 23, index * 72 + progress * 36)
        c.ellipse((x - 2, y - 2, x + 2, y + 2), fill=LIGHT)
    return c.finish()


def alarm(progress):
    c = Canvas()
    strength = (1 + math.sin(progress * math.tau)) / 2
    c.line([(64, 14), (64, 23)], blend(MID, CYAN, strength), 4)
    for points in ([(28, 25), (35, 32)], [(100, 25), (93, 32)],
                   [(16, 49), (27, 52)], [(112, 49), (101, 52)]):
        c.line(points, blend(MID, CYAN, strength), 3.5)
    c.rect((37, 36, 91, 92), fill=blend(BLUE, CYAN, strength), outline=NAVY, width=2.5, radius=24)
    c.rect((37, 63, 91, 93), fill=blend(BLUE, CYAN, strength))
    c.line([(37, 63), (37, 91)], NAVY, 2.5)
    c.line([(91, 63), (91, 91)], NAVY, 2.5)
    c.arc((46, 44, 82, 80), 186, 256, LIGHT, 3.5)
    c.rect((29, 90, 99, 102), fill=NAVY, outline=CYAN, width=2, radius=4)
    c.line([(36, 108), (92, 108)], BLUE, 3)
    return c.finish()


def radar(progress):
    c = Canvas()
    c.ellipse((16, 16, 112, 112), outline=NAVY, width=5)
    c.ellipse((17, 17, 111, 111), outline=CYAN, width=2)
    for radius in (16, 31):
        c.ellipse((64 - radius, 64 - radius, 64 + radius, 64 + radius), outline=MID, width=1.5)
    c.line([(18, 64), (110, 64)], MID, 1)
    c.line([(64, 18), (64, 110)], MID, 1)
    angle = progress * 360 - 90
    for tail in range(30, 0, -1):
        c.line([(64, 64), polar((64, 64), 44, angle - tail)],
               blend(NAVY, BLUE, (30 - tail) / 30), 1.6)
    c.line([(64, 64), polar((64, 64), 44, angle)], CYAN, 3)
    for x, y in ((82, 39), (39, 77), (81, 88)):
        c.ellipse((x - 3.5, y - 3.5, x + 3.5, y + 3.5), fill=LIGHT, outline=NAVY, width=1)
    c.ellipse((60, 60, 68, 68), fill=LIGHT, outline=CYAN, width=1)
    return c.finish()


def data_flow(progress):
    c = Canvas()
    for x in (15, 87):
        for y in (27, 49, 71):
            c.rect((x, y, x + 26, y + 18), fill=NAVY, outline=BLUE, width=2, radius=3)
            c.line([(x + 6, y + 6), (x + 14, y + 6)], LIGHT, 2)
            c.ellipse((x + 19, y + 10, x + 22, y + 13), fill=CYAN)
        c.line([(x + 5, 95), (x + 21, 95)], MID, 3)
    for y, direction in ((42, 1), (75, -1)):
        c.line([(45, y), (83, y)], MID, 2)
        tip = 82 if direction == 1 else 46
        c.line([(tip - direction * 6, y - 5), (tip, y), (tip - direction * 6, y + 5)], CYAN, 2.5)
        for index in range(2):
            distance = ((progress + index / 2) % 1) * 26
            x = 47 + distance if direction == 1 else 81 - distance
            c.rect((x - 3, y - 2, x + 3, y + 2), fill=LIGHT, radius=1)
    return c.finish()


def location_pulse(progress):
    c = Canvas()
    for offset in (0, 0.5):
        phase = (progress + offset) % 1
        rx, ry = 18 + 31 * phase, 5 + 12 * phase
        c.ellipse((64 - rx, 97 - ry, 64 + rx, 97 + ry),
                  outline=CYAN if phase < .55 else BLUE, width=2.5 * (1 - phase) + .25)
    outline = []
    for angle in range(0, 181, 6):
        outline.append(polar((64, 45), 29, angle + 180))
    outline.extend([(88, 63), (64, 98), (40, 63)])
    c.poly(outline, fill=BLUE, outline=NAVY, width=2.5)
    c.ellipse((49, 30, 79, 60), fill=NAVY, outline=CYAN, width=3)
    c.ellipse((58, 39, 70, 51), fill=LIGHT)
    return c.finish()


ICONS = [
    ("fan", "风机运行", "设备运行", fan),
    ("gear", "齿轮运行", "设备运行", gear),
    ("alarm", "告警闪烁", "状态告警", alarm),
    ("radar", "雷达扫描", "监测定位", radar),
    ("data-flow", "数据传输", "数据通信", data_flow),
    ("location-pulse", "定位脉冲", "监测定位", location_pulse),
]


def gif_frames(frames):
    # One global palette prevents color changes caused by per-frame quantization.
    sheet = Image.new("RGB", (SIZE * len(frames), SIZE), NAVY)
    for index, frame in enumerate(frames):
        sheet.paste(frame.convert("RGB"), (index * SIZE, 0))
    palette_image = sheet.quantize(colors=255, method=Image.Quantize.MEDIANCUT)
    palette = palette_image.getpalette()[:765] + [0, 0, 0]
    result = []
    for frame in frames:
        quantized = frame.convert("RGB").quantize(palette=palette_image, dither=Image.Dither.NONE)
        quantized.putpalette(palette)
        mask = frame.getchannel("A").point(lambda alpha: 255 if alpha < 128 else 0)
        quantized.paste(255, mask=mask)
        quantized.info["transparency"] = 255
        result.append(quantized)
    return result


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    entries = []
    samples = []
    for key, label, category, renderer in ICONS:
        frames = [renderer(index / FRAMES) for index in range(FRAMES)]
        frames[3].save(OUTPUT / f"{key}.png", optimize=True)
        indexed = gif_frames(frames)
        indexed[0].save(OUTPUT / f"{key}.gif", save_all=True, append_images=indexed[1:],
                        duration=DURATION, loop=0, disposal=2, transparency=255, optimize=False)
        with Image.open(OUTPUT / f"{key}.gif") as gif:
            assert gif.format == "GIF" and gif.size == (SIZE, SIZE)
            assert gif.n_frames >= 12 and gif.info["loop"] == 0
            hashes = set()
            for index in range(gif.n_frames):
                gif.seek(index)
                decoded = gif.convert("RGBA")
                assert decoded.getchannel("A").getextrema() == (0, 255)
                hashes.add(decoded.tobytes())
            assert len(hashes) >= 12, f"{key} requires visibly changing animation"
            count = gif.n_frames
        with Image.open(OUTPUT / f"{key}.png") as png:
            assert png.format == "PNG" and png.mode == "RGBA"
            assert png.getchannel("A").getextrema() == (0, 255)
        entries.append({"key": key, "label": label, "category": category,
                        "gif": f"{key}.gif", "png": f"{key}.png", "frames": count,
                        "width": SIZE, "height": SIZE, "frameDurationMs": DURATION})
        samples.append((key, frames[3]))
        print(f"{key}: {count} GIF frames, {SIZE}x{SIZE}, transparent; "
              f"GIF {(OUTPUT / f'{key}.gif').stat().st_size:,} bytes")
    (OUTPUT / "manifest.json").write_text(json.dumps({
        "version": 1, "source": "项目原创几何绘制，使用 Pillow 生成", "icons": entries,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    contact = Image.new("RGB", (6 * 160, 2 * 176), "#ffffff")
    contact_draw = ImageDraw.Draw(contact)
    for row, background in enumerate(("#f5f7fa", "#101e31")):
        contact_draw.rectangle((0, row * 176, contact.width, (row + 1) * 176), fill=background)
        for col, (key, frame) in enumerate(samples):
            contact.paste(frame, (col * 160 + 16, row * 176 + 8), frame)
            contact_draw.text((col * 160 + 16, row * 176 + 144), key,
                              fill="#52657a" if row == 0 else "#b1c6dd")
    contact.save(OUTPUT / "contact-sheet.png", optimize=True)


if __name__ == "__main__":
    main()
