"""Simulated handheld footage for the board tracker.

    python tools/tracking/simulate.py

Renders a slide on a wall, moves a virtual camera (drift, jitter, roll, zoom wobble,
slight tilt), adds motion blur on 30% of frames and changes the slide halfway.
Reports corner error (max over the 4 corners, as % of board width) for the tracker
versus keeping the user's corners fixed. Used to design TrackingRegionDetector.kt.
"""
import math, os, random, sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tracker import Tracker  # noqa: E402
from boardlens_data import render_slides  # noqa: E402

def scene(seed, slide_seed, textured):
    rng = np.random.default_rng(seed)
    S = np.full((1800, 2400, 3), rng.integers(90, 170, 3), np.uint8)
    if textured:  # posters, door, light switch: what a real wall has
        for _ in range(12):
            x, y = rng.integers(0, 2200), rng.integers(0, 1600); w, h = rng.integers(40, 260, 2)
            cv2.rectangle(S, (int(x), int(y)), (int(x+w), int(y+h)), [int(v) for v in rng.integers(0, 255, 3)], -1)
    img, _ = render_slides.render(random.Random(slide_seed), [], 1200, 675)
    slide = cv2.cvtColor(np.asarray(img), cv2.COLOR_RGB2BGR)
    x0, y0 = 600, 520
    S[y0:y0+675, x0:x0+1200] = slide
    cv2.rectangle(S, (x0-8, y0-8), (x0+1208, y0+683), (40, 40, 40), 8)  # board frame
    return S, np.float32([[x0, y0], [x0+1200, y0], [x0+1200, y0+675], [x0, y0+675]])

AMP = 1.0
def camera(t, rng):
    # Handheld: slow drift + jitter, small roll and scale wobble. Maps scene -> 1440x1080 frame.
    tx = 480 + AMP*140*math.sin(t*0.4) + rng.normal(0, AMP*25); ty = 360 + AMP*90*math.sin(t*0.3+1) + rng.normal(0, AMP*20)
    ang = math.radians(AMP*4*math.sin(t*0.5) + rng.normal(0, AMP*1)); sc = 0.9 * (1 + AMP*0.04*math.sin(t*0.7))
    c = np.array([1200, 900]); R = cv2.getRotationMatrix2D((float(c[0]), float(c[1])), math.degrees(ang), sc)
    A = np.vstack([R, [0, 0, 1]]); T = np.array([[1, 0, -tx], [0, 1, -ty], [0, 0, 1]])
    # Mild perspective, as if the phone tilts.
    P = np.array([[1, 0, 0], [0, 1, 0], [rng.normal(0, AMP*2e-5), rng.normal(0, AMP*2e-5), 1]])
    return T @ P @ A

motion = []
skipped = [0]
fixed_errs = []
def run(textured, seed):
    rng = np.random.default_rng(seed)
    S1, board = scene(seed, seed, textured); S2, _ = scene(seed, seed + 100, textured)
    tr = Tracker(); errs = []; lost = 0; prev_gt = None; fixed = None
    for t in range(30):
        Hc = camera(t, rng); S = S1 if t < 15 else S2   # slide changes halfway
        f = cv2.warpPerspective(S, Hc, (1440, 1080))
        if rng.random() < 0.3: f = cv2.blur(f, (int(rng.integers(3, 9)), 1))  # motion blur
        f = np.clip(f + rng.normal(0, 4, f.shape), 0, 255).astype(np.uint8)
        gt = cv2.perspectiveTransform(board.reshape(-1, 1, 2), Hc).reshape(4, 2)
        if t == 0: tr.set_reference(f, gt); fixed = gt.copy(); continue
        fixed_errs.append(np.linalg.norm(fixed - gt, axis=1).max() / np.linalg.norm(gt[1] - gt[0]))
        q, st = tr.track(f)
        lost += st == 'lost'
        skipped[0] += st == 'skipped'
        if st == 'skipped': continue
        errs.append(np.linalg.norm(q - gt, axis=1).max() / np.linalg.norm(gt[1] - gt[0]))
        if t > 1: motion.append(np.linalg.norm(gt - prev_gt, axis=1).max() / np.linalg.norm(gt[1]-gt[0]))
        prev_gt = gt
    return np.array(errs), lost

for AMP, label in ((1.0, 'aggressive'), (0.3, 'steady'), (0.15, 'very steady')):
  motion.clear(); fixed_errs.clear(); skipped[0] = 0
  for textured in (True, False):
    allE, allL = [], 0
    for seed in range(8):
        e, l = run(textured, seed); allE.append(e); allL += l
    e = np.concatenate(allE)
    print(f"[{label}] textured_wall={textured}: frames={len(e)} lost={allL} median_err={np.median(e)*100:.2f}% p95={np.percentile(e,95)*100:.2f}% max={e.max()*100:.1f}% of board width")
  print(f'  [{label}] inter-frame motion median {np.median(motion)*100:.1f}%; skipped(blurry)={skipped[0]}; WITHOUT tracking: median_err={np.median(fixed_errs)*100:.1f}% p95={np.percentile(fixed_errs,95)*100:.1f}%')
