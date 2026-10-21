import pytest

torch = pytest.importorskip("torch")

from boardlens_data import make_pairs, render_slides  # noqa: E402
from boardlens_ml import train_sr  # noqa: E402
from boardlens_ml.export import load_model  # noqa: E402
from boardlens_ml.models.sr import BurstSRLite, count_params  # noqa: E402


def test_output_shape_and_size():
    m = BurstSRLite(scale=4, frames=5, channels=1)
    y = m(torch.rand(2, 5, 24, 32))
    assert y.shape == (2, 1, 96, 128)
    assert count_params(m) < 200_000  # stays phone-sized


def test_training_smoke(tmp_path):
    clean = tmp_path / "clean"
    render_slides.main(["--out", str(clean), "--n", "4", "--width", "384", "--height", "256"])
    pairs = tmp_path / "pairs"
    make_pairs.main(["--clean", str(clean), "--out", str(pairs), "--burst", "3", "--fixed-scale", "2"])

    run = tmp_path / "run"
    train_sr.main(["--data", str(pairs), "--out", str(run), "--scale", "2", "--frames", "3",
                   "--epochs", "1", "--batch", "2", "--patch", "32", "--workers", "0"])
    model = load_model(run / "best.pt")
    assert model.frames == 3 and model.scale == 2
