"""Does TabPFN beat what the app does today?

Temporal hold-out: train on older sightings, test on the most recent 20% (no peeking at the future).
Compared:
  season-histogram  P(flowering | week of year, hemisphere-aligned). This is the idea behind the
                    app's SeasonCurve, but given every annotated sighting worldwide, so it's a
                    generous baseline.
  logistic          logistic regression on the same features as TabPFN
  tabpfn            TabPFN classifier, no tuning

Writes eval.json and prints a markdown table for the DEV post.
"""
from __future__ import annotations

import json
import time

import numpy as np
import pandas as pd
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, brier_score_loss, log_loss, roc_auc_score
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

import features as F
import model as M


def aligned_week(df: pd.DataFrame) -> pd.Series:
    angle = np.arctan2(df["season_sin"], df["season_cos"]) % (2 * np.pi)
    return (angle / (2 * np.pi) * 52).astype(int)


def season_histogram(train: pd.DataFrame, test: pd.DataFrame) -> np.ndarray:
    tw, sw = aligned_week(train), aligned_week(test)
    prior = train["flowering"].mean()
    p = {}
    for w in range(53):
        # pool the week with its neighbours and shrink toward the global rate (Laplace-style)
        mask = tw.isin([(w - 1) % 52, w, (w + 1) % 52])
        p[w] = (train.loc[mask, "flowering"].sum() + 2 * prior) / (mask.sum() + 2)
    return sw.map(p).to_numpy()


def metrics(y: np.ndarray, p: np.ndarray) -> dict:
    p = np.clip(p, 1e-6, 1 - 1e-6)
    return {
        "roc_auc": round(roc_auc_score(y, p), 3),
        "brier": round(brier_score_loss(y, p), 3),
        "log_loss": round(log_loss(y, p), 3),
        "accuracy": round(accuracy_score(y, p >= 0.5), 3),
    }


def is_south_africa(df: pd.DataFrame) -> pd.Series:
    return df["lat"].between(-35, -22) & df["lon"].between(16, 33)


def main() -> None:
    df = M.load_training().sort_values("date").reset_index(drop=True)
    cutoff = df["date"].quantile(0.8)
    train, test = df[df["date"] < cutoff], df[df["date"] >= cutoff]
    X, Xt = train[F.FEATURES], test[F.FEATURES]
    y, yt = train["flowering"].to_numpy(), test["flowering"].to_numpy()
    print(f"train {len(train)} (< {cutoff.date()}), test {len(test)}; flowering rate {y.mean():.2f} / {yt.mean():.2f}")

    preds, timings = {}, {}
    preds["season-histogram"] = season_histogram(train, test)

    logit = make_pipeline(SimpleImputer(strategy="median"), StandardScaler(), LogisticRegression(max_iter=1000))
    preds["logistic"] = logit.fit(X, y).predict_proba(Xt)[:, 1]

    clf = M.make_classifier()
    t0 = time.time()
    clf.fit(X, y)
    preds["tabpfn"] = clf.predict_proba(Xt)[:, 1]
    timings["tabpfn_fit_predict_s"] = round(time.time() - t0, 1)

    za = is_south_africa(test).to_numpy()
    report = {
        "cutoff": str(cutoff.date()),
        "train_rows": len(train),
        "test_rows": len(test),
        "test_rows_south_africa": int(za.sum()),
        "backend": M.backend(), "model": M.label(),
        "timings": timings,
        "all": {k: metrics(yt, p) for k, p in preds.items()},
    }
    if za.sum() >= 20 and len(set(yt[za])) == 2:
        report["south_africa"] = {k: metrics(yt[za], p[za]) for k, p in preds.items()}

    (M.HERE / "eval.json").write_text(json.dumps(report, indent=2))
    for scope in ("all", "south_africa"):
        if scope not in report:
            continue
        print(f"\n**{scope}** (test n={len(test) if scope == 'all' else int(za.sum())})\n")
        print("| model | ROC AUC ↑ | Brier ↓ | log loss ↓ | accuracy ↑ |\n|---|---|---|---|---|")
        for k, m in report[scope].items():
            print(f"| {k} | {m['roc_auc']} | {m['brier']} | {m['log_loss']} | {m['accuracy']} |")
    print(f"\nTabPFN fit+predict: {timings['tabpfn_fit_predict_s']} s ({M.label()})")


if __name__ == "__main__":
    main()
