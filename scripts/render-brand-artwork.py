#!/usr/bin/env python3
"""Render the brand artwork at the sizes we ship.

Source of truth: docs/brand/launcher-icon-512x512.png — the t-shirt +
sun icon, rendered as opaque RGB on near-white. None of the design
itself uses white, so a white-matte unmultiply (alpha = 255 - min(R,G,B),
white removed from the remaining color) recovers RGBA that composites
back to the same pixel values on a white background.

Outputs:

  - app/src/main/res/mipmap-{m,h,x,xx,xxx}dpi/ic_launcher_foreground.png
    Adaptive-icon foreground at each density. Used on top of the white
    @drawable/ic_launcher_background, so the unmultiply round-trips
    exactly to the source palette.
  - docs/play-store/feature-graphic.png  (1024x500, white background)
  - app/src/main/res/drawable-xhdpi/tv_banner.png  (320x180, white
    background, referenced by android:banner)

Banner composition: white background, logo on the left, "ClothesCast"
wordmark on the right with "Cast" picked out in the sun yellow
(#E9B94B, sampled from the launcher icon so the two yellows read as
one). Entire banner is rendered at 3x supersample and Lanczos-
downscaled in one pass so small details — the wordmark's RAQM-shaped
kerning pairs ("th", "sC") and the t-shirt outline — stay sharp.

Dependencies (this script only — nothing here ships in the APK):
  pip install Pillow numpy
plus Roboto Bold installed on the host (see FONT_CANDIDATES below).
"""

import os
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "docs/brand/launcher-icon-512x512.png"

# Roboto Bold isn't vendored in the repo; we look it up on the host.
# On Debian / Ubuntu the `fonts-roboto` apt package installs it under
# /usr/share/fonts/truetype/roboto/. The macOS path is included for
# devs running this locally. Override with $CLOTHESCAST_ROBOTO_BOLD if
# your install lives elsewhere.
FONT_CANDIDATES = (
    "/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Bold.ttf",
    "/usr/share/fonts/truetype/roboto/Roboto-Bold.ttf",
    "/Library/Fonts/Roboto-Bold.ttf",
    "/System/Library/Fonts/Supplemental/Roboto-Bold.ttf",
)


def find_font() -> str:
    override = os.environ.get("CLOTHESCAST_ROBOTO_BOLD")
    if override:
        if Path(override).exists():
            return override
        raise SystemExit(
            f"CLOTHESCAST_ROBOTO_BOLD={override!r} does not exist."
        )
    for path in FONT_CANDIDATES:
        if Path(path).exists():
            return path
    raise SystemExit(
        "Roboto Bold not found. Install it (e.g. `sudo apt install "
        "fonts-roboto` on Debian/Ubuntu, or download Roboto-Bold.ttf "
        "from https://fonts.google.com/specimen/Roboto) and re-run, "
        "or point $CLOTHESCAST_ROBOTO_BOLD at the .ttf."
    )

BG = (255, 255, 255)
TEXT = (30, 35, 48)
YELLOW = (233, 185, 75)

LAUNCHER_DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

# Fraction of the 108dp adaptive-icon canvas the actual artwork should
# occupy. Matched to the previous launcher icon's footprint (240/432 ≈
# 55.6% wide × ~48.8% tall in the xxxhdpi PNG), so visual presence on
# the home screen is unchanged from what's shipping. That's
# comfortably inside both the 66dp safe circle and the 72dp
# recommended-visible square — no mask will clip the sleeves.
LAUNCHER_ARTWORK_FRACTION = 240 / 432


@dataclass(frozen=True)
class Banner:
    out: Path
    width: int
    height: int
    logo_height: int
    font_size: int
    gap: int


BANNERS = (
    Banner(
        out=ROOT / "docs/play-store/feature-graphic.png",
        width=1024,
        height=500,
        logo_height=320,
        font_size=110,
        gap=40,
    ),
    Banner(
        out=ROOT / "app/src/main/res/drawable-xhdpi/tv_banner.png",
        width=320,
        height=180,
        logo_height=140,
        font_size=26,
        gap=10,
    ),
)

SUPERSAMPLE = 3
TEXT_FEATURES = ["+kern", "+liga"]


def load_logo_rgba() -> Image.Image:
    """Source PNG → RGBA, cropped to the real artwork bbox.

    White-matte unmultiply (alpha = 255 - min(R,G,B), white removed
    from the remaining color) recovers RGBA that composites back to
    the source pixels on a white background. Crop is computed from
    the RGB source (min(R,G,B) < 200) — using `alpha > 0` on the
    unmultiplied result would keep the entire canvas, since the
    near-white margin has whiteness 254 → alpha 1, not 0.
    """
    rgb = np.asarray(Image.open(SOURCE).convert("RGB"), dtype=np.float32)
    artwork = rgb.min(axis=2) < 200
    ys, xs = np.where(artwork)
    if len(xs) == 0:
        raise SystemExit(f"{SOURCE} appears to be blank/white")
    x0, x1 = int(xs.min()), int(xs.max()) + 1
    y0, y1 = int(ys.min()), int(ys.max()) + 1
    rgb = rgb[y0:y1, x0:x1]

    whiteness = rgb.min(axis=2)
    alpha = 255.0 - whiteness
    safe = np.maximum(alpha, 1.0)[..., None]
    color = (rgb - 255.0 + alpha[..., None]) * 255.0 / safe
    color = np.clip(color, 0.0, 255.0)
    rgba = np.dstack([color, alpha]).astype(np.uint8)
    return Image.fromarray(rgba, mode="RGBA")


