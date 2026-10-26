#!/usr/bin/env python3
"""
Builds every image GitDrop needs from the master logo (art/gitdrop-logo.png).

    python3 tools/make_icons.py            # needs: pip install pillow numpy scipy

Generated files
  app/src/main/res/mipmap-*/ic_launcher.png            legacy launcher icon (Android 7.x)
  app/src/main/res/mipmap-*/ic_launcher_round.png      legacy round launcher icon
  app/src/main/res/mipmap-*/ic_launcher_foreground.png adaptive icon foreground (Android 8+)
  app/src/main/res/mipmap-*/ic_launcher_monochrome.png themed icon layer (Android 13+)
  app/src/main/res/drawable-nodpi/logo_mark.png        droplet only, transparent (in-app header)
  app/src/main/res/drawable-nodpi/logo_full.png        droplet + wordmark, transparent (start screen)
  docs/logo.png, docs/icon-512.png                     README banner and store-size icon

The droplet is cut out of the dark background with a soft edge ("colour to alpha"), and the
dark Git glyph inside the droplet is kept opaque, so the artwork looks right on any background.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy import ndimage

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "art", "gitdrop-logo.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

# Colours sampled from the logo artwork.
BG_TOP = (10, 34, 80)      # launcher background, top
BG_BOTTOM = (4, 10, 28)    # launcher background, bottom
BG_SAMPLE = np.array([1.0, 8.0, 22.0])  # typical dark background around the droplet

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def load_source():
    return Image.open(SRC).convert("RGB")


def cutout(img, box, keep_holes):
    """Returns an RGBA image of `box` with the dark background removed.

    keep_holes=True : dark areas fully enclosed by the artwork (the Git glyph) stay opaque.
    keep_holes=False: every dark pixel becomes transparent (wordmark letter counters).
    """
    crop = img.crop(box)
    a = np.asarray(crop).astype(np.float32)
    m = a.max(axis=2)

    lo, hi = 70.0, 190.0
    soft = np.clip((m - lo) / (hi - lo), 0.0, 1.0)

    dark = m < 110  # background incl. the soft corner glow; the logo itself is far brighter
    labels, _ = ndimage.label(dark)
    # The crop border is all background, so the label at the corner is the outer background.
    outer = labels == labels[0, 0]
    # Sanity: the whole border must belong to the outer region.
    border = np.zeros_like(dark)
    border[0, :] = border[-1, :] = True
    border[:, 0] = border[:, -1] = True
    if not np.all(outer[border]):
        raise RuntimeError("crop border is not pure background; adjust the crop box")

    if keep_holes:
        # Pixels near the outer background get the soft ramp; everything deeper is opaque.
        near = ndimage.binary_dilation(outer, iterations=4)
        alpha = np.where(near, soft, 1.0)
        alpha[outer] = 0.0
    else:
        alpha = soft
        alpha[outer] = 0.0

    # Remove the background's contribution from partly transparent edge pixels.
    safe = np.maximum(alpha, 1e-3)[..., None]
    rgb = (a - (1.0 - alpha[..., None]) * BG_SAMPLE) / safe
    rgb = np.where(alpha[..., None] >= 0.999, a, rgb)
    rgb = np.clip(rgb, 0, 255)

    out = np.dstack([rgb, alpha * 255.0]).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def trim(img, pad=0):
    """Crops transparent margins, then adds `pad` transparent pixels on every side."""
    bbox = img.getchannel("A").point(lambda v: 255 if v > 8 else 0).getbbox()
    img = img.crop(bbox)
    if pad:
        canvas = Image.new("RGBA", (img.width + 2 * pad, img.height + 2 * pad), (0, 0, 0, 0))
        canvas.paste(img, (pad, pad))
        img = canvas
    return img


def monochrome_from(mark_rgba):
    """White silhouette whose alpha follows the droplet's brightness (glyph becomes a cut-out)."""
    a = np.asarray(mark_rgba).astype(np.float32)
    m = a[..., :3].max(axis=2)
    ramp = np.clip((m - 70.0) / (150.0 - 70.0), 0.0, 1.0)
    alpha = ramp * (a[..., 3] / 255.0)
    out = np.zeros(a.shape, np.uint8)
    out[..., :3] = 255
    out[..., 3] = (alpha * 255).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def gradient_square(size, top=BG_TOP, bottom=BG_BOTTOM):
    t = np.linspace(0.0, 1.0, size)[:, None]
    top = np.array(top, np.float32)
    bottom = np.array(bottom, np.float32)
    col = top[None, :] * (1 - t) + bottom[None, :] * t  # (size, 3)
    arr = np.repeat(col[:, None, :], size, axis=1)
    arr = np.dstack([arr, np.full((size, size), 255, np.float32)])
    return Image.fromarray(arr.astype(np.uint8), "RGBA")


