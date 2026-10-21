"""Export a trained SR checkpoint for on-device inference.

    bl-export --ckpt runs/sr_x4_b5/best.pt --lr-size 240 320 --out app/src/main/assets/sr_x4.tflite

LiteRT export uses `litert-torch` (the converter formerly called AI Edge Torch):
    pip install litert-torch
It may pin its own torch version, so a separate virtualenv for export is easiest.

`--format onnx` writes an ONNX file instead, for the ONNX Runtime Mobile fallback.
The input shape is fixed at export time; the app tiles the rectified board to match.
Quantization (FP16 / INT8) is the next step once FP32 latency is measured on a phone.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import torch

from .models.sr import BurstSRLite


def load_model(ckpt_path: Path) -> BurstSRLite:
    ckpt = torch.load(ckpt_path, map_location="cpu")
    model = BurstSRLite(**ckpt["config"])
    model.load_state_dict(ckpt["model"])
    return model.eval()


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ckpt", type=Path, required=True)
    ap.add_argument("--lr-size", type=int, nargs=2, metavar=("H", "W"), default=(240, 320))
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--format", choices=["litert", "onnx"], default="litert")
    args = ap.parse_args(argv)

    model = load_model(args.ckpt)
    h, w = args.lr_size
    sample = torch.rand(1, model.frames * model.channels, h, w)
    args.out.parent.mkdir(parents=True, exist_ok=True)

    if args.format == "onnx":
        torch.onnx.export(model, (sample,), str(args.out), input_names=["lr"], output_names=["sr"], opset_version=17)
    else:
        import litert_torch

        litert_torch.convert(model, (sample,)).export(str(args.out))

    print(f"exported {args.format} model {tuple(sample.shape)} -> {args.out}")


if __name__ == "__main__":
    main()
