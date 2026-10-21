"""CER / WER, the primary metrics of the project.

Corpus-level rates are computed as total edits / total reference length
(not the mean of per-sample rates), which is the standard convention and
keeps short lines from dominating the average.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from typing import Iterable, Sequence

_WS = re.compile(r"\s+")


def normalize(text: str, lowercase: bool = False) -> str:
    """NFKC, unify whitespace (OCR line breaks vary), optionally lowercase."""
    text = unicodedata.normalize("NFKC", text)
    text = _WS.sub(" ", text).strip()
    return text.lower() if lowercase else text


def edit_distance(ref: Sequence, hyp: Sequence) -> int:
    """Levenshtein distance over any sequence (characters or words)."""
    if len(ref) < len(hyp):
        ref, hyp = hyp, ref
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        curr = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1):
            curr[j] = min(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + (r != h))
        prev = curr
    return prev[-1]


@dataclass
class ErrorCounts:
    char_edits: int = 0
    char_ref: int = 0
    word_edits: int = 0
    word_ref: int = 0
    n: int = 0

    def add(self, ref: str, hyp: str, lowercase: bool = False) -> "ErrorCounts":
        r, h = normalize(ref, lowercase), normalize(hyp, lowercase)
        self.char_edits += edit_distance(r, h)
        self.char_ref += len(r)
        self.word_edits += edit_distance(r.split(), h.split())
        self.word_ref += len(r.split())
        self.n += 1
        return self

    @property
    def cer(self) -> float:
        return self.char_edits / max(self.char_ref, 1)

    @property
    def wer(self) -> float:
        return self.word_edits / max(self.word_ref, 1)


def cer(ref: str, hyp: str, lowercase: bool = False) -> float:
    return ErrorCounts().add(ref, hyp, lowercase).cer


def wer(ref: str, hyp: str, lowercase: bool = False) -> float:
    return ErrorCounts().add(ref, hyp, lowercase).wer


def corpus_rates(pairs: Iterable[tuple[str, str]], lowercase: bool = False) -> ErrorCounts:
    acc = ErrorCounts()
    for ref, hyp in pairs:
        acc.add(ref, hyp, lowercase)
    return acc


def psnr(a, b, max_val: float = 1.0) -> float:
    """PSNR for numpy arrays or torch tensors in [0, max_val]. Secondary metric only:
    a model can raise PSNR while making text less readable, so CER decides."""
    import numpy as np

    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    mse = float(np.mean((a - b) ** 2))
    return float("inf") if mse == 0 else float(10 * np.log10(max_val ** 2 / mse))
