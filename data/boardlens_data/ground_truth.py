"""Ground truth for phone evaluation captures, from the lecture's slide PDF.

    bl-gt --eval eval/ --pdf lecture07.pdf --out gt.jsonl

`eval/` is the folder pulled from the phone (adb pull /sdcard/Android/data/com.boardlens/files/eval).
For each saved board crop:

1. rectify it with the quad stored in frames.jsonl (same corners the app used),
2. register it to every page of the PDF(s) (SIFT + RANSAC; page features computed once),
3. score each page by pixel similarity (NCC) between the registered frame and the page,
4. take the best page's text layer as the ground-truth text.

Why pixels and not match counts: lectures build slides up one bullet at a time, so
slide 1 is a subset of slide 2 and both get similar feature-match counts. Warped onto
the page, though, the frame lacks slide 2's extra bullet and NCC drops there.

A capture session is ~4 s of one slide, so scores are also averaged per session:
a frame takes its own best page when it is clear on its own, otherwise the session's.
Frames that are unclear either way get "ok": false, are left out of scoring by
`bl-eval`, and are listed in <out>.review.csv. Fix them with --page-map
(CSV: id,pdf,page with 1-based pages) and re-run.

Select the whole slide when capturing: the ground truth is the entire page's text,
so a crop of part of a slide would be scored against lines it never showed.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np


# ---- inputs ---------------------------------------------------------------------------------------

def load_frames(eval_root: Path) -> list[dict]:
    """All records from <eval_root>/**/frames.jsonl, with the absolute image path added."""
    records = []
    for frames_file in sorted(eval_root.rglob("frames.jsonl")):
        for line in frames_file.read_text(encoding="utf-8").splitlines():
            if line.strip():
                rec = json.loads(line)
                rec["_path"] = str(frames_file.parent / rec["file"])
                records.append(rec)
    return records


def rectify(crop: np.ndarray, quad8: list[float], max_long_side: int = 1600) -> np.ndarray:
    """Warp the board in `crop` to a fronto-parallel image, like OpenCvRectifier.kt."""
    q = np.float32(quad8).reshape(4, 2)
    w = max(np.linalg.norm(q[1] - q[0]), np.linalg.norm(q[2] - q[3]))
    h = max(np.linalg.norm(q[3] - q[0]), np.linalg.norm(q[2] - q[1]))
    s = min(1.0, max_long_side / max(w, h))
    w, h = max(1, int(round(w * s))), max(1, int(round(h * s)))
    dst = np.float32([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]])
    return cv2.warpPerspective(crop, cv2.getPerspectiveTransform(q, dst), (w, h), flags=cv2.INTER_CUBIC)


def normalize_pdf_text(text: str) -> str:
    """PDF text layers use \\r\\n and sometimes soft hyphens / ligatures; make them plain."""
    text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\u00ad", "")
    text = text.replace("\ufb01", "fi").replace("\ufb02", "fl")
    return re.sub(r"[ \t]+", " ", text).strip()


# ---- page matching --------------------------------------------------------------------------------

@dataclass
class Page:
    pdf: str          # file name
    number: int       # 1-based
    text: str
    keypoints: list
    descriptors: np.ndarray | None
    size: tuple[int, int]


class PageIndex:
    def __init__(self, pdfs: list[Path], render_scale: float = 1.5, nfeatures: int = 3000,
                 own_fit_ratio: float = 0.9):
        self.own_fit_ratio = own_fit_ratio
        import pypdfium2 as pdfium  # pip install boardlens[pdf]

        self.sift = cv2.SIFT_create(nfeatures=nfeatures)
        self.matcher = cv2.BFMatcher(cv2.NORM_L2)
        self.pages: list[Page] = []
        self.images: list[np.ndarray] = []
        for pdf_path in pdfs:
            doc = pdfium.PdfDocument(str(pdf_path))
            for i in range(len(doc)):
                page = doc[i]
                img = np.asarray(page.render(scale=render_scale).to_pil().convert("L"))
                self.images.append(img)
                kp, des = self.sift.detectAndCompute(img, None)
                text = normalize_pdf_text(page.get_textpage().get_text_range())
                self.pages.append(Page(pdf_path.name, i + 1, text, kp, des, (img.shape[1], img.shape[0])))

    def register(self, gray: np.ndarray, ratio: float = 0.75) -> list[tuple[int, np.ndarray | None]]:
        """(inliers, homography frame->page) for every page."""
        kp, des = self.sift.detectAndCompute(gray, None)
        out = []
        for page in self.pages:
            inliers, H = 0, None
            if des is not None and page.descriptors is not None and len(kp) >= 4 and len(page.keypoints) >= 4:
                pairs = self.matcher.knnMatch(des, page.descriptors, k=2)
                good = [m for m, n in (p for p in pairs if len(p) == 2) if m.distance < ratio * n.distance]
                if len(good) >= 8:
                    src = np.float32([kp[m.queryIdx].pt for m in good])
                    dst = np.float32([page.keypoints[m.trainIdx].pt for m in good])
                    H, mask = cv2.findHomography(src, dst, cv2.RANSAC, 5.0)
                    inliers = int(mask.sum()) if H is not None else 0
            out.append((inliers, H))
        return out

    def similarity(self, gray: np.ndarray, page_index: int, H: np.ndarray) -> float:
        """NCC between the frame warped onto the page and the page, over the covered area."""
        page_img = self.images[page_index]
        h, w = page_img.shape
        warped = cv2.warpPerspective(gray, H, (w, h))
        covered = cv2.warpPerspective(np.full(gray.shape, 255, np.uint8), H, (w, h)) > 0
        if covered.sum() < 0.3 * w * h:
            return -1.0  # the frame covers too little of this page to judge
        # Blur both: the frame is far blurrier than the clean render, and NCC should
        # compare where ink is, not stroke sharpness.
        a = cv2.GaussianBlur(warped.astype(np.float32), (0, 0), 3)[covered]
        b = cv2.GaussianBlur(page_img.astype(np.float32), (0, 0), 3)[covered]
        return float(((a - a.mean()) * (b - b.mean())).mean() / (a.std() * b.std() + 1e-6))

    def page_scores(self, image_bgr: np.ndarray, min_inliers: int) -> tuple[np.ndarray, int]:
        """NCC per page (-1 where registration failed) and the best inlier count."""
        gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY) if image_bgr.ndim == 3 else image_bgr
        reg = self.register(gray)
        best_inliers = max(i for i, _ in reg)
        # Score every page with the best-supported registration. Slides of one deck share
        # a layout, so it is valid for the other pages too. A page's own fit is used only
        # when nearly as well supported: weaker fits are often shifted by one text line
        # (bullet lines look alike), which can line up *better* with a slide that has an
        # extra line and make the wrong page win.
        H_best = max(reg, key=lambda t: t[0])[1]
        scores = np.full(len(self.pages), -1.0)
        if H_best is None or best_inliers < min_inliers:
            return scores, best_inliers
        for idx, (inliers, H) in enumerate(reg):
            candidates = [H_best]
            if H is not None and inliers >= self.own_fit_ratio * best_inliers:
                candidates.append(H)
            scores[idx] = max(self.similarity(gray, idx, h) for h in candidates)
        return scores, best_inliers


# ---- main -----------------------------------------------------------------------------------------

def read_page_map(path: Path | None) -> dict[str, tuple[str, int]]:
    if path is None:
        return {}
    with open(path, newline="", encoding="utf-8") as f:
        return {row["id"]: (row["pdf"], int(row["page"])) for row in csv.DictReader(f)}


def decide(frame_scores: np.ndarray, session_scores: np.ndarray, frame_margin: float,
           session_margin: float, min_similarity: float) -> tuple[int, str, bool]:
    """(page index, source, ok): the frame's own pick when it is clear, else the session's."""
    def best_and_margin(v):
        order = np.argsort(-v)
        second = v[order[1]] if len(v) > 1 else -1.0
        return int(order[0]), float(v[order[0]]), float(v[order[0]] - second)

    f_best, f_sim, f_margin = best_and_margin(frame_scores)
    if f_sim >= min_similarity and f_margin >= frame_margin:
        return f_best, "auto", True
    s_best, s_sim, s_margin = best_and_margin(session_scores)
    # The session only breaks near-ties: its pick must be one the frame itself rates
    # almost as high as its own best (guards against a slide change mid-capture).
    near_tie = frame_scores[s_best] >= f_sim - frame_margin
    if s_sim >= min_similarity and s_margin >= session_margin and near_tie and frame_scores[s_best] >= min_similarity:
        return s_best, "session", True
    return f_best, "auto", False


def build_ground_truth(eval_root: Path, pdfs: list[Path], out: Path, page_map: Path | None = None,
                       min_inliers: int = 10, min_similarity: float = 0.5,
                       frame_margin: float = 0.05, session_margin: float = 0.02) -> dict[str, int]:
    index = PageIndex(pdfs)
    by_key = {(p.pdf, p.number): p for p in index.pages}
    overrides = read_page_map(page_map)
    frames = load_frames(eval_root)
    counts = {"frames": len(frames), "ok": 0, "review": 0, "manual": 0}

    # Pass 1: score every frame against every page.
    scored = {}
    for rec in frames:
        if rec["id"] in overrides:
            continue
        crop = cv2.imread(rec["_path"], cv2.IMREAD_COLOR)
        if crop is None:
            print(f"warning: cannot read {rec['_path']}", file=sys.stderr)
            continue
        scored[rec["id"]] = index.page_scores(rectify(crop, rec["quad"]), min_inliers)

    session_mean = {}
    for rec in frames:
        if rec["id"] in scored:
            session_mean.setdefault(rec.get("session", ""), []).append(scored[rec["id"]][0])
    session_mean = {k: np.mean(v, axis=0) for k, v in session_mean.items()}

    # Pass 2: decide and write.
    review_rows = []
    with open(out, "w", encoding="utf-8") as f:
        for rec in frames:
            row = {"id": rec["id"], "distance_m": rec.get("distance_m")}
            if rec["id"] in overrides:
                pdf, number = overrides[rec["id"]]
                page = by_key[(pdf, number)]
                row.update(text=page.text, pdf=pdf, page=number, source="manual", ok=True)
                counts["manual"] += 1
            elif rec["id"] in scored:
                scores, inliers = scored[rec["id"]]
                idx, source, ok = decide(scores, session_mean[rec.get("session", "")],
                                         frame_margin, session_margin, min_similarity)
                page = index.pages[idx]
                ranked = sorted(scores, reverse=True)
                row.update(text=page.text, pdf=page.pdf, page=page.number, source=source, ok=ok,
                           similarity=round(float(scores[idx]), 3),
                           runner_up_similarity=round(float(ranked[1]), 3) if len(ranked) > 1 else None,
                           inliers=inliers)
                counts["ok" if ok else "review"] += 1
                if not ok:
                    review_rows.append({"id": rec["id"], "pdf": page.pdf, "page": page.number,
                                        "similarity": row["similarity"],
                                        "runner_up_similarity": row["runner_up_similarity"],
                                        "image": rec["_path"]})
            else:
                continue
            f.write(json.dumps(row, ensure_ascii=False) + "\n")

    review = out.with_suffix(".review.csv")
    with open(review, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["id", "pdf", "page", "similarity", "runner_up_similarity", "image"])
        w.writeheader()
        w.writerows(review_rows)
    return counts


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--eval", type=Path, required=True, help="folder pulled from the phone")
    ap.add_argument("--pdf", type=Path, nargs="+", required=True, help="slide PDF(s) of the captured lecture(s)")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--page-map", type=Path, help="CSV id,pdf,page (1-based) overriding automatic matching")
    ap.add_argument("--min-inliers", type=int, default=10)
    ap.add_argument("--min-similarity", type=float, default=0.5)
    args = ap.parse_args(argv)

    c = build_ground_truth(args.eval, args.pdf, args.out, args.page_map, args.min_inliers, args.min_similarity)
    print(f"{c['frames']} frames: {c['ok']} matched, {c['manual']} from page map, "
          f"{c['review']} need review (see {args.out.with_suffix('.review.csv')})")


if __name__ == "__main__":
    main()
