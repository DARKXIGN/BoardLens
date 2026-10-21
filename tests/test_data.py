import json
import random

import cv2
import numpy as np

from boardlens_data import align, make_pairs, render_slides
from boardlens_data.degrade import DegradationConfig, degrade, degrade_burst, perspective_homography, sample_params


def _slide(seed=0, w=640, h=360):
    img, meta = render_slides.render(random.Random(seed), [], w, h)
    return cv2.cvtColor(np.asarray(img), cv2.COLOR_RGB2BGR), meta


def test_render_has_ground_truth():
    img, meta = _slide()
    assert img.shape == (360, 640, 3)
    assert meta["text"] and len(meta["lines"]) >= 2


def test_degrade_shapes_and_determinism():
    hr, _ = _slide()
    cfg = DegradationConfig(scale=(4, 4))
    p = sample_params(cfg, np.random.default_rng(1))
    a, _ = degrade(hr, p, np.random.default_rng(7))
    b, _ = degrade(hr, p, np.random.default_rng(7))
    assert a.shape == (90, 160, 3)
    assert np.array_equal(a, b)


def test_burst_frames_differ_but_match_shape():
    hr, _ = _slide()
    p = sample_params(DegradationConfig(scale=(2, 2)), np.random.default_rng(0))
    frames = degrade_burst(hr, p, n=3, shift_px=2.0, rng=np.random.default_rng(0))
    assert len(frames) == 3 and all(f.shape == frames[0].shape for f in frames)
    assert not np.array_equal(frames[0], frames[1])


def test_align_recovers_known_homography():
    slide, _ = _slide(seed=3, w=960, h=540)
    H_true = perspective_homography(960, 540, [[0.04, 0.03], [-0.05, 0.02], [-0.03, -0.04], [0.05, -0.02]])
    frame = cv2.warpPerspective(slide, H_true, (960, 540), borderMode=cv2.BORDER_REPLICATE)

    result = align.align(frame, slide)
    assert result is not None and result.inlier_ratio > 0.3

    # Mapping frame -> slide should invert the synthetic warp.
    pts = np.float32([[[200, 150]], [[700, 400]]])
    recovered = cv2.perspectiveTransform(cv2.perspectiveTransform(pts, H_true), result.H)
    assert np.abs(recovered - pts).max() < 2.0


def test_make_pairs_cli(tmp_path):
    clean = tmp_path / "clean"
    render_slides.main(["--out", str(clean), "--n", "2", "--width", "320", "--height", "180"])
    out = tmp_path / "pairs"
    make_pairs.main(["--clean", str(clean), "--out", str(out), "--burst", "2", "--fixed-scale", "2"])
    meta = [json.loads(l) for l in (out / "meta.jsonl").read_text().splitlines()]
    assert len(meta) == 2 and meta[0]["text"]
    assert (out / "lr" / f"{meta[0]['id']}_1.png").exists()
