"""Score OCR output against ground truth, sliced by pipeline variant and distance.

    bl-eval --gt data/real/gt.jsonl --pred results/preds.jsonl --by variant distance_m

gt.jsonl:    {"id": "row12_0042", "text": "..."}
preds.jsonl: {"id": "row12_0042", "variant": "baseline", "distance_m": 12, "pred": "..."}
             any extra keys (lighting, angle_deg, device) can be used with --by.

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
    with open(path) as f:
        return [json.loads(line) for line in f if line.strip()]


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
    ap.add_argument("--gt", type=Path, required=True)
    ap.add_argument("--pred", type=Path, required=True)
    ap.add_argument("--by", nargs="*", default=["variant", "distance_m"])
    ap.add_argument("--lowercase", action="store_true", help="case-insensitive scoring")
    ap.add_argument("--csv", type=Path, help="also write the table as CSV")
    args = ap.parse_args(argv)

    gt = {r["id"]: r["text"] for r in read_jsonl(args.gt)}
    groups, missing = group_rates(gt, read_jsonl(args.pred), args.by, args.lowercase)

    print(to_markdown(args.by, groups))
    if missing:
        print(f"\nwarning: {missing} predictions had no ground truth", file=sys.stderr)

    if args.csv:
        with open(args.csv, "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(args.by + ["n", "cer", "wer"])
            for k, c in groups.items():
                w.writerow([*k, c.n, round(c.cer, 5), round(c.wer, 5)])


if __name__ == "__main__":
    main()
