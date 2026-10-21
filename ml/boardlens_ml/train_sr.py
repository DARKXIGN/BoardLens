"""Train the stage-5 super-resolution model on pairs from `bl-make-pairs`.

    bl-train-sr --data data/synth/pairs --scale 4 --frames 5 --epochs 50 --out runs/sr_x4_b5 [--wandb]

Runs on CPU for smoke tests; use Colab/Kaggle (or lab GPUs) for real training.
Validation reports PSNR; the decisive CER comparison happens on the phone.
"""

from __future__ import annotations

import argparse
import json
import random
import time
from pathlib import Path

import torch
from torch.nn import functional as F
from torch.utils.data import DataLoader

from .datasets import BurstPairDataset
from .metrics import psnr
from .models.sr import BurstSRLite, count_params


def split_ids(root: Path, scale: int, frames: int, val_frac: float, seed: int) -> tuple[list[str], list[str]]:
    ids = [it["id"] for it in BurstPairDataset(root, scale, frames, patch=None).items]
    random.Random(seed).shuffle(ids)
    n_val = max(1, int(len(ids) * val_frac)) if len(ids) > 1 else 0
    return ids[n_val:], ids[:n_val]


@torch.no_grad()
def validate(model, loader, device) -> float:
    model.eval()
    scores = [psnr(model(lr.to(device)).cpu(), hr) for lr, hr in loader]
    model.train()
    return sum(scores) / max(len(scores), 1)


def main(argv: list[str] | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--scale", type=int, default=4)
    ap.add_argument("--frames", type=int, default=1)
    ap.add_argument("--feats", type=int, default=32)
    ap.add_argument("--blocks", type=int, default=4)
    ap.add_argument("--patch", type=int, default=48, help="LR patch size")
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--epochs", type=int, default=50)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--val-frac", type=float, default=0.1)
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--wandb", action="store_true")
    args = ap.parse_args(argv)

    torch.manual_seed(args.seed)
    device = "cuda" if torch.cuda.is_available() else ("mps" if torch.backends.mps.is_available() else "cpu")

    train_ids, val_ids = split_ids(args.data, args.scale, args.frames, args.val_frac, args.seed)
    train_ds = BurstPairDataset(args.data, args.scale, args.frames, args.patch, ids=train_ids, seed=args.seed)
    train_dl = DataLoader(train_ds, batch_size=args.batch, shuffle=True, num_workers=args.workers, drop_last=False)
    val_dl = None
    if val_ids:
        val_ds = BurstPairDataset(args.data, args.scale, args.frames, patch=None, ids=val_ids)
        val_dl = DataLoader(val_ds, batch_size=1)

    model = BurstSRLite(args.scale, args.frames, 1, args.feats, args.blocks).to(device)
    opt = torch.optim.Adam(model.parameters(), lr=args.lr)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=max(args.epochs, 1))
    print(f"device={device} params={count_params(model):,} train={len(train_ds)} val={len(val_ids)}")

    run = None
    if args.wandb:
        import wandb
        run = wandb.init(project="boardlens", config=vars(args) | {"params": count_params(model)})

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "args.json").write_text(json.dumps({k: str(v) for k, v in vars(args).items()}, indent=1))
    best = float("-inf")

    for epoch in range(1, args.epochs + 1):
        t0, total = time.time(), 0.0
        for lr_img, hr_img in train_dl:
            lr_img, hr_img = lr_img.to(device), hr_img.to(device)
            loss = F.l1_loss(model(lr_img), hr_img)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
            total += loss.item() * lr_img.size(0)
        sched.step()

        train_l1 = total / len(train_ds)
        val_psnr = validate(model, val_dl, device) if val_dl else float("nan")
        print(f"epoch {epoch:3d}  l1 {train_l1:.4f}  val_psnr {val_psnr:.2f} dB  ({time.time() - t0:.1f}s)")
        if run:
            run.log({"epoch": epoch, "train/l1": train_l1, "val/psnr": val_psnr, "lr": sched.get_last_lr()[0]})

        ckpt = {"model": model.state_dict(), "config": model.config(), "epoch": epoch, "val_psnr": val_psnr}
        torch.save(ckpt, args.out / "last.pt")
        if val_psnr > best or val_dl is None:
            best = val_psnr
            torch.save(ckpt, args.out / "best.pt")

    if run:
        run.finish()


if __name__ == "__main__":
    main()
