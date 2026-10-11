"""Bloom-chance sidecar for Jacaranda Scout.

    uvicorn server:app --port 8090

POST /predict  {"points": [{"lat": -26.17, "lon": 28.03, "date": "2026-10-07"}, ...]}
            -> {"probabilities": [0.81, ...], "model": "tabpfn-local", "weather": "open-meteo-live"}
GET  /health   -> training size, backend

TabPFN is fitted once at startup on train.csv (in-context learning: "fit" stores the table,
the transformer does the work at predict time). Weather per ~25 km cell is cached on disk, so a
sync on Wi-Fi keeps predictions working offline; with no weather at all, the model still uses
place + season (TabPFN accepts missing values).
"""
from __future__ import annotations

import datetime as dt
import logging
import math
from datetime import date, timedelta
from threading import Lock

import pandas as pd
import requests
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

import features as F
import model as M

log = logging.getLogger("bloom-model")
app = FastAPI(title="Jacaranda Scout bloom model")

_state: dict = {}
_lock = Lock()  # TabPFN models aren't guaranteed thread-safe
_cache = F.WeatherCache(M.CACHE_DIR / "recent_weather_cache.json")


class Point(BaseModel):
    lat: float = Field(ge=-90, le=90)
    lon: float = Field(ge=-180, le=180)
    date: dt.date | None = None  # dt.: a field named `date` shadows the type inside the class


class PredictRequest(BaseModel):
    points: list[Point] = Field(max_length=2000)


@app.on_event("startup")
def fit() -> None:
    df = M.load_training()
    try:
        clf = M.make_classifier(serving=True)
        clf.fit(df[F.FEATURES], df["flowering"])
    except Exception as e:  # stay up and report not-ready; the planner falls back to sightings only
        log.error("TabPFN (%s) failed to start: %s", M.label(), e)
        _state["error"] = str(e)
        return
    _state.update(clf=clf, rows=len(df), rate=float(df["flowering"].mean()))
    log.info("TabPFN (%s) ready on %d sightings", M.label(), len(df))


@app.get("/health")
def health() -> dict:
    return {"ready": "clf" in _state, "backend": M.backend(), "model": M.label(), "trainRows": _state.get("rows"),
            "trainFloweringRate": _state.get("rate"), "error": _state.get("error")}


@app.post("/predict")
def predict(req: PredictRequest) -> dict:
    if "clf" not in _state:
        raise HTTPException(503, "model not fitted yet")
    if not req.points:
        return {"probabilities": [], "model": M.label(), "weather": "none"}

    today = date.today()
    # trees photographed many times sit on the same spot: predict each ~100 m spot once
    keys = [(round(p.lat, 3), round(p.lon, 3), p.date or today) for p in req.points]
    unique = list(dict.fromkeys(keys))
    weather_by_cell: dict[tuple, dict | None] = {}
    sources: set[str] = set()
    rows = []
    for lat, lon, d in unique:
        c = F.cell(lat, lon)
        if c not in weather_by_cell:
            weather_by_cell[c], src = recent_weather(c, today)
            sources.add(src)
        w = weather_by_cell[c]
        window = F.window_features(w["daily"], d) if w else {k: math.nan for k in ("tmax_30", "tmin_30", "precip_60")}
        rows.append(F.row(lat, lon, d, w and w.get("elevation"), window))
    _cache.flush()

    X = pd.DataFrame(rows, columns=F.FEATURES)
    with _lock:
        proba = dict(zip(unique, _state["clf"].predict_proba(X)[:, 1]))
    weather = "open-meteo-live" if sources == {"live"} else "cached" if "missing" not in sources else "partial"
    return {"probabilities": [round(float(proba[k]), 3) for k in keys], "model": M.label(), "weather": weather}


def recent_weather(c: tuple, today: date) -> tuple[dict | None, str]:
    key = f"recent:{c[0]},{c[1]}:{today}"
    hit = _cache.get(key)
    if hit:
        return hit, "live"
    try:
        hit = F.fetch_recent(c[0], c[1])
        _cache.put(key, hit)
        return hit, "live"
    except requests.RequestException as e:
        log.warning("Open-Meteo unreachable for %s: %s", c, e)
    # offline: newest cached fetch for this cell from the last week still covers most of the window
    for back in range(1, 8):
        hit = _cache.get(f"recent:{c[0]},{c[1]}:{today - timedelta(days=back)}")
        if hit:
            return hit, "cached"
    return None, "missing"
