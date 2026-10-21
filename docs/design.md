# Design notes

## Goals and non-goals

- Read far-away lecture text (board, projected slides, whiteboard) on a mid-range Android phone, fully on-device.
- Make every pipeline stage swappable so learned components can be measured against a fixed baseline.
- Not a general scene-text reader, not an AR headset app, not cloud OCR.

## Pipeline

```
CameraX frame ─► Stabilizer ─► RegionDetector ─► Rectifier ─► TextDetector ─► Enhancer ─► TextRecognizer ─► UI / TTS
                 (stage 2)     (stage 3a)        (3b)         (4)             (5)          (6)                (7)
```

Contracts live in `app/src/main/java/com/boardlens/pipeline/Stages.kt`; baselines in `BaselineStages.kt`; orchestration and per-stage timing in `ReadingPipeline.kt`.

- **Threading.** CameraX delivers frames on a single analysis executor with `STRATEGY_KEEP_ONLY_LATEST`. `FrameAnalyzer` throttles to one frame every 400 ms for the MVP; the pipeline runs synchronously on that thread (ML Kit is awaited with `Tasks.await`). State reaches Compose through `StateFlow`.
- **Coordinates.** The user's region is stored normalized (0..1) in upright frame coordinates, so it survives resolution changes. Geometry (`Pt`, `Quad`, `FitTransform`) is pure Kotlin and unit-tested on the JVM.
- **Region picking.** "Select board" freezes the latest analysis frame and opens a corner editor on it. Picking on a still frame avoids hand shake while dragging, and the corners map exactly to analysis pixels instead of relying on the preview and analysis streams lining up. `OpenCvBoardDetector` proposes starting corners (largest convex quadrilateral across an edge map and two Otsu masks); a screen-reader user can accept that proposal without dragging. Crossed or tiny quads are rejected before they reach the homography.
- **Invalidation.** The region is tied to the camera's field of view: zoom is locked while a region is set, and rotating the device clears the region.
- **Tracking (handheld use).** `TrackingRegionDetector` keeps the region on the board while the phone moves. ORB features (≈640 px grayscale) inside the quad plus a 25% margin are matched to the current frame (ratio test, RANSAC homography), and the reference corners are mapped through. A match is rejected unless it has ≥15 inliers, an inlier ratio ≥0.4, a convex result and an area within 0.6–1.6× of the selection (zoom is locked, so size should barely change). The policy (`TrackingPolicy`, unit-tested):
  1. Match the long-term anchor (the user's selection). It never drifts.
  2. If that fails, match the previous frame. Right after a slide change the anchor's text is gone, but frames 0.4 s apart still share content.
  3. After 3 consecutive frame-to-frame successes, the change is real: promote the current view to be the anchor.
  4. If both fail, hold the last position (orange outline; a notice appears after ~2 s).
- **Blur gate.** `SharpnessGate` skips frames whose Laplacian variance is below 0.6× the median of the last 8 frames (at most 2 in a row), so motion-blurred frames reach neither the tracker nor OCR. It compares against recent frames rather than a fixed reference, because a sparse new slide is legitimately less "sharp".
- **Tracking evaluation (simulation).** `tools/tracking/simulate.py` renders a slide on a wall, moves a virtual camera (drift, jitter, roll, zoom wobble, tilt), blurs 30% of frames and changes the slide halfway. Corner error is the max over the 4 corners, as % of board width:

  | Hand motion per 0.4 s (median) | Tracking, median / p95 | Fixed corners, median / p95 |
  |---|---|---|
  | 1.8% (very steady) | 1.1% / 3.5–4.4% | 2.5% / 4.7% |
  | 3.6% (steady) | 2.0% / 6.8–7.2% | 5.1% / 9.4% |
  | 12% (strong drift) | 6.5–7% / 23% | 17% / 31% |

  The remaining error is almost all from the single frame where the slide changes: nothing matches, the position is held, and any motion during that frame carries over. Approaches that failed in simulation, kept for the write-up: periodic re-anchoring (bakes small errors in permanently), re-anchoring after holds (locks in large errors), matching only wall/frame features around the board (repeated posters gave false matches), an inlier-spread check (did not separate good from bad fits). Real footage is still needed to confirm these numbers.
- **Baseline enhancer.** The comparison baseline is bicubic upscaling, not the raw crop, because ML Kit reads small glyphs better once they cover more pixels. The learned model has to beat bicubic. The "Upscale" chip switches between `bicubic` and `none` on the same scene; `BoardLens/Perf` logs which one ran.
- **Enhancer history.** `ReadingPipeline` keeps the last `historySize` rectified frames for multi-frame fusion and clears it when the region changes.
- **Slide changes.** `SlideChangeDetector` compares normalized OCR text by edit distance and requires two consecutive differing frames, so OCR flicker does not trigger it.

## Accessibility decisions

- The reading pane is not a live region; refreshing OCR text every 400 ms would make TalkBack unusable. Only the short "New slide" line is a polite live region, and reading the content is explicit ("Read aloud") or opt-in on slide change (`speakNewSlides`).
- Default display is yellow on black at 36 sp; size, contrast and inversion are user settings.
- Controls carry content descriptions; buttons are full-width-ish for low-precision tapping.

## Evaluation protocol

1. **Baseline (weeks 4-6).** Capture fixed sets of frames at 3, 6, 9, 12, 15 m (and two angles, two lighting conditions) with instructor permission. Align each frame to the original slide with `bl-align` to get ground-truth text.
2. **Run on-device.** An evaluation mode in the app (TODO) runs each pipeline variant over the captured set and writes `preds.jsonl` (`id, variant, distance_m, pred`).
3. **Score.** `bl-eval --by variant distance_m`. Report corpus-level CER/WER with the number of frames per cell.
4. **Latency.** Median and p90 per stage from `BoardLens/Perf`, on a named device, after a warm-up period, with thermal state noted.

## Known risks

- **Hallucinated characters.** SR can produce plausible but wrong glyphs (e.g. 8 vs B). This is worse than blur for a reader who trusts the output. Track substitution errors separately and consider showing the raw crop alongside enhanced text.
- **Synthetic-to-real gap.** The degradation chain is a guess about real cameras (ISP sharpening, denoising and HDR are not modeled). Validate on aligned real frames before claiming gains.
- **Projected slides vs. boards.** Projector glare and moiré behave differently from chalk or marker; keep them separate in reporting.

## Open questions

- Prior work on classroom far-text reading in ASSETS / CHI (literature check pending).
- Do low-vision students actually use phones this way in class, versus relying on shared slides? (Interviews via the McBurney Center.)
