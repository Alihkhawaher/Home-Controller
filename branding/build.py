#!/usr/bin/env python3
"""Build the Home Controller icon from one set of shapes.

Writes, from the geometry below:
  branding/icon.svg            master icon, 512 x 512, full-bleed square (what a launcher shows)
  branding/icon-rounded.svg    the same in a rounded square, for the README and web pages
  app/src/main/res/drawable/ic_launcher_foreground.xml   adaptive icon foreground
  app/src/main/res/drawable/ic_launcher_monochrome.xml   Android 13+ themed icon

With --render it also renders the PNGs (icon-512, feature graphic, social preview) with a headless
Chromium-based browser (Edge or Chrome; set BROWSER to its path if it is not found) and copies the
store images into fastlane/.

Coordinates use the adaptive-icon canvas: 108 x 108 units, visible area 72 x 72 centred at 54,54,
safe zone a circle of radius 33. Run from anywhere: python branding/build.py [--render]
"""
import math
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BRAND = ROOT / "branding"
RES = ROOT / "app" / "src" / "main" / "res"

# Palette
NAVY = "#0E3A4F"      # background
WHITE = "#FFFFFF"     # house
CYAN = "#5CE1E6"      # active part of the dial
CYAN_DIM = "#2C6E80"  # rest of the dial
AMBER = "#FFB547"     # the set point / node

STROKE = 5.0
DIAL = dict(cx=54.0, cy=61.5, r=13.0, start=135.0, end=405.0, knob=315.0)
KNOB_R = 4.4
KNOB_GAP = 16.0       # degrees of track hidden either side of the knob (colour icon)
KNOB_HALO = 2.4       # background-coloured ring that lifts the knob off the track (colour icon)
# The monochrome (themed) icon has no background colour to cut with, so the knob gets real space instead.
MONO_KNOB_R = 4.0
MONO_KNOB_GAP = 36.0
HUB_R = 3.0
HOUSE = [(54, 27), (76, 45.5), (76, 79), (32, 79), (32, 45.5)]
HOUSE_RADII = [5, 4.5, 7.5, 7.5, 4.5]
SAFE_R = 33.0


def n(v: float) -> str:
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def rounded_polygon(pts, radii):
    """Closed path through pts with each corner rounded by a circular arc (clockwise on screen)."""
    segs = []
    for i, V in enumerate(pts):
        P, N, r = pts[i - 1], pts[(i + 1) % len(pts)], radii[i]
        u1 = ((P[0] - V[0]), (P[1] - V[1]))
        u2 = ((N[0] - V[0]), (N[1] - V[1]))
        l1, l2 = math.hypot(*u1), math.hypot(*u2)
        u1, u2 = (u1[0] / l1, u1[1] / l1), (u2[0] / l2, u2[1] / l2)
        theta = math.acos(max(-1.0, min(1.0, u1[0] * u2[0] + u1[1] * u2[1])))
        d = r / math.tan(theta / 2)
        segs.append(((V[0] + u1[0] * d, V[1] + u1[1] * d), (V[0] + u2[0] * d, V[1] + u2[1] * d), r))
    out = f"M{n(segs[-1][1][0])},{n(segs[-1][1][1])}"
    for t1, t2, r in segs:
        out += f" L{n(t1[0])},{n(t1[1])} A{n(r)},{n(r)} 0 0 1 {n(t2[0])},{n(t2[1])}"
    return out + " Z"


