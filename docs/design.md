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
- **Invalidation.** The region is tied to the camera's field of view: zoom is locked while a region is set, and rotating the device clears the region. Until stabilization (stage 2) exists, the phone needs to stay roughly still (desk, stand) after selection.
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
