# Evaluation protocol: OCR accuracy by distance

Answers: **how well does the app read lecture text from a given seat, and how much does each enhancer help?** Primary metric: character error rate (CER) of ML Kit on the phone, per enhancer variant and viewing distance. Read [data-collection.md](data-collection.md) first.

## 1. Capture (phone)

1. Get the instructor's permission and the slide PDF for the lecture.
2. Sit at the target distance. Measure it (laser measure, floor tiles, or rows × row pitch) and write it down.
3. Set zoom, then **Select board** and select the **whole slide**. Ground truth is the full page text, so a partial selection would be scored against lines it never showed.
4. Open the **Eval** chip, enter the distance (m) and a note (room, row, lighting), and tap **Capture 10 frames**. Hold the phone as you normally would; the app saves only sharp, tracked frames.
5. **One slide per capture.** Start a new capture after the slide changes. Ground-truth matching uses the fact that a session shows one slide.
6. Repeat for several slides (aim for ≥5) at each distance. Suggested distances: 3, 6, 9, 12, 15 m.

Each capture becomes a session folder holding lossless PNG crops of the board (never the full frame) and `frames.jsonl` (distance, note, board corners, tracking status, sharpness, device). **Delete last session** removes a capture with the wrong distance.

## 2. Run the enhancer variants (phone)

Tap **Run evaluation**. Every saved crop goes through rectify → enhance → ML Kit once per variant (currently `none` and `bicubic`; the SR model will join here). Results go to `preds.jsonl` in each session folder. Re-running overwrites them, so add a variant and run again.

## 3. Copy to the computer

```bash
adb pull /sdcard/Android/data/com.boardlens/files/eval data/real/eval
```

(or Android Studio → Device Explorer → `/sdcard/Android/data/com.boardlens/files/eval`). `data/real/` is git-ignored.

## 4. Ground truth from the slide PDF

```bash
pip install -e ".[pdf]"
bl-gt --eval data/real/eval --pdf lecture07.pdf --out data/real/gt.jsonl
```

Each crop is registered to every PDF page (SIFT + RANSAC) and the page is chosen by pixel similarity (NCC) after registration, because build-up slides (each one adds a bullet) get near-identical feature-match counts. Within a session, the session's average breaks near-ties. Frames that are still ambiguous are marked `"ok": false`, skipped when scoring, and listed in `gt.review.csv`. Look at those images and add the right page to a CSV:

```csv
id,pdf,page
20261021-153400_d9m/f004,lecture07.pdf,12
```

then re-run with `--page-map map.csv`.

Synthetic check (tests/test_ground_truth.py, 4 build-up slides, 5 frames per slide):

| Board width in the frame | Confident | Needs review | Confidently wrong |
|---|---|---|---|
| 600 px | 20 / 20 | 0 | 0 |
| 450 px | 18 / 20 | 2 | 0 |
| 300 px | 10 / 20 | 10 | 0 |

Far captures need more manual review, but the tool flags rather than guesses.

## 5. Score

```bash
bl-eval --gt data/real/gt.jsonl --pred data/real/eval --by variant distance_m --csv results/baseline.csv
```

Report corpus-level CER/WER with the number of frames per cell, the device, and the lecture(s). Record each run in `docs/experiments/` (see TEMPLATE.md).

## Known limits of PDF ground truth

- Text that is not visible gets counted: hidden animation builds, speaker notes, and text off the slide edge. Prefer decks without animations, or use slides where all content is shown.
- Text inside images is missing from the PDF text layer but visible on screen, so OCR reading it counts as insertions.
- Equations and symbols are often extracted oddly. Use `--lowercase` and consider reporting a text-only subset.
- Reading order of multi-column slides can differ between the PDF and OCR. CER penalizes that even when every word is right; note such slides.
