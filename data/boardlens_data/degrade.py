"""Physically ordered degradations that mimic reading a board from the back of a lecture hall.

Each degradation is driven by an explicit parameter dict so every generated
pair can be reproduced and every result can be sliced by condition
(e.g. "CER vs. effective distance" uses `scale` and `blur_sigma`).

Order follows the imaging chain:
    scene (glare, exposure) -> geometry (perspective, sub-pixel shift)
    -> optics (defocus) -> motion (hand shake) -> sensor (downsample, noise)
    -> encoder (JPEG)
"""

from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Any

import cv2
import numpy as np


@dataclass
class DegradationConfig:
    scale: tuple[int, int] = (2, 6)              # HR / LR ratio, a proxy for distance
    blur_sigma: tuple[float, float] = (0.0, 2.0)  # defocus, in HR pixels
    motion_len: tuple[int, int] = (0, 9)         # hand shake, in HR pixels
    noise_sigma: tuple[float, float] = (1.0, 8.0)  # read noise, 8-bit units
    jpeg_quality: tuple[int, int] = (60, 95)
    contrast: tuple[float, float] = (0.5, 1.0)   # 1 = untouched
    brightness: tuple[float, float] = (-30.0, 30.0)
    glare_prob: float = 0.3
    perspective: float = 0.0                     # max corner jitter as a fraction of size
    shift_px: float = 0.0                        # per-frame sub-pixel shift for bursts


def sample_params(cfg: DegradationConfig, rng: np.random.Generator) -> dict[str, Any]:
    """Draw one set of degradation parameters. Shared across a burst except per-frame fields."""
    u = lambda r: float(rng.uniform(*r))
    return {
        "scale": int(rng.integers(cfg.scale[0], cfg.scale[1] + 1)),
        "blur_sigma": u(cfg.blur_sigma),
        "motion_len": int(rng.integers(cfg.motion_len[0], cfg.motion_len[1] + 1)),
        "motion_angle": u((0.0, 180.0)),
        "noise_sigma": u(cfg.noise_sigma),
        "jpeg_quality": int(rng.integers(cfg.jpeg_quality[0], cfg.jpeg_quality[1] + 1)),
        "contrast": u(cfg.contrast),
        "brightness": u(cfg.brightness),
        "glare": (
            {"cx": u((0.1, 0.9)), "cy": u((0.1, 0.9)), "radius": u((0.1, 0.4)), "strength": u((40, 160))}
            if rng.random() < cfg.glare_prob else None
        ),
        "perspective": [[u((-cfg.perspective, cfg.perspective)) for _ in range(2)] for _ in range(4)]
        if cfg.perspective > 0 else None,
    }


# --- individual operations (all take and return uint8 HxWx3 or HxW) -------------------------

def apply_exposure(img: np.ndarray, contrast: float, brightness: float) -> np.ndarray:
    out = (img.astype(np.float32) - 127.5) * contrast + 127.5 + brightness
    return np.clip(out, 0, 255).astype(np.uint8)


def apply_glare(img: np.ndarray, cx: float, cy: float, radius: float, strength: float) -> np.ndarray:
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    r = radius * max(h, w)
    mask = np.exp(-(((xx - cx * w) ** 2) + ((yy - cy * h) ** 2)) / (2 * r * r)) * strength
    if img.ndim == 3:
        mask = mask[..., None]
    return np.clip(img.astype(np.float32) + mask, 0, 255).astype(np.uint8)


def perspective_homography(w: int, h: int, jitter: list[list[float]]) -> np.ndarray:
    """Homography mapping the clean image to a view with corners moved by `jitter` (fractions)."""
    src = np.float32([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]])
    dst = src + np.float32(jitter) * np.float32([w, h])
    return cv2.getPerspectiveTransform(src, dst)


def apply_warp(img: np.ndarray, H: np.ndarray) -> np.ndarray:
    h, w = img.shape[:2]
    return cv2.warpPerspective(img, H, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)


