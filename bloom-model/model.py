"""Picks the TabPFN backend.

TABPFN_BACKEND=local  (default) open weights, runs on this machine's CPU/GPU; nothing leaves the laptop.
TABPFN_BACKEND=client Prior Labs' hosted API (tabpfn-client); needs TABPFN_TOKEN. Useful on a tiny server.

TABPFN_MODEL_VERSION (local only, default v2): v2 weights download without sign-in, so the sidecar runs
headless and in Docker. Newer versions (v2.5, v2.6, v3, v3.5) are gated: the first run opens a browser to
accept Prior Labs' licence on Hugging Face (read its terms; some are non-commercial).
"""
from __future__ import annotations

import os
from pathlib import Path

import pandas as pd

import features as F

HERE = Path(__file__).parent
TRAIN_CSV = HERE / "train.csv"     # committed, so anyone can reproduce the eval
CACHE_DIR = HERE / "data"          # weather caches (git-ignored)


def backend() -> str:
    return os.environ.get("TABPFN_BACKEND", "local").lower()


def make_classifier(serving: bool = False):
    """serving=True caches the training context once at fit time: a ~1 min startup on CPU, then each
    /predict takes seconds instead of ~45 s (same probabilities)."""
    if backend() == "client":
        import tabpfn_client
        from tabpfn_client import TabPFNClassifier

        token = os.environ.get("TABPFN_TOKEN")
        if not token:
            # without a token tabpfn-client falls back to an interactive login, which hangs a server
            raise RuntimeError("TABPFN_BACKEND=client needs TABPFN_TOKEN")
        tabpfn_client.set_access_token(token)
        return TabPFNClassifier()

    os.environ.setdefault("TABPFN_ALLOW_CPU_LARGE_DATASET", "1")
    from tabpfn import TabPFNClassifier
    from tabpfn.constants import ModelVersion

    return TabPFNClassifier.create_default_for_version(
        ModelVersion(model_version()), device="auto", ignore_pretraining_limits=True, random_state=0,
        **({"fit_mode": "fit_with_cache"} if serving else {}))


def model_version() -> str:
    return os.environ.get("TABPFN_MODEL_VERSION", "v2")


def label() -> str:
    """Shown in the app's sources line, e.g. "tabpfn-v2-local"."""
    return "tabpfn-client" if backend() == "client" else f"tabpfn-{model_version()}-local"


def load_training(path: Path = TRAIN_CSV) -> pd.DataFrame:
    df = pd.read_csv(path, parse_dates=["date"])
    df[F.FEATURES] = df[F.FEATURES].apply(pd.to_numeric, errors="coerce")
    return df
