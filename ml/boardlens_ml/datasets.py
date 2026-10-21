"""PyTorch dataset over the folders written by `bl-make-pairs`."""

from __future__ import annotations

import json
from pathlib import Path

import cv2
import numpy as np
import torch
from torch.utils.data import Dataset


def _load(path: Path, gray: bool) -> np.ndarray:
    img = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE if gray else cv2.IMREAD_COLOR)
    if img is None:
        raise FileNotFoundError(path)
    if not gray:
        img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    return img[..., None] if gray else img  # HWC


class BurstPairDataset(Dataset):
    """Returns (lr, hr): lr is (frames*C, p, p), hr is (C, p*scale, p*scale), floats in [0, 1].

    Only samples generated at `scale` are used, since one model serves one scale.
    `patch=None` returns full images (use batch_size=1 for validation).
    """

    def __init__(self, root: Path, scale: int, frames: int = 1, patch: int | None = 48,
                 gray: bool = True, ids: list[str] | None = None, seed: int = 0):
        self.root, self.scale, self.frames, self.patch, self.gray = Path(root), scale, frames, patch, gray
        with open(self.root / "meta.jsonl") as f:
            meta = [json.loads(line) for line in f if line.strip()]
        self.items = [m for m in meta if m["params"]["scale"] == scale and m["burst"] >= frames]
        if ids is not None:
            keep = set(ids)
            self.items = [m for m in self.items if m["id"] in keep]
        if not self.items:
            raise ValueError(f"no samples with scale={scale} and burst>={frames} in {root}")
        self.rng = np.random.default_rng(seed)

    def __len__(self) -> int:
        return len(self.items)

    def __getitem__(self, i: int):
        sid = self.items[i]["id"]
        hr = _load(self.root / "hr" / f"{sid}.png", self.gray)
        lrs = [_load(self.root / "lr" / f"{sid}_{k}.png", self.gray) for k in range(self.frames)]
        lr = np.concatenate(lrs, axis=2)

        if self.patch is not None:
            h, w = lr.shape[:2]
            p, s = self.patch, self.scale
            y = int(self.rng.integers(0, max(h - p, 0) + 1))
            x = int(self.rng.integers(0, max(w - p, 0) + 1))
            lr = lr[y:y + p, x:x + p]
            hr = hr[y * s:(y + p) * s, x * s:(x + p) * s]

        to_t = lambda a: torch.from_numpy(np.ascontiguousarray(a.transpose(2, 0, 1))).float() / 255.0
        return to_t(lr), to_t(hr)