def render_launcher_icons(logo: Image.Image) -> None:
    # Adaptive-icon foreground is 108dp square; the actual artwork sits
    # inside the central safe region or it'll get clipped by the
    # launcher mask. Scale-to-fit the logo into LAUNCHER_ARTWORK_FRACTION
    # of the canvas and center it on transparent padding.
    src_w, src_h = logo.size
    xxxhdpi_out = None
    for density, size in LAUNCHER_DENSITIES.items():
        out = ROOT / f"app/src/main/res/mipmap-{density}/ic_launcher_foreground.png"
        target = int(size * LAUNCHER_ARTWORK_FRACTION)
        scale = min(target / src_w, target / src_h)
        w = max(1, int(round(src_w * scale)))
        h = max(1, int(round(src_h * scale)))
        scaled = logo.resize((w, h), Image.LANCZOS)
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        canvas.alpha_composite(scaled, ((size - w) // 2, (size - h) // 2))
        out.parent.mkdir(parents=True, exist_ok=True)
        canvas.save(out, "PNG", optimize=True)
        print(f"Wrote {out.relative_to(ROOT)} ({size}x{size}, artwork {w}x{h})")
        if density == "xxxhdpi":
            xxxhdpi_out = out

    # Density-pinned copy for LauncherIcon{,Dev}Preview / snapshot tests.
    # Same bytes as the xxxhdpi mipmap, just at a path Robolectric's
    # resource resolver can't ambiguate between density buckets — see
    # the docstring in LauncherIconPreviews.kt for why.
    pinned = ROOT / "app/src/main/res/drawable-nodpi/ic_launcher_foreground_pinned.png"
    pinned.parent.mkdir(parents=True, exist_ok=True)
    pinned.write_bytes(xxxhdpi_out.read_bytes())
    print(f"Wrote {pinned.relative_to(ROOT)} (copy of mipmap-xxxhdpi)")


def render_banner(banner: Banner, logo_src: Image.Image) -> None:
    # Render the whole banner at SUPERSAMPLE-x and Lanczos-downscale in
    # one pass. Single pass keeps the t-shirt outline crisp at small
    # sizes (the TV banner especially — a direct 1254→140 logo resize
    # softens noticeably).
    ss = SUPERSAMPLE
    W = banner.width * ss
    H = banner.height * ss
    canvas = Image.new("RGBA", (W, H), BG + (255,))

    logo_h = banner.logo_height * ss
    scale = logo_h / logo_src.height
    logo_w = int(round(logo_src.width * scale))
    logo = logo_src.resize((logo_w, logo_h), Image.LANCZOS)

    font = ImageFont.truetype(
        find_font(), banner.font_size * ss, layout_engine=ImageFont.Layout.RAQM
    )
    full_w = int(font.getlength("ClothesCast", features=TEXT_FEATURES))
    cast_w = int(font.getlength("Cast", features=TEXT_FEATURES))
    cast_offset = full_w - cast_w
    bbox = font.getbbox("ClothesCast", features=TEXT_FEATURES)
    text_top = bbox[1]
    text_h = bbox[3] - bbox[1]

    gap = banner.gap * ss
    total_w = logo_w + gap + full_w
    start_x = (W - total_w) // 2

    logo_x = start_x
    logo_y = (H - logo_h) // 2
    canvas.alpha_composite(logo, (logo_x, logo_y))

    text_x = logo_x + logo_w + gap
    text_y = (H - text_h) // 2 - text_top

    draw = ImageDraw.Draw(canvas)
    draw.text(
        (text_x, text_y), "Clothes",
        font=font, fill=TEXT, features=TEXT_FEATURES,
    )
    draw.text(
        (text_x + cast_offset, text_y), "Cast",
        font=font, fill=YELLOW, features=TEXT_FEATURES,
    )

    final = canvas.resize((banner.width, banner.height), Image.LANCZOS)
    banner.out.parent.mkdir(parents=True, exist_ok=True)
    final.convert("RGB").save(banner.out, "PNG", optimize=True)
    print(f"Wrote {banner.out.relative_to(ROOT)} ({banner.width}x{banner.height})")


def main() -> None:
    logo = load_logo_rgba()
    render_launcher_icons(logo)
    for banner in BANNERS:
        render_banner(banner, logo)


if __name__ == "__main__":
    main()