def polar(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy + r * math.sin(a)


def arc(cx, cy, r, a0, a1):
    x0, y0 = polar(cx, cy, r, a0)
    x1, y1 = polar(cx, cy, r, a1)
    large = 1 if (a1 - a0) % 360 > 180 else 0
    return f"M{n(x0)},{n(y0)} A{n(r)},{n(r)} 0 {large} 1 {n(x1)},{n(y1)}"


def disc(cx, cy, r):
    return f"M{n(cx - r)},{n(cy)} A{n(r)},{n(r)} 0 1 0 {n(cx + r)},{n(cy)} A{n(r)},{n(r)} 0 1 0 {n(cx - r)},{n(cy)} Z"


def shapes(mono=False):
    """(kind, path, colour, alpha) in drawing order. kind is 'stroke' or 'fill'.

    Colour: house, track (active cyan, rest dim), a navy halo, the amber knob, the hub.
    Monochrome: one colour; the rest of the track is drawn at reduced alpha and the knob stands apart.
    """
    d = DIAL
    gap, kr = (MONO_KNOB_GAP, MONO_KNOB_R) if mono else (KNOB_GAP, KNOB_R)
    kx, ky = polar(d["cx"], d["cy"], d["r"], d["knob"])
    out = [
        ("stroke", rounded_polygon(HOUSE, HOUSE_RADII), WHITE, 1.0),
        ("stroke", arc(d["cx"], d["cy"], d["r"], d["start"], d["knob"] - gap), CYAN, 1.0),
        ("stroke", arc(d["cx"], d["cy"], d["r"], d["knob"] + gap, d["end"]), CYAN_DIM, 0.45 if mono else 1.0),
    ]
    if not mono:
        out.append(("fill", disc(kx, ky, kr + KNOB_HALO), NAVY, 1.0))
    out += [
        ("fill", disc(kx, ky, kr), AMBER, 1.0),
        ("fill", disc(d["cx"], d["cy"], HUB_R), WHITE, 1.0),
    ]
    if mono:
        out = [(k, p, WHITE, a) for k, p, _, a in out]
    return out


def check_safe_zone():
    """Fail loudly if any part of the artwork leaves the 66 dp safe zone."""
    worst = 0.0
    # house corners: centre of each corner arc plus radius plus half the stroke
    for i, V in enumerate(HOUSE):
        P, N, r = HOUSE[i - 1], HOUSE[(i + 1) % len(HOUSE)], HOUSE_RADII[i]
        u1 = ((P[0] - V[0]), (P[1] - V[1])); l1 = math.hypot(*u1); u1 = (u1[0] / l1, u1[1] / l1)
        u2 = ((N[0] - V[0]), (N[1] - V[1])); l2 = math.hypot(*u2); u2 = (u2[0] / l2, u2[1] / l2)
        bis = (u1[0] + u2[0], u1[1] + u2[1]); lb = math.hypot(*bis); bis = (bis[0] / lb, bis[1] / lb)
        theta = math.acos(u1[0] * u2[0] + u1[1] * u2[1])
        c = (V[0] + bis[0] * r / math.sin(theta / 2), V[1] + bis[1] * r / math.sin(theta / 2))
        worst = max(worst, math.hypot(c[0] - 54, c[1] - 54) + r + STROKE / 2)
    if worst > SAFE_R:
        sys.exit(f"artwork reaches {worst:.2f} units from the centre; safe zone is {SAFE_R}")
    return worst


def svg_layers(mono=False):
    out = []
    for kind, path, c, alpha in shapes(mono):
        op = f' opacity="{n(alpha)}"' if alpha < 1 else ""
        if kind == "stroke":
            out.append(f'  <path d="{path}" fill="none" stroke="{c}" stroke-width="{n(STROKE)}" '
                       f'stroke-linecap="round" stroke-linejoin="round"{op}/>')
        else:
            out.append(f'  <path d="{path}" fill="{c}"{op}/>')
    return "\n".join(out)


def write(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    print("wrote", path.relative_to(ROOT))


def build_svgs():
    head = '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="18 18 72 72">\n'
    write(BRAND / "icon.svg", head
          + "  <title>Home Controller</title>\n"
          + f'  <rect x="0" y="0" width="108" height="108" fill="{NAVY}"/>\n'
          + svg_layers() + "\n</svg>\n")
    write(BRAND / "icon-rounded.svg", head
          + "  <title>Home Controller</title>\n"
          + f'  <rect x="18" y="18" width="72" height="72" rx="17" fill="{NAVY}"/>\n'
          + svg_layers() + "\n</svg>\n")


def vector(mono: bool) -> str:
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Generated by branding/build.py; edit the geometry there, not here. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="108dp"',
        '    android:height="108dp"',
        '    android:viewportWidth="108"',
        '    android:viewportHeight="108">',
    ]
    for kind, path, c, alpha in shapes(mono):
        lines.append("    <path")
        if kind == "stroke":
            lines += [
                f'        android:strokeColor="{c}"',
                f'        android:strokeWidth="{n(STROKE)}"',
                '        android:strokeLineCap="round"',
                '        android:strokeLineJoin="round"',
            ]
            if alpha < 1:
                lines.append(f'        android:strokeAlpha="{n(alpha)}"')
        else:
            lines.append(f'        android:fillColor="{c}"')
            if alpha < 1:
                lines.append(f'        android:fillAlpha="{n(alpha)}"')
        lines.append(f'        android:pathData="{path}" />')
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def build_vectors():
    write(RES / "drawable" / "ic_launcher_foreground.xml", vector(mono=False))
    write(RES / "drawable" / "ic_launcher_monochrome.xml", vector(mono=True))


def browser() -> str:
    candidates = [
        os.environ.get("BROWSER", ""),
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        shutil.which("chromium") or "", shutil.which("chromium-browser") or "",
        shutil.which("google-chrome") or "", shutil.which("microsoft-edge") or "",
    ]
    for c in candidates:
        if c and Path(c).exists():
            return c
    sys.exit("no Chromium-based browser found; set BROWSER to its path")


def shot(page: Path, out: Path, w: int, h: int):
    subprocess.run([
        browser(), "--headless", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
        f"--window-size={w},{h}", f"--screenshot={out}", page.resolve().as_uri(),
    ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print("rendered", out.relative_to(ROOT), f"{w}x{h}")


def to_rgba(png: Path):
    """Google Play wants the store icon as a 32-bit PNG. Uses Pillow when it is installed."""
    try:
        from PIL import Image
    except ImportError:
        print("Pillow not installed: icon-512.png left as 24-bit RGB")
        return
    with Image.open(png) as im:
        im.convert("RGBA").save(png, optimize=True)


def render():
    src = BRAND / "src"
    shot(src / "icon-512.html", BRAND / "icon-512.png", 512, 512)
    to_rgba(BRAND / "icon-512.png")
    shot(src / "feature-graphic.html", BRAND / "feature-graphic.png", 1024, 500)
    shot(src / "social-preview.html", BRAND / "social-preview.png", 1280, 640)
    images = ROOT / "fastlane" / "metadata" / "android" / "en-US" / "images"
    images.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(BRAND / "icon-512.png", images / "icon.png")
    shutil.copyfile(BRAND / "feature-graphic.png", images / "featureGraphic.png")
    print("copied store images to", images.relative_to(ROOT))


if __name__ == "__main__":
    print(f"safe zone ok: artwork within {check_safe_zone():.2f} of {SAFE_R} units")
    build_svgs()
    build_vectors()
    if "--render" in sys.argv:
        render()
