# BoardLens

An Android app that helps low-vision students read the board, slides and whiteboard from anywhere in a lecture hall. The phone camera locks onto the board, corrects perspective, enhances the text and shows it again in large, high-contrast type or reads it aloud. Everything runs on the device; no frame leaves the phone.

The research question: **how much does a learned multi-frame super-resolution stage reduce OCR error on far-away lecture text, at a latency a phone can sustain?** The MVP uses off-the-shelf on-device tools; each stage is then replaced by a trained model and measured against that baseline in CER/WER by distance.

![Synthetic degradations: clean, x3, x5](docs/img/degradation_examples.png)

## Status

| Stage | Baseline (MVP) | Planned replacement |
|---|---|---|
| 1. Capture | CameraX, ~2.7 MP analysis frames, linear zoom | Optical tele lens selection |
| 2. Stabilize | Passthrough | Feature-based stabilization |
| 3. Region + rectify | OpenCV quad detection proposes corners, user adjusts on a frozen frame; OpenCV homography | Learned board detector, tracking |
| 4. Text detection | Whole region | Text-box detector (crop before SR) |
| 5. Enhance | Bicubic upscale to 1600 px long side (toggle: none) | **Multi-frame SR (`ml/`)** |
| 6. OCR | ML Kit on-device Latin | — |
| 7. Output | Large text, contrast/invert, TTS, slide-change cue | Re-rendered layout |

## Repository layout

```
app/    Android app (Kotlin, Jetpack Compose, CameraX, OpenCV, ML Kit, LiteRT)
data/   boardlens_data: synthetic slides, degradation chain, slide-frame alignment
ml/     boardlens_ml:   CER/WER metrics, SR model, training, LiteRT/ONNX export
docs/   design notes, data-collection protocol, experiment logs
tests/  Python tests (pytest); Android unit tests live in app/src/test
```

## Getting started

### Android app

Requirements: Android Studio with AGP 9.4 support, JDK 17+, a physical phone (the emulator camera is not useful for this).

```bash
./gradlew :app:installDebug      # or Run in Android Studio
./gradlew :app:testDebugUnitTest
adb logcat -s BoardLens/Perf     # per-stage latency, one line per processed frame
```

### Python (data, training, evaluation)

```bash
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"            # add ,train for PyTorch, ,pdf for slide PDFs
pytest

bl-render-slides --out data/synth/clean --n 500
bl-make-pairs   --clean data/synth/clean --out data/synth/pairs --burst 5 --fixed-scale 4
bl-train-sr     --data data/synth/pairs --scale 4 --frames 5 --epochs 50 --out runs/sr_x4_b5
bl-export       --ckpt runs/sr_x4_b5/best.pt --out app/src/main/assets/sr_x4.tflite   # needs litert-torch
bl-eval         --gt gt.jsonl --pred preds.jsonl --by variant distance_m
```

## Evaluation

Primary metric is character error rate (CER) of ML Kit OCR on the phone, baseline vs. enhanced, broken down by viewing distance. Secondary: per-stage latency, FPS, battery and thermal behavior on a named device. PSNR is logged during training but does not decide anything: a model can improve PSNR while making text harder to read. See [docs/design.md](docs/design.md).

## Privacy

No `INTERNET` permission. Real classroom data is collected only under [docs/data-collection.md](docs/data-collection.md): instructor permission, no faces, slides only.
