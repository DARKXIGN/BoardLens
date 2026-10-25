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
- **Invalidation.** The region is tied to the camera's field of view, so zoom is locked while a region is set. Rotation keeps it: analysis frames are upright in both orientations (CameraX follows the orientation sensor), so the board only moves within a wider or taller frame and the tracker re-finds it by its features. Without OpenCV (fixed corners) rotation still clears the region.
- **Layout and rotation.** One custom layout places camera, reading panel and controls: portrait gives the camera 62% of the height above the controls, landscape gives it 60% of the width with text and controls on the right, and "Expand" gives it the whole screen. Rarely used settings fold behind "⋯" and the zoom slider sits on the preview. The activity declares `configChanges` and `fullSensor`: rotating moves views without recreating the activity (camera and zoom keep running), and the UI follows the physical orientation like the analysis frames do, so overlay and frames always agree. The camera controller is created above the layout switch; zoom is also kept in the ViewModel and restored if the activity is recreated for another reason.
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
- **Reading order.** ML Kit orders words within a line but not lines or blocks relative to each other, so concatenating its output scrambled multi-column layouts (first device test: the "COLLABORATION" column of a three-column header surfaced in the middle of the body). `ReadingOrder` (pure, unit-tested) sorts blocks with a recursive XY-cut: tall columns are read column by column; otherwise the region is split into rows, consecutive multi-column rows sharing a gutter are merged and read column by column (two-column slides are not interleaved), and single rows are split into their own columns. Lines inside a block are sorted top to bottom.
- **False text.** Three layers: `LineFilter` drops lines with ML Kit confidence < 0.35 (0 = not reported, kept) or no letter/digit; `TextStabilizer` shows a line only after it appears (fuzzy match, edit distance ≤ 0.34) in 2 of the last 3 frames, otherwise the previous text stays; and `MotionGate` (gyroscope, > 0.35 rad/s, held 300 ms) skips frames while the phone is turned or panned. Nothing new is shown while tracking is lost. "Show image" draws each kept line's box with its reading-order number (yellow) and rejected lines (red).
- **Slide changes.** `SlideChangeDetector` compares normalized OCR text by edit distance and requires two consecutive differing frames, so OCR flicker does not trigger it.

## Accessibility decisions

- The reading pane is not a live region; refreshing OCR text every 400 ms would make TalkBack unusable. Only the short "New slide" line is a polite live region, and reading the content is explicit ("Read aloud") or opt-in on slide change (`speakNewSlides`).
- Default display is dark text on white cards at 36 sp. "High contrast" switches the whole app to yellow on black; size and inversion are separate settings. All text/background pairs are at least 4.5:1.
- Type follows the phone's font-size setting (sp): screen title 22, sheet title 20, card title 17, body 16, caption 13. Board text is 16–96 sp, user-controlled.
- Controls carry content descriptions; the main controls are 84 dp bento tiles and every target is at least 48 dp, for low-precision tapping.
- Every view change can be undone with a visible control and with system Back (one step at a time: expanded camera, evaluation panel, image view, options). "Read aloud" becomes a red "Stop" while speech plays, and speech stops when the app goes to the background.

## Visual design

- Toss-style layout: grey ground, white rounded cards, one blue accent; a bento grid of four tiles (Read aloud, Freeze, Select/Adjust board, Text size), 2×2 on phones and one row of four when the portrait width is 600 dp or more (unfolded foldables). Rarer settings live in a bottom sheet behind the gear button.
- One typeface, Pretendard (SIL OFL 1.1, four static weights bundled unmodified, about 6 MB, Korean included for later localization). One icon set, Tossface. Material icons and chips are not used.
- Tossface's license forbids modified versions, including format changes, so the original SVG files are copied byte-for-byte into `assets/tossface` and drawn at runtime with AndroidSVG rather than converted to vector drawables. The Licenses screen carries the required credit to Toss Team and both license texts.
- The camera card uses a TextureView preview (`ImplementationMode.COMPATIBLE`) so it can be clipped to rounded corners.

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
