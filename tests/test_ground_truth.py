"""End-to-end check of the phone-evaluation flow on the computer side:
frames.jsonl + crops (as written by EvalStore.kt) -> bl-gt -> bl-eval."""

import json

import cv2
import numpy as np
import pytest

pytest.importorskip("pypdfium2")
reportlab = pytest.importorskip("reportlab")

from boardlens_data import ground_truth  # noqa: E402
from boardlens_data.degrade import DegradationConfig, degrade, sample_params  # noqa: E402
from boardlens_ml import evaluate  # noqa: E402

BULLETS = [
    "- greedy choice property",
    "- optimal substructure",
    "- overlapping subproblems",
    "- memoization vs tabulation",
    "- knapsack recurrence OPT(i, w)",
]


def make_pdf(path):
    """Lecture-like deck: shared title, each slide adds one bullet (hard to tell apart)."""
    from reportlab.pdfgen import canvas

    c = canvas.Canvas(str(path), pagesize=(960, 540))
    for n in range(2, len(BULLETS) + 1):
        c.setFont("Helvetica-Bold", 40)
        c.drawString(60, 460, "Dynamic Programming")
        c.setFont("Helvetica", 30)
        for i, b in enumerate(BULLETS[:n]):
            c.drawString(80, 380 - i * 55, b)
        c.setFont("Helvetica", 14)
        c.drawString(880, 30, str(n - 1))
        c.showPage()
    c.save()


def render_page(pdf_path, index, scale=2.0):
    import pypdfium2 as pdfium

    pil = pdfium.PdfDocument(str(pdf_path))[index].render(scale=scale).to_pil().convert("RGB")
    return cv2.cvtColor(np.asarray(pil), cv2.COLOR_RGB2BGR)


def write_capture(session_dir, slide_bgr, frame_no, distance_scale, seed):
    """Mimic EvalStore.kt: classroom frame -> board crop + quad in crop pixels."""
    rng = np.random.default_rng(seed)
    H_img, W_img = 1080, 1440
    wall = np.full((H_img, W_img, 3), 120, np.uint8)
    h, w = slide_bgr.shape[:2]
    bw = 900 / distance_scale
    bh = bw * h / w
    cx, cy = W_img / 2 + rng.uniform(-40, 40), H_img / 2 + rng.uniform(-30, 30)
    j = lambda: rng.uniform(-0.04, 0.04) * bw  # noqa: E731
    quad = np.float32([[cx - bw / 2 + j(), cy - bh / 2 + j()], [cx + bw / 2 + j(), cy - bh / 2 + j()],
                       [cx + bw / 2 + j(), cy + bh / 2 + j()], [cx - bw / 2 + j(), cy + bh / 2 + j()]])
    M = cv2.getPerspectiveTransform(np.float32([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]]), quad)
    frame = cv2.warpPerspective(slide_bgr, M, (W_img, H_img), borderValue=(120, 120, 120))
    # Camera softness and noise (no downsampling: the frame is already at sensor scale here).
    p = sample_params(DegradationConfig(scale=(1, 1), glare_prob=0.0, blur_sigma=(0.6, 1.0)), rng)
    frame, _ = degrade(frame, p, rng)

    x0, y0 = np.floor(quad.min(0) - 20).astype(int).clip(0)
    x1, y1 = np.ceil(quad.max(0) + 20).astype(int)
    crop = frame[y0:y1, x0:x1]
    name = f"f{frame_no:03d}"
    cv2.imwrite(str(session_dir / f"{name}.png"), crop)
    rec = {"id": f"{session_dir.name}/{name}", "session": session_dir.name, "file": f"{name}.png",
           "distance_m": distance_scale * 3, "note": "test", "quad": (quad - [x0, y0]).ravel().tolist()}
    with open(session_dir / "frames.jsonl", "a") as f:
        f.write(json.dumps(rec) + "\n")
    return rec["id"]


