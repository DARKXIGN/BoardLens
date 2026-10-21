"""Starting-point super-resolution model for stage 5.

Deliberately small and built only from ops LiteRT's GPU delegate handles
well (3x3 conv, ReLU, add, depth-to-space, bilinear resize), so the first
on-device latency numbers are meaningful. Swap in something stronger once
the baseline-vs-SR CER comparison is working end to end.

Input:  (B, frames * C, h, w), frame 0 is the reference frame.
Output: (B, C, h * scale, w * scale), aligned to the reference frame.
"""

from __future__ import annotations

import torch
from torch import nn
from torch.nn import functional as F


class ResBlock(nn.Module):
    def __init__(self, feats: int):
        super().__init__()
        self.conv1 = nn.Conv2d(feats, feats, 3, padding=1)
        self.conv2 = nn.Conv2d(feats, feats, 3, padding=1)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return x + self.conv2(F.relu(self.conv1(x)))


class BurstSRLite(nn.Module):
    def __init__(self, scale: int = 4, frames: int = 1, channels: int = 1, feats: int = 32, blocks: int = 4):
        super().__init__()
        self.scale, self.frames, self.channels = scale, frames, channels
        # Early fusion: frames are stacked on the channel axis. Fine for small
        # hand-shake shifts; explicit alignment is a later experiment.
        self.head = nn.Conv2d(frames * channels, feats, 3, padding=1)
        self.body = nn.Sequential(*[ResBlock(feats) for _ in range(blocks)])
        self.tail = nn.Sequential(
            nn.Conv2d(feats, channels * scale * scale, 3, padding=1),
            nn.PixelShuffle(scale),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        ref = x[:, : self.channels]
        base = F.interpolate(ref, scale_factor=self.scale, mode="bilinear", align_corners=False)
        feat = self.head(x)
        residual = self.tail(self.body(feat) + feat)
        return (base + residual).clamp(0.0, 1.0)

    def config(self) -> dict:
        return {
            "scale": self.scale,
            "frames": self.frames,
            "channels": self.channels,
            "feats": self.head.out_channels,
            "blocks": len(self.body),
        }


def count_params(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters())