def apply_shift(img: np.ndarray, dx: float, dy: float) -> np.ndarray:
    h, w = img.shape[:2]
    M = np.float32([[1, 0, dx], [0, 1, dy]])
    return cv2.warpAffine(img, M, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)


def apply_defocus(img: np.ndarray, sigma: float) -> np.ndarray:
    return img if sigma < 0.1 else cv2.GaussianBlur(img, (0, 0), sigma)


def motion_kernel(length: int, angle_deg: float) -> np.ndarray:
    k = np.zeros((length, length), np.float32)
    k[length // 2, :] = 1.0
    R = cv2.getRotationMatrix2D(((length - 1) / 2, (length - 1) / 2), angle_deg, 1.0)
    k = cv2.warpAffine(k, R, (length, length))
    return k / max(k.sum(), 1e-6)


def apply_motion(img: np.ndarray, length: int, angle_deg: float) -> np.ndarray:
    return img if length < 2 else cv2.filter2D(img, -1, motion_kernel(length, angle_deg))


def downsample(img: np.ndarray, scale: int) -> np.ndarray:
    h, w = img.shape[:2]
    return cv2.resize(img, (max(1, w // scale), max(1, h // scale)), interpolation=cv2.INTER_AREA)


def apply_noise(img: np.ndarray, sigma: float, rng: np.random.Generator) -> np.ndarray:
    # Signal-dependent (shot) + constant (read) noise.
    f = img.astype(np.float32)
    shot = rng.normal(0, 1, f.shape).astype(np.float32) * np.sqrt(np.maximum(f, 0) / 255.0) * sigma
    read = rng.normal(0, sigma * 0.5, f.shape).astype(np.float32)
    return np.clip(f + shot + read, 0, 255).astype(np.uint8)


def apply_jpeg(img: np.ndarray, quality: int) -> np.ndarray:
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, quality])
    assert ok
    return cv2.imdecode(buf, cv2.IMREAD_UNCHANGED)


# --- composition --------------------------------------------------------------------------------

def degrade(
    hr: np.ndarray,
    params: dict[str, Any],
    rng: np.random.Generator,
    shift: tuple[float, float] = (0.0, 0.0),
) -> tuple[np.ndarray, np.ndarray | None]:
    """Apply the full chain to a clean HR image.

    Returns (lr, H): the degraded low-resolution frame and the homography used
    (None when perspective is off). Noise uses `rng`, so frames of a burst that
    share `params` still get independent noise.
    """
    x = apply_exposure(hr, params["contrast"], params["brightness"])
    if params.get("glare"):
        x = apply_glare(x, **params["glare"])

    H = None
    if params.get("perspective"):
        H = perspective_homography(x.shape[1], x.shape[0], params["perspective"])
        x = apply_warp(x, H)
    if shift != (0.0, 0.0):
        x = apply_shift(x, *shift)

    x = apply_defocus(x, params["blur_sigma"])
    x = apply_motion(x, params["motion_len"], params["motion_angle"])
    x = downsample(x, params["scale"])
    x = apply_noise(x, params["noise_sigma"], rng)
    x = apply_jpeg(x, params["jpeg_quality"])
    return x, H


def degrade_burst(
    hr: np.ndarray, params: dict[str, Any], n: int, shift_px: float, rng: np.random.Generator
) -> list[np.ndarray]:
    """N frames of the same scene with independent noise and small hand-shake shifts.

    Frame 0 is the reference (no shift); it is the frame the SR target aligns to.
    """
    frames = []
    for i in range(n):
        s = (0.0, 0.0) if i == 0 else (float(rng.uniform(-shift_px, shift_px)), float(rng.uniform(-shift_px, shift_px)))
        lr, _ = degrade(hr, params, rng, shift=s)
        frames.append(lr)
    return frames


def config_to_dict(cfg: DegradationConfig) -> dict[str, Any]:
    return asdict(cfg)
