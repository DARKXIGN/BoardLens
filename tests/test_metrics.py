import json

from boardlens_ml import evaluate
from boardlens_ml.metrics import cer, corpus_rates, edit_distance, normalize, wer


def test_edit_distance():
    assert edit_distance("kitten", "sitting") == 3
    assert edit_distance("", "abc") == 3
    assert edit_distance(["a", "b"], ["a", "b"]) == 0


def test_normalize_collapses_line_breaks():
    assert normalize("Dynamic\n  Programming ") == "Dynamic Programming"
    assert normalize("ＯＰＴ") == "OPT"  # NFKC folds full-width characters


def test_cer_wer():
    assert cer("hello world", "hello world") == 0.0
    assert abs(cer("hello", "hallo") - 0.2) < 1e-9
    assert wer("the cat sat", "the bat sat") == 1 / 3


def test_corpus_rate_weights_by_length():
    counts = corpus_rates([("a", "b"), ("abcdefghij", "abcdefghij")])
    assert abs(counts.cer - 1 / 11) < 1e-9  # not the mean of per-sample CERs (0.5)


def test_evaluate_cli(tmp_path, capsys):
    gt = tmp_path / "gt.jsonl"
    pred = tmp_path / "pred.jsonl"
    gt.write_text(json.dumps({"id": "f1", "text": "knapsack recurrence"}) + "\n")
    pred.write_text("\n".join(json.dumps(r) for r in [
        {"id": "f1", "variant": "baseline", "distance_m": 10, "pred": "knapsak recurence"},
        {"id": "f1", "variant": "sr", "distance_m": 10, "pred": "knapsack recurrence"},
    ]))
    evaluate.main(["--gt", str(gt), "--pred", str(pred), "--csv", str(tmp_path / "out.csv")])
    out = capsys.readouterr().out
    assert "| sr | 10 | 1 | 0.000 | 0.000 |" in out
    assert (tmp_path / "out.csv").exists()
