"""Key the green-screen cat sprites into transparent PNGs for the keyboard.

Usage: python3 key_cat_sprites.py <in_dir> <out_xcassets>
Each sprite is keyed, sculpted into a compact head-and-chest kawaii mascot
(trimming away the wide side-loaf rump so the cat perches cleanly at the end of
the candidate bar), trimmed to its painted bounds, and written as @2x/@3x
images into an asset catalog image set.
"""
import json, os, sys
import numpy as np
from PIL import Image, ImageFilter

HEIGHT_PT = {"mimimo": 43, "momo": 42}


def darken_coat(r, g, b, greenness):
    """Turn 米米莫's blue-grey render into a soft charcoal black.

    One smooth tone curve over the cool-toned coat, so highlights stay soft
    sheen rather than blue patches. The warm cream eyes, the bright white
    whiskers and the green background are left as they are.
    """
    lum = (0.3 * r + 0.59 * np.minimum(g, np.maximum(r, b)) + 0.11 * b) / 255
    coat = (greenness < 25) & (b >= r - 8) & (lum < 0.86)
    l = lum[coat]
    dark = (0.07 + 0.42 * l ** 1.5) * 255
    for channel, tint in ((r, 0.97), (g, 0.99), (b, 1.10)):
        channel[coat] = np.clip(dark * tint, 0, 255)


def cream_face_lines(r, g, b):
    """On black fur, dark '^ ^' eye arches vanish; paint them warm cream."""
    yy, xx = np.mgrid[0:r.shape[0], 0:r.shape[1]]
    eye_box = (
        (yy >= 425) & (yy <= 510) & (xx >= 565) & (xx <= 845)
        & ~((xx >= 675) & (xx <= 738) & (yy >= 468))
    )
    lum = (0.3 * r + 0.59 * g + 0.11 * b) / 255
    weight = np.where(eye_box, np.clip((0.36 - lum) / 0.12, 0, 1), 0)
    w_img = np.asarray(
        Image.fromarray((weight * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.2))
    ) / 255.0
    for channel, cream in ((r, 248), (g, 236), (b, 204)):
        channel[:] = channel * (1 - w_img) + cream * w_img


def sculpt_mascot(alpha, is_mimimo):
    """Contour each cat to its round head, ears, and chubby front chest."""
    yy, xx = np.mgrid[0:alpha.shape[0], 0:alpha.shape[1]]
    if is_mimimo:
        x_ear = np.where(yy < 338, 400.0, 492.5 - (yy - 340.0) * 0.035)
        t_head = np.clip(1.0 - ((yy - 472.0) / 168.0) ** 2, 0.0, 1.0)
        x_head = 662.0 - 200.0 * np.sqrt(t_head)
        t_chest = np.clip(1.0 - ((yy - 585.0) / 158.0) ** 2, 0.0, 1.0)
        x_chest = 650.0 - 174.0 * np.sqrt(t_chest)
        x_bound = np.where(yy < 385, x_ear, x_head)
        x_bound = np.where(yy > 472, np.minimum(x_bound, x_chest), x_bound)
        keep_side = np.clip((xx - x_bound + 2.0) / 4.5, 0.0, 1.0)
        y_bot = 585.0 + 158.0 * np.sqrt(np.clip(1.0 - ((xx - 650.0) / 178.0) ** 2, 0.0, 1.0))
        keep_bot = np.where(yy > 585, np.clip((y_bot - yy + 2.0) / 5.0, 0.0, 1.0), 1.0)
        return alpha * keep_side * keep_bot
    else:
        x_ear = np.where(yy < 338, 620.0, 529.5 + (yy - 340.0) * 0.035)
        t_head = np.clip(1.0 - ((yy - 472.0) / 168.0) ** 2, 0.0, 1.0)
        x_head = 360.0 + 200.0 * np.sqrt(t_head)
        t_chest = np.clip(1.0 - ((yy - 590.0) / 158.0) ** 2, 0.0, 1.0)
        x_chest = 362.0 + 172.0 * np.sqrt(t_chest)
        x_bound = np.where(yy < 385, x_ear, x_head)
        x_bound = np.where(yy > 472, np.maximum(x_bound, x_chest), x_bound)
        keep_side = np.clip((x_bound - xx + 2.0) / 4.5, 0.0, 1.0)
        y_bot = 590.0 + 158.0 * np.sqrt(np.clip(1.0 - ((xx - 362.0) / 174.0) ** 2, 0.0, 1.0))
        keep_bot = np.where(yy > 590, np.clip((y_bot - yy + 2.0) / 5.0, 0.0, 1.0), 1.0)
        return alpha * keep_side * keep_bot


def key(path):
    im = np.asarray(Image.open(path).convert("RGB")).astype(np.float32)
    r, g, b = im[..., 0], im[..., 1], im[..., 2]
    greenness = g - np.maximum(r, b)
    is_mimimo = os.path.basename(path).startswith("mimimo")
    if is_mimimo:
        if os.path.basename(path) == "mimimo_happy.png":
            cream_face_lines(r, g, b)
        darken_coat(r, g, b, greenness)
    # Fully green → transparent; neutral → opaque; soft ramp in between.
    alpha = np.clip(1 - (greenness - 25) / (110 - 25), 0, 1)
    # Remove green spill from the soft edge.
    g2 = np.minimum(g, np.maximum(r, b) + 8)
    alpha = sculpt_mascot(alpha, is_mimimo)
    out = np.dstack([r, g2, b, alpha * 255]).astype(np.uint8)
    img = Image.fromarray(out)
    # Clean speckles in the background and soften the cut edge a little.
    a = img.getchannel("A").filter(ImageFilter.MedianFilter(3)).filter(ImageFilter.GaussianBlur(0.7))
    img.putalpha(a)
    return img


def bbox(img):
    a = np.asarray(img.getchannel("A"))
    ys, xs = np.where(a > 12)
    return xs.min(), ys.min(), xs.max() + 1, ys.max() + 1


def main(src, dst):
    groups = {}
    for name in sorted(os.listdir(src)):
        if name in ("mimimo_open.png", "mimimo_happy.png", "momo_open.png", "momo_happy.png"):
            groups.setdefault(name.split("_")[0], []).append(name)
    for cat, names in groups.items():
        keyed = {n: key(os.path.join(src, n)) for n in names}
        reference = next(n for n in names if n.endswith("_open.png"))
        ref_box = bbox(keyed[reference])
        size = keyed[reference].crop(ref_box).size
        for name, img in keyed.items():
            crop = img.crop(bbox(img)).resize(size, Image.LANCZOS)
            stem = name[:-4]
            folder = os.path.join(dst, f"{stem}.imageset")
            os.makedirs(folder, exist_ok=True)
            images = []
            for scale in (2, 3):
                h = HEIGHT_PT[cat] * scale
                w = round(crop.width * h / crop.height)
                file = f"{stem}@{scale}x.png"
                crop.resize((w, h), Image.LANCZOS).save(os.path.join(folder, file), optimize=True)
                images.append({"filename": file, "idiom": "universal", "scale": f"{scale}x"})
            with open(os.path.join(folder, "Contents.json"), "w") as f:
                json.dump({"images": images, "info": {"author": "xcode", "version": 1}}, f, indent=2)
            print(stem, "aspect", round(crop.width / crop.height, 3))
    with open(os.path.join(dst, "Contents.json"), "w") as f:
        json.dump({"info": {"author": "xcode", "version": 1}}, f, indent=2)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
