#!/usr/bin/env python3
"""Regenerate Sensefield raster brand assets from the transparent mark."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
BRAND = (240, 198, 178)  # #F0C6B2
SOCIAL_BG = (251, 247, 241)
RESAMPLING = Image.Resampling.LANCZOS


def mark_crop(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    alpha = rgba.getchannel("A")
    bounds = alpha.getbbox()
    if bounds is None:
        raise ValueError("transparent brand mark has no visible pixels")
    return rgba.crop(bounds)


def fit_width(mark: Image.Image, width: int) -> Image.Image:
    height = round(mark.height * width / mark.width)
    return mark.resize((width, height), RESAMPLING)


def centered_mark(canvas: Image.Image, mark: Image.Image) -> None:
    canvas.alpha_composite(mark, ((canvas.width - mark.width) // 2,
                                  (canvas.height - mark.height) // 2))


def rounded_tile(size: int, color: tuple[int, int, int], radius: int) -> Image.Image:
    scale = 4
    mask = Image.new("L", (size * scale, size * scale), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size * scale - 1, size * scale - 1),
        radius=radius * scale,
        fill=255,
    )
    mask = mask.resize((size, size), RESAMPLING)
    tile = Image.new("RGBA", (size, size), (*color, 0))
    tile.putalpha(mask)
    return tile


def icon_mask(size: int, shape: str) -> Image.Image:
    scale = 4
    mask = Image.new("L", (size * scale, size * scale), 0)
    draw = ImageDraw.Draw(mask)
    bounds = (0, 0, size * scale - 1, size * scale - 1)
    if shape == "circle":
        draw.ellipse(bounds, fill=255)
    else:
        draw.rounded_rectangle(bounds, radius=round(size * 0.22 * scale), fill=255)
    return mask.resize((size, size), Image.Resampling.BOX)


def main() -> None:
    assets = ROOT / "assets"
    android_res = ROOT / "android/app/src/main/res"
    mark_path = assets / "sensefield-mark.png"
    artwork = mark_crop(Image.open(mark_path))

    # Keep a useful, centered transparent lockup as the canonical source mark.
    canonical = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    centered_mark(canonical, fit_width(artwork, 700))
    canonical.save(mark_path, optimize=True)
    artwork = mark_crop(canonical)

    # Flat warm background, with a larger and optically centered symbol.
    app_icon = Image.new("RGBA", (1024, 1024), (*BRAND, 255))
    centered_mark(app_icon, fit_width(artwork, round(1024 * 0.69)))
    app_icon.save(assets / "sensefield-icon.png", optimize=True)

    # Tight crop with a small safety margin for compact 44dp UI logo tiles.
    compact = fit_width(artwork, 564)
    compact_canvas = Image.new("RGBA", (600, compact.height + 28), (0, 0, 0, 0))
    compact_canvas.alpha_composite(compact, (18, 14))
    compact_canvas.save(android_res / "drawable-nodpi/sensefield_mark_compact.png",
                        optimize=True)

    # Adaptive foreground has no background pixels. Android launchers crop the
    # 108dp layer down to a roughly 72dp mask; keep the complete mark inside
    # the 66dp safe zone so vendor launchers do not cut off its side accents.
    foreground = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    centered_mark(foreground, fit_width(artwork, 264))
    foreground.save(android_res / "drawable-nodpi/sensefield_foreground.png",
                    optimize=True)

    # Legacy launcher PNGs are precomposed over the same flat solid color.
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                          ("xxhdpi", 144), ("xxxhdpi", 192)):
        icon = Image.new("RGBA", (size, size), (*BRAND, 255))
        centered_mark(icon, fit_width(artwork, round(size * 0.68)))
        mipmap = android_res / f"mipmap-{density}"
        rounded_square = icon.copy()
        rounded_square.putalpha(icon_mask(size, "rounded-square"))
        rounded_square.save(mipmap / "ic_launcher.png", optimize=True)
        round_icon = icon.copy()
        round_icon.putalpha(icon_mask(size, "circle"))
        round_icon.save(mipmap / "ic_launcher_round.png", optimize=True)

    # Refresh the tile and its matching accent rule in the existing social card.
    social_path = assets / "sensefield-social-preview.png"
    social = Image.open(social_path).convert("RGBA")
    tile_size = 356
    tile = rounded_tile(tile_size, BRAND, 56)
    centered_mark(tile, fit_width(artwork, round(tile_size * 0.66)))
    social.paste((*SOCIAL_BG, 255), (96, 142, 96 + tile_size, 142 + tile_size))
    social.alpha_composite(tile, (96, 142))
    accent = Image.new("RGBA", social.size, (0, 0, 0, 0))
    ImageDraw.Draw(accent).rounded_rectangle((562, 302, 643, 310), radius=4,
                                             fill=(*BRAND, 255))
    social.alpha_composite(accent)
    social.save(social_path, optimize=True)

    web_mark = ROOT / "python/mapassist/annotation_web/sensefield-mark.png"
    canonical.save(web_mark, optimize=True)
    compact_web_mark = ROOT / "python/mapassist/annotation_web/sensefield-mark-compact.png"
    compact_canvas.save(compact_web_mark, optimize=True)


if __name__ == "__main__":
    main()
