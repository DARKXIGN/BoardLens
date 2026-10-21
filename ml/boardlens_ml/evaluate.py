"""Score OCR output against ground truth, sliced by pipeline variant and distance.

    bl-eval --gt gt.jsonl --pred eval/ --by variant distance_m

--pred takes preds.jsonl files or folders (searched recursively for preds.jsonl),
so the eval folder pulled from the phone can be passed as is.

gt.jsonl:    {"id": "...", "text": "...", "ok": true}   (from bl-gt; "ok": false rows are skipped)
preds.jsonl: {"id": "...", "variant": "bicubic", "distance_m": 12, "pred": "..."}
             any extra keys (note, session, device) can be used with --by.

The numbers that go in the write-up must come from ML Kit running on the
phone (the app's evaluation export), so desktop runs here only score files.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import defaultdict
from pathlib import Path

from .metrics import ErrorCounts


def read_jsonl(path: Path) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def expand(paths: list[Path], name: str) -> list[Path]:
    """Files as given; folders searched recursively for `name`."""
    out = []
    for p in paths:
        out.extend(sorted(p.rglob(name)) if p.is_dir() else [p])
    return out


def load_ground_truth(paths: list[Path]) -> tuple[dict[str, str], int]:
    """id -> text, skipping rows bl-gt marked as needing review. Returns (gt, skipped)."""
    gt, skipped = {}, 0
    for path in paths:
        for r in read_jsonl(path):
            if r.get("ok", True) is False:
                skipped += 1
                continue
            gt[r["id"]] = r["text"]
    return gt, skipped


def group_rates(gt: dict[str, str], preds: list[dict], keys: list[str], lowercase: bool):
    groups: dict[tuple, ErrorCounts] = defaultdict(ErrorCounts)
    missing = 0
    for p in preds:
        ref = gt.get(p["id"])
        if ref is None:
            missing += 1
            continue
        groups[tuple(p.get(k) for k in keys)].add(ref, p.get("pred", ""), lowercase)
    return dict(sorted(groups.items(), key=lambda kv: tuple(str(x) for x in kv[0]))), missing


def to_markdown(keys: list[str], groups: dict[tuple, ErrorCounts]) -> str:
    header = "| " + " | ".join(keys + ["n", "CER", "WER"]) + " |"
    sep = "|" + "---|" * (len(keys) + 3)
    rows = [
        "| " + " | ".join([str(v) for v in k] + [str(c.n), f"{c.cer:.3f}", f"{c.wer:.3f}"]) + " |"
        for k, c in groups.items()
    ]
    return "\n".join([header, sep, *rows])


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--gt", type=Path, nargs="+", required=True)
    ap.add_argument("--pred", type=Path, nargs="+", required=True, help="preds.jsonl files or folders")
    ap.add_argument("--by", nargs="*", default=["variant", "distance_m"])
    ap.add_argument("--lowercase", action="store_true", help="case-insensitive scoring")
    ap.add_argument("--csv", type=Path, help="also write the table as CSV")
    args = ap.parse_args(argv)

    gt, skipped = load_ground_truth(args.gt)
    preds = [r for path in expand(args.pred, "preds.jsonl") for r in read_jsonl(path)]
    groups, missing = group_rates(gt, preds, args.by, args.lowercase)

    print(to_markdown(args.by, groups))
    if skipped:
        print(f"\nnote: {skipped} ground-truth rows need review (ok=false) and were skipped", file=sys.stderr)
    if missing:
        print(f"warning: {missing} predictions had no usable ground truth", file=sys.stderr)

    if args.csv:
        with open(args.csv, "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(args.by + ["n", "cer", "wer"])
            for k, c in groups.items():
                w.writerow([*k, c.n, round(c.cer, 5), round(c.wer, 5)])


if __name__ == "__main__":
    main()
