"""Turn clean images into (HR target, degraded LR burst) training pairs.

    bl-make-pairs --clean data/synth/clean --out data/synth/pairs --burst 5 --shift-px 2

Layout:
    out/hr/<id>.png             clean target
    out/lr/<id>_<k>.png         k = 0..burst-1, frame 0 is the reference
    out/meta.jsonl              one line per id: params, scale, ground-truth text if available
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np

from .degrade import DegradationConfig, config_to_dict, degrade_burst, sample_params


def crop_to_multiple(img: np.ndarray, m: int) -> np.ndarray:
    h, w = img.shape[:2]
    return img[: h - h % m, : w - w % m]


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--clean", type=Path, required=True, help="folder of clean .png/.jpg (+ optional .json sidecars)")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--burst", type=int, default=1, help="frames per sample (1 = single-image SR)")
    ap.add_argument("--shift-px", type=float, default=2.0, help="max hand-shake shift between burst frames (HR px)")
    ap.add_argument("--fixed-scale", type=int, help="force one HR/LR scale (the SR model is trained per scale)")
    ap.add_argument("--perspective", type=float, default=0.0, help="corner jitter fraction; keep 0 for SR pairs")
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args(argv)

    cfg = DegradationConfig(perspective=args.perspective, shift_px=args.shift_px)
    if args.fixed_scale:
        cfg.scale = (args.fixed_scale, args.fixed_scale)

    rng = np.random.default_rng(args.seed)
    (args.out / "hr").mkdir(parents=True, exist_ok=True)
    (args.out / "lr").mkdir(parents=True, exist_ok=True)

    paths = sorted(p for p in args.clean.iterdir() if p.suffix.lower() in {".png", ".jpg", ".jpeg"})
    with open(args.out / "meta.jsonl", "w") as meta_f:
        for p in paths:
            hr = cv2.imread(str(p), cv2.IMREAD_COLOR)
            params = sample_params(cfg, rng)
            hr = crop_to_multiple(hr, params["scale"])
            frames = degrade_burst(hr, params, args.burst, args.shift_px, rng)

            cv2.imwrite(str(args.out / "hr" / f"{p.stem}.png"), hr)
            for k, lr in enumerate(frames):
                cv2.imwrite(str(args.out / "lr" / f"{p.stem}_{k}.png"), lr)

            sidecar = p.with_suffix(".json")
            text = json.loads(sidecar.read_text()).get("text") if sidecar.exists() else None
            meta_f.write(json.dumps({"id": p.stem, "burst": args.burst, "params": params, "text": text}) + "\n")

    (args.out / "config.json").write_text(json.dumps(
        {"degradation": config_to_dict(cfg), "seed": args.seed, "burst": args.burst}, indent=1))
    print(f"wrote {len(paths)} samples x {args.burst} frames to {args.out}")


if __name__ == "__main__":
    main()
