"""Render synthetic lecture slides / boards with known ground-truth text.

    bl-render-slides --out data/synth/clean --n 500 --font-dir /path/to/ttf

Each image gets a sidecar JSON with the exact text, so OCR on degraded
versions can be scored with CER/WER without any manual labeling.
Pass --font-dir with a few .ttf files (sans, serif, handwriting-style) for
variety; without it Pillow's built-in font is used.
"""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

VOCAB = (
    "algorithm array binary cache complexity dynamic graph greedy hash heap index kernel latency "
    "matrix memory network node pointer process queue recursion register schedule search stack "
    "thread tree vector gradient loss model training inference dataset accuracy feature layer "
    "neuron batch epoch optimizer theorem proof lemma induction invariant runtime space time "
    "photosynthesis enzyme protein membrane cell energy climate carbon ecosystem species"
).split()

STYLES = {
    "slide": ((255, 255, 255), [(20, 20, 20), (30, 60, 140), (150, 20, 20)]),
    "dark_slide": ((25, 30, 45), [(240, 240, 240), (255, 210, 80)]),
    "whiteboard": ((235, 238, 236), [(20, 20, 160), (10, 10, 10), (170, 20, 20), (20, 120, 40)]),
    "chalkboard": ((35, 70, 50), [(235, 235, 225), (240, 230, 150)]),
}


def random_line(rng: random.Random, lo: int = 2, hi: int = 7) -> str:
    words = [rng.choice(VOCAB) for _ in range(rng.randint(lo, hi))]
    if rng.random() < 0.3:
        words.insert(rng.randrange(len(words) + 1), f"O(n^{rng.randint(1, 3)})")
    if rng.random() < 0.2:
        words.append(f"= {rng.randint(0, 999)}")
    return " ".join(words)


def load_font(fonts: list[Path], size: int) -> ImageFont.ImageFont:
    if fonts:
        return ImageFont.truetype(str(random.choice(fonts)), size)
    return ImageFont.load_default(size=size)


def render(rng: random.Random, fonts: list[Path], width: int, height: int) -> tuple[Image.Image, dict]:
    style = rng.choice(list(STYLES))
    bg, inks = STYLES[style]
    img = Image.new("RGB", (width, height), bg)
    draw = ImageDraw.Draw(img)

    margin = int(width * 0.06)
    y = int(height * 0.07)
    title_size = rng.randint(height // 14, height // 10)
    body_size = rng.randint(height // 22, height // 15)

    lines = []
    title = random_line(rng, 2, 5).title()
    draw.text((margin, y), title, font=load_font(fonts, title_size), fill=inks[0])
    lines.append(title)
    y += int(title_size * 1.8)

    font = load_font(fonts, body_size)
    while y + body_size < height - margin:
        text = random_line(rng)
        prefix = "- " if style in ("slide", "dark_slide") else ""
        draw.text((margin, y), prefix + text, font=font, fill=rng.choice(inks))
        lines.append(prefix + text)
        y += int(body_size * rng.uniform(1.4, 1.9))

    return img, {"style": style, "title_px": title_size, "body_px": body_size, "lines": lines,
                 "text": "\n".join(lines)}


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--n", type=int, default=100)
    ap.add_argument("--width", type=int, default=1920)
    ap.add_argument("--height", type=int, default=1080)
    ap.add_argument("--font-dir", type=Path)
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args(argv)

    rng = random.Random(args.seed)
    random.seed(args.seed)
    fonts = sorted(args.font_dir.glob("*.[ot]tf")) if args.font_dir else []
    args.out.mkdir(parents=True, exist_ok=True)

    for i in range(args.n):
        img, meta = render(rng, fonts, args.width, args.height)
        stem = f"slide_{i:05d}"
        img.save(args.out / f"{stem}.png")
        (args.out / f"{stem}.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1))
    print(f"wrote {args.n} slides to {args.out}")


if __name__ == "__main__":
    main()
