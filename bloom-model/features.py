"""Feature engineering shared by build_dataset.py, evaluate.py and server.py.

One row = one place on one date. The question the model answers:
"is a jacaranda here flowering on that date?"

Features
  lat, lon, abs_lat       where on the planet (jacarandas are planted on every continent)
  season_sin, season_cos  day of year, shifted by half a year in the southern hemisphere
                          so October in Johannesburg lines up with April in Los Angeles
  elevation_m             Highveld (1,700 m) blooms later than coastal Durban
  tmax_30, tmin_30        mean daily max/min temperature over the 30 days before the date
  precip_60               rain over the 60 days before (spring rain triggers flowering)

Weather comes from Open-Meteo (CC BY 4.0). Missing weather stays NaN: TabPFN handles missing values.
"""
from __future__ import annotations

import json
import math
import time
from datetime import date, timedelta
from pathlib import Path

import requests

FEATURES = ["lat", "lon", "abs_lat", "season_sin", "season_cos", "elevation_m", "tmax_30", "tmin_30", "precip_60"]

ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
DAILY = "temperature_2m_max,temperature_2m_min,precipitation_sum"
USER_AGENT = "jacaranda-scout-bloom-model/0.1 (open-source dev.to Touch Grass build)"
CELL_DEG = 0.25  # weather is fetched once per ~25 km grid cell


def season_angle(lat: float, d: date) -> float:
    doy = d.timetuple().tm_yday
    shift = 182.6 if lat < 0 else 0.0
    return 2 * math.pi * ((doy + shift) % 365.25) / 365.25


def cell(lat: float, lon: float) -> tuple[float, float]:
    return (round(round(lat / CELL_DEG) * CELL_DEG, 4), round(round(lon / CELL_DEG) * CELL_DEG, 4))


def window_features(daily: dict, target: date) -> dict:
    """Aggregate an Open-Meteo daily block into the 30/60-day windows ending the day before `target`."""
    days = daily.get("time", [])
    idx = {d: i for i, d in enumerate(days)}

    def collect(key: str, n: int) -> list[float]:
        out = []
        for k in range(1, n + 1):
            i = idx.get((target - timedelta(days=k)).isoformat())
            if i is not None and daily[key][i] is not None:
                out.append(daily[key][i])
        return out

    tmax, tmin, rain = collect("temperature_2m_max", 30), collect("temperature_2m_min", 30), collect("precipitation_sum", 60)
    return {
        "tmax_30": sum(tmax) / len(tmax) if len(tmax) >= 20 else math.nan,
        "tmin_30": sum(tmin) / len(tmin) if len(tmin) >= 20 else math.nan,
        "precip_60": sum(rain) * 60 / len(rain) if len(rain) >= 40 else math.nan,
    }


def row(lat: float, lon: float, d: date, elevation: float | None, weather: dict) -> dict:
    a = season_angle(lat, d)
    return {
        "lat": lat,
        "lon": lon,
        "abs_lat": abs(lat),
        "season_sin": math.sin(a),
        "season_cos": math.cos(a),
        "elevation_m": math.nan if elevation is None else elevation,
        **weather,
    }


class WeatherCache:
    """Disk-backed cache of Open-Meteo responses so reruns (and offline serving) don't refetch."""

    def __init__(self, path: Path):
        self.path = path
        self.data: dict = json.loads(path.read_text()) if path.exists() else {}
        self.dirty = 0

    def get(self, key: str):
        return self.data.get(key)

    def put(self, key: str, value) -> None:
        self.data[key] = value
        self.dirty += 1
        if self.dirty >= 25:
            self.flush()

    def flush(self) -> None:
        if self.dirty:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self.data))
            self.dirty = 0


def fetch_archive(lat: float, lon: float, start: date, end: date, timeout: float = 30) -> dict:
    """Historical daily weather for one grid cell. Returns {"elevation": m, "daily": {...}}."""
    r = _get(ARCHIVE_URL, {
        "latitude": lat, "longitude": lon,
        "start_date": start.isoformat(), "end_date": end.isoformat(),
        "daily": DAILY, "timezone": "UTC",
    }, timeout)
    return {"elevation": r.get("elevation"), "daily": r.get("daily", {})}


def fetch_recent(lat: float, lon: float, timeout: float = 20) -> dict:
    """Last 61 days + 7-day forecast for one grid cell, for live predictions."""
    r = _get(FORECAST_URL, {
        "latitude": lat, "longitude": lon,
        "past_days": 61, "forecast_days": 7,
        "daily": DAILY, "timezone": "UTC",
    }, timeout)
    return {"elevation": r.get("elevation"), "daily": r.get("daily", {})}


def _get(url: str, params: dict, timeout: float) -> dict:
    for attempt in range(4):
        resp = requests.get(url, params=params, timeout=timeout, headers={"User-Agent": USER_AGENT})
        if resp.status_code == 429:
            time.sleep(10 * (attempt + 1))
            continue
        resp.raise_for_status()
        return resp.json()
    resp.raise_for_status()
    return {}