def test_pdf_text_and_page_matching(tmp_path):
    """Build-up slides (each a superset of the previous) at two distances, one slide per session."""
    pdf = tmp_path / "lecture.pdf"
    make_pdf(pdf)
    eval_root = tmp_path / "eval"

    expected = {}
    seed = 0
    for scale in (1.5, 2.0):
        for page_index in range(4):
            session = eval_root / f"s_{scale}_{page_index}"
            session.mkdir(parents=True)
            slide = render_page(pdf, page_index)
            for k in range(3):
                seed += 1
                expected[write_capture(session, slide, k, distance_scale=scale, seed=seed)] = page_index + 1

    out = tmp_path / "gt.jsonl"
    counts = ground_truth.build_ground_truth(eval_root, [pdf], out)
    rows = {r["id"]: r for r in map(json.loads, out.read_text().splitlines())}

    assert counts["frames"] == len(expected)
    wrong = [(fid, rows[fid]["page"], page) for fid, page in expected.items()
             if rows[fid]["ok"] and rows[fid]["page"] != page]
    assert not wrong, wrong  # never confidently wrong
    assert counts["ok"] >= 0.9 * len(expected), counts  # and almost always confident
    # Text comes from the PDF text layer. Page n shows the first n+1 bullets,
    # so page 3 ends at "memoization" and page 4 adds "knapsack".
    page3 = next(r for r in rows.values() if r["page"] == 3)
    page4 = next(r for r in rows.values() if r["page"] == 4)
    assert "memoization vs tabulation" in page3["text"]
    assert "knapsack" not in page3["text"]
    assert "knapsack" in page4["text"]
    assert "\r" not in page3["text"]


def test_slide_change_mid_capture_is_not_overruled(tmp_path):
    """A session whose last frame shows the next slide: that frame keeps its own page."""
    pdf = tmp_path / "lecture.pdf"
    make_pdf(pdf)
    session = tmp_path / "eval" / "s1"
    session.mkdir(parents=True)
    for k in range(3):
        write_capture(session, render_page(pdf, 0), k, distance_scale=1.5, seed=k)
    changed = write_capture(session, render_page(pdf, 3), 3, distance_scale=1.5, seed=9)

    out = tmp_path / "gt.jsonl"
    ground_truth.build_ground_truth(tmp_path / "eval", [pdf], out)
    row = {r["id"]: r for r in map(json.loads, out.read_text().splitlines())}[changed]
    assert not (row["ok"] and row["page"] != 4), row


def test_page_map_overrides_and_eval_reads_folders(tmp_path, capsys):
    pdf = tmp_path / "lecture.pdf"
    make_pdf(pdf)
    eval_root = tmp_path / "eval"
    session = eval_root / "s1"
    session.mkdir(parents=True)
    fid = write_capture(session, render_page(pdf, 0), 0, distance_scale=2.0, seed=1)

    page_map = tmp_path / "map.csv"
    page_map.write_text(f"id,pdf,page\n{fid},lecture.pdf,2\n")
    out = tmp_path / "gt.jsonl"
    ground_truth.build_ground_truth(eval_root, [pdf], out, page_map=page_map)
    row = json.loads(out.read_text().splitlines()[0])
    assert row["page"] == 2 and row["source"] == "manual"

    # A preds.jsonl inside the session folder, as Evaluator.kt writes it.
    text = row["text"]
    with open(session / "preds.jsonl", "w") as f:
        f.write(json.dumps({"id": fid, "variant": "none", "distance_m": 6, "pred": text[:-5]}) + "\n")
        f.write(json.dumps({"id": fid, "variant": "bicubic", "distance_m": 6, "pred": text}) + "\n")
    evaluate.main(["--gt", str(out), "--pred", str(eval_root)])
    table = capsys.readouterr().out
    assert "| bicubic | 6 | 1 | 0.000 | 0.000 |" in table
    assert "| none | 6 | 1 |" in table


def test_unsure_matches_are_skipped_by_eval(tmp_path, capsys):
    gt = tmp_path / "gt.jsonl"
    gt.write_text(json.dumps({"id": "a", "text": "hello", "ok": False}) + "\n"
                  + json.dumps({"id": "b", "text": "world", "ok": True}) + "\n")
    pred = tmp_path / "preds.jsonl"
    pred.write_text(json.dumps({"id": "a", "variant": "none", "distance_m": 3, "pred": "xxxxx"}) + "\n"
                    + json.dumps({"id": "b", "variant": "none", "distance_m": 3, "pred": "world"}) + "\n")
    evaluate.main(["--gt", str(gt), "--pred", str(pred)])
    captured = capsys.readouterr()
    assert "| none | 3 | 1 | 0.000 | 0.000 |" in captured.out
    assert "need review" in captured.err