def place(layer, canvas_px, target_height_px, dy_px=0):
    """Scales `layer` to `target_height_px` and centres it on a transparent square canvas."""
    ratio = target_height_px / layer.height
    w = max(1, round(layer.width * ratio))
    h = max(1, round(target_height_px))
    scaled = layer.resize((w, h), Image.LANCZOS)
    canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    canvas.alpha_composite(scaled, ((canvas_px - w) // 2, (canvas_px - h) // 2 + dy_px))
    return canvas


def rounded_mask(size, radius_frac):
    scale = 4
    m = Image.new("L", (size * scale, size * scale), 0)
    d = ImageDraw.Draw(m)
    r = int(size * scale * radius_frac)
    d.rounded_rectangle([0, 0, size * scale - 1, size * scale - 1], radius=r, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def circle_mask(size):
    scale = 4
    m = Image.new("L", (size * scale, size * scale), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size * scale - 1, size * scale - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def save(img, path, optimize=True):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG", optimize=optimize)
    print("wrote", os.path.relpath(path, ROOT), img.size)


def main():
    if not os.path.exists(SRC):
        sys.exit("missing " + SRC)
    src = load_source()

    # Droplet only: the crop stops above the wordmark (which starts at y=815).
    mark = trim(cutout(src, (358, 144, 895, 807), keep_holes=True))

    # Droplet + wordmark. The Git glyph inside the droplet stays opaque, while the counters of
    # the wordmark letters (D, o, p, G) become transparent like the rest of the background.
    full_raw = np.asarray(cutout(src, (229, 144, 1041, 1050), keep_holes=True)).copy()
    word_raw = np.asarray(cutout(src, (229, 805, 1041, 1050), keep_holes=False))
    top = 805 - 144
    full_raw[top:, :, :] = word_raw
    full = trim(Image.fromarray(full_raw, "RGBA"))

    mono_source = mark

    # ---- in-app artwork ------------------------------------------------------------------
    # Shown at 28dp in the top bars: 128px covers every density (xxxhdpi needs 112px) without heavy downscaling.
    mark_app = mark.resize((round(mark.width * 128 / mark.height), 128), Image.LANCZOS)
    save(mark_app, os.path.join(RES, "drawable-nodpi", "logo_mark.png"))
    full_app = full.resize((round(full.width * 480 / full.height), 480), Image.LANCZOS)
    save(full_app, os.path.join(RES, "drawable-nodpi", "logo_full.png"))

    # ---- launcher icons ------------------------------------------------------------------
    master = 1296  # 12 x 108, scaled down for each density
    fg_master = place(mark, master, int(master * 62 / 108), dy_px=int(master * 0.004))
    mono_master = place(monochrome_from(mono_source), master, int(master * 58 / 108))

    for name, scale in DENSITIES.items():
        fg_px = int(round(108 * scale))
        save(fg_master.resize((fg_px, fg_px), Image.LANCZOS),
             os.path.join(RES, "mipmap-" + name, "ic_launcher_foreground.png"))
        save(mono_master.resize((fg_px, fg_px), Image.LANCZOS),
             os.path.join(RES, "mipmap-" + name, "ic_launcher_monochrome.png"))

        px = int(round(48 * scale))
        big = 1024
        base = gradient_square(big)
        base.alpha_composite(place(mark, big, int(big * 0.70), dy_px=int(big * 0.004)))
        square = base.resize((px, px), Image.LANCZOS)
        sq = square.copy()
        sq.putalpha(rounded_mask(px, 0.22))
        save(sq, os.path.join(RES, "mipmap-" + name, "ic_launcher.png"))

        rbase = gradient_square(big)
        rbase.alpha_composite(place(mark, big, int(big * 0.62), dy_px=int(big * 0.004)))
        rd = rbase.resize((px, px), Image.LANCZOS)
        rd.putalpha(circle_mask(px))
        save(rd, os.path.join(RES, "mipmap-" + name, "ic_launcher_round.png"))

    # ---- documentation art ---------------------------------------------------------------
    banner = src.resize((640, 640), Image.LANCZOS)
    save(banner, os.path.join(ROOT, "docs", "logo.png"))
    store = gradient_square(1024)
    store.alpha_composite(place(mark, 1024, int(1024 * 0.66), dy_px=int(1024 * 0.004)))
    save(store.resize((512, 512), Image.LANCZOS).convert("RGB"), os.path.join(ROOT, "docs", "icon-512.png"))


if __name__ == "__main__":
    main()
