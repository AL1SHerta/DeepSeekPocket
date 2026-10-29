"""从用户提供的白底图标原图生成 Android 启动图标资源。

原图是「蓝底圆角方块 + 白色鲸鱼」，`#3367F6` 为底色。这里做两件事：
1. 把蓝底全部转成透明，只留白色鲸鱼（鲸鱼身上的蓝眼睛、硬币上的蓝菱形因此变成镂空）；
2. 分别输出 adaptive icon 的 foreground 层、monochrome（主题图标）层和传统位图图标。
"""

from pathlib import Path

from PIL import Image, ImageDraw

SRC = Path(r"C:\Users\Silver Wolf\.dsh\attachments\v1\objects\4d\4dcf821387e7844138d55ec32ac08fc5a873947b911da1143d6695a654af002a")
RES = Path(r"D:\Projects\DeepSeekPocket\app\src\main\res")
PREVIEW = Path(r"D:\Projects\DeepSeekPocket\docs\screenshots\icon_preview.png")

BACKGROUND = (51, 103, 246)          # 原图底色 #3367F6
ADAPTIVE_DP = 108                     # adaptive icon 画布
FOREGROUND_RATIO = 0.64               # 图案占画布的比例（安全区 66%，略微外扩一点点）
LEGACY_RATIO = 0.72                   # 传统图标里图案占方块的比例

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def extract_whale(image: Image.Image) -> Image.Image:
    """把蓝底抹成透明，返回白鲸鱼的 RGBA 图（已裁到内容边界）。"""
    width, height = image.size
    pixels = image.load()

    # 圆角方块外面的白底和鲸鱼的白是同一个颜色，只能靠连通性区分：
    # 从四个角洪泛填充成洋红，剩下的白色才是鲸鱼。
    probe = image.convert("RGB")
    sentinel = (255, 0, 255)
    for corner in ((0, 0), (width - 1, 0), (0, height - 1), (width - 1, height - 1)):
        ImageDraw.floodfill(probe, corner, sentinel, thresh=40)
    probe_pixels = probe.load()

    # 方块自身的抗锯齿边缘（蓝→白过渡）也是一圈淡白，必须靠几何 mask 切掉；
    # 内缩 8px 是为了把这圈过渡带整个排除在外（鲸鱼离边框还有 60px 以上，切不到）。
    (bx0, by0), (bx1, by1) = (182, 182), (1072, 1076)
    radius = round(min(bx1 - bx0, by1 - by0) * 0.217)
    inset = 8
    inside = Image.new("L", (width, height), 0)
    ImageDraw.Draw(inside).rounded_rectangle(
        (bx0 + inset, by0 + inset, bx1 - inset, by1 - inset),
        radius=radius - inset,
        fill=255,
    )
    inside_pixels = inside.load()

    out = Image.new("RGBA", (width, height), (255, 255, 255, 0))
    out_pixels = out.load()
    base_r = BACKGROUND[0]
    span = 255 - base_r

    for y in range(height):
        for x in range(width):
            if probe_pixels[x, y] == sentinel or not inside_pixels[x, y]:
                continue
            r = pixels[x, y][0]
            # 红通道差值最大（蓝底 51 vs 白 255），用它还原抗锯齿边缘的 alpha。
            # 阈值取 0.82 是因为方块边框那圈过渡像素最亮也只到 r≈200（alpha≈0.73），
            # 而鲸鱼本体是 r=254，中间留出安全间隙。
            alpha = (r - base_r) / span
            if alpha < 0.82:
                continue
            out_pixels[x, y] = (255, 255, 255, int(round(alpha * 255)))

    return out.crop(out.getbbox())


def place_on_canvas(art: Image.Image, canvas: int, ratio: float) -> Image.Image:
    """把图案按比例缩放后居中放到正方形透明画布上。"""
    target = int(canvas * ratio)
    w, h = art.size
    scale = min(target / w, target / h)
    resized = art.resize(
        (max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS,
    )
    layer = Image.new("RGBA", (canvas, canvas), (255, 255, 255, 0))
    layer.paste(resized, ((canvas - resized.width) // 2, (canvas - resized.height) // 2), resized)
    return layer


def rounded_square_icon(art: Image.Image, size: int) -> Image.Image:
    """传统位图图标：蓝底圆角方块 + 白鲸鱼。"""
    icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    body = Image.new("RGBA", (size, size), BACKGROUND + (255,))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size - 1, size - 1), radius=int(size * 0.22), fill=255,
    )
    icon.paste(body, (0, 0), mask)
    icon.alpha_composite(place_on_canvas(art, size, LEGACY_RATIO))
    return icon


def main() -> None:
    source = Image.open(SRC).convert("RGBA")
    whale = extract_whale(source)
    print("whale bbox size:", whale.size)

    for name, multiplier in DENSITIES.items():
        folder = RES / f"mipmap-{name}"
        folder.mkdir(parents=True, exist_ok=True)

        canvas = round(ADAPTIVE_DP * multiplier)
        place_on_canvas(whale, canvas, FOREGROUND_RATIO).save(
            folder / "ic_launcher_foreground.png",
        )

        legacy = round(48 * multiplier)
        rounded_square_icon(whale, legacy).save(folder / "ic_launcher.png")
        rounded_square_icon(whale, legacy).save(folder / "ic_launcher_round.png")
        print(f"{name}: foreground {canvas}px, legacy {legacy}px")

    # 预览：模拟圆形 / 圆角方形 mask 下的效果，方便肉眼验收
    preview = Image.new("RGBA", (3 * 432 + 80, 432 + 40), (245, 246, 248, 255))
    fg = place_on_canvas(whale, 432, FOREGROUND_RATIO)
    for index, radius in enumerate([216, 96, 40]):
        tile = Image.new("RGBA", (432, 432), BACKGROUND + (255,))
        tile.alpha_composite(fg)
        mask = Image.new("L", (432, 432), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, 431, 431), radius=radius, fill=255)
        tile.putalpha(mask)
        preview.alpha_composite(tile, (20 + index * (432 + 20), 20))
    preview.convert("RGB").save(PREVIEW)
    print("preview written")


if __name__ == "__main__":
    main()
