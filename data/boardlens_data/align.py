"""Align a real classroom frame to the instructor's original slide to get a real (input, target) pair.

    bl-align --slide lecture07.pdf --page 3 --frame captures/row12_0042.jpg --out aligned/

Writes the frame warped into slide coordinates plus a JSON with the homography
and inlier ratio. Low inlier ratios usually mean the wrong slide or heavy
occlusion; filter those out before training (see `--min-inliers`).

Privacy: run this only on frames captured under the data-collection protocol
in docs/data-collection.md (consent, no faces).
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np


@dataclass
class Alignment:
    H: np.ndarray           # maps frame pixels -> slide pixels
    inliers: int
    matches: int

    @property
    def inlier_ratio(self) -> float:
        return self.inliers / max(self.matches, 1)


def _detector():
    # SIFT is in the main OpenCV package since 4.4 and is far more robust than ORB on text.
    if hasattr(cv2, "SIFT_create"):
        return cv2.SIFT_create(nfeatures=8000), cv2.NORM_L2
    return cv2.ORB_create(nfeatures=8000), cv2.NORM_HAMMING


def align(frame: np.ndarray, slide: np.ndarray, ratio: float = 0.75) -> Alignment | None:
    det, norm = _detector()
    g1 = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY) if frame.ndim == 3 else frame
    g2 = cv2.cvtColor(slide, cv2.COLOR_BGR2GRAY) if slide.ndim == 3 else slide
    k1, d1 = det.detectAndCompute(g1, None)
    k2, d2 = det.detectAndCompute(g2, None)
    if d1 is None or d2 is None or len(k1) < 4 or len(k2) < 4:
        return None

    pairs = cv2.BFMatcher(norm).knnMatch(d1, d2, k=2)
    good = [m for m, n in (p for p in pairs if len(p) == 2) if m.distance < ratio * n.distance]
    if len(good) < 8:
        return None

    src = np.float32([k1[m.queryIdx].pt for m in good])
    dst = np.float32([k2[m.trainIdx].pt for m in good])
    H, mask = cv2.findHomography(src, dst, cv2.RANSAC, 3.0)
    if H is None:
        return None
    return Alignment(H=H, inliers=int(mask.sum()), matches=len(good))


def warp_to_slide(frame: np.ndarray, slide_shape: tuple[int, int], H: np.ndarray) -> np.ndarray:
    h, w = slide_shape
    return cv2.warpPerspective(frame, H, (w, h), flags=cv2.INTER_CUBIC)


def load_slide(path: Path, page: int, dpi_scale: float) -> np.ndarray:
    if path.suffix.lower() == ".pdf":
        import pypdfium2 as pdfium  # pip install boardlens[pdf]

        pil = pdfium.PdfDocument(str(path))[page].render(scale=dpi_scale).to_pil().convert("RGB")
        return cv2.cvtColor(np.asarray(pil), cv2.COLOR_RGB2BGR)
    img = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if img is None:
        raise FileNotFoundError(path)
    return img


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--slide", type=Path, required=True, help="slide image or PDF")
    ap.add_argument("--page", type=int, default=0, help="0-based PDF page")
    ap.add_argument("--pdf-scale", type=float, default=2.0, help="PDF render scale (2.0 ~ 144 dpi)")
    ap.add_argument("--frame", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--min-inliers", type=float, default=0.25)
    args = ap.parse_args(argv)

    slide = load_slide(args.slide, args.page, args.pdf_scale)
    frame = cv2.imread(str(args.frame), cv2.IMREAD_COLOR)
    if frame is None:
        raise FileNotFoundError(args.frame)

    result = align(frame, slide)
    args.out.mkdir(parents=True, exist_ok=True)
    stem = args.frame.stem
    report = {"frame": str(args.frame), "slide": str(args.slide), "page": args.page}

    if result is None or result.inlier_ratio < args.min_inliers:
        report.update(ok=False, inlier_ratio=None if result is None else result.inlier_ratio)
    else:
        cv2.imwrite(str(args.out / f"{stem}_aligned.png"), warp_to_slide(frame, slide.shape[:2], result.H))
        cv2.imwrite(str(args.out / f"{stem}_target.png"), slide)
        report.update(ok=True, inlier_ratio=result.inlier_ratio, inliers=result.inliers, H=result.H.tolist())

    (args.out / f"{stem}.json").write_text(json.dumps(report, indent=1))
    print(json.dumps({k: v for k, v in report.items() if k != "H"}))


if __name__ == "__main__":
    main()
