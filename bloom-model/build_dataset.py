"""Build the training CSV: every iNaturalist jacaranda sighting someone annotated with plant phenology.

Label: flowering = 1 when the "Flowers" annotation (value 13) is present, else 0
(fruits/seeds, flower buds, or "no flowers or fruits").

Usage:  python build_dataset.py            -> train.csv
        python build_dataset.py --no-weather   (fast; weather columns left empty)
"""
from __future__ import annotations

import argparse
import csv
import time
from datetime import date, timedelta
from pathlib import Path

import requests

import features as F

INAT = "https://api.inaturalist.org/v1/observations"
TAXON_ID = 77541          # Jacaranda mimosifolia
PHENOLOGY_TERM = 12
FLOWERS = 13
HERE = Path(__file__).parent
DATA = HERE / "data"


def fetch_annotated() -> list[dict]:
    rows, id_above = [], 0
    while True:
        r = requests.get(INAT, params={
            "taxon_id": TAXON_ID, "term_id": PHENOLOGY_TERM, "geo": "true",
            "per_page": 200, "order_by": "id", "order": "asc", "id_above": id_above,
        }, headers={"User-Agent": F.USER_AGENT}, timeout=30)
        r.raise_for_status()
        results = r.json()["results"]
        if not results:
            break
        for o in results:
            id_above = max(id_above, o["id"])
            parsed = parse(o)
            if parsed:
                rows.append(parsed)
        print(f"  iNaturalist: {len(rows)} annotated sightings so far")
        time.sleep(1.0)  # iNaturalist asks for ~1 request per second
    return rows


def parse(o: dict) -> dict | None:
    observed = o.get("observed_on")
    coords = (o.get("geojson") or {}).get("coordinates")
    if not observed or not coords:
        return None
    d = date.fromisoformat(observed[:10])
    if d.year < 2000:
        return None
    values = {a.get("controlled_value_id") for a in o.get("annotations", [])
              if a.get("controlled_attribute_id") == PHENOLOGY_TERM}
    if not values:
        return None
    return {"id": o["id"], "lat": coords[1], "lon": coords[0], "date": d, "flowering": int(FLOWERS in values)}


def attach_weather(rows: list[dict], cache: F.WeatherCache) -> None:
    archive_lag = date.today() - timedelta(days=6)
    for n, r in enumerate(rows, 1):
        c = F.cell(r["lat"], r["lon"])
        key = f"archive:{c[0]},{c[1]}:{r['date']}"
        hit = cache.get(key)
        if hit is None and r["date"] <= archive_lag:
            try:
                hit = F.fetch_archive(c[0], c[1], r["date"] - timedelta(days=61), r["date"] - timedelta(days=1))
                cache.put(key, hit)
                time.sleep(0.15)
            except requests.RequestException as e:
                print(f"  weather failed for {key}: {e}")
        weather = F.window_features(hit["daily"], r["date"]) if hit else {"tmax_30": float("nan"), "tmin_30": float("nan"), "precip_60": float("nan")}
        r.update(F.row(r["lat"], r["lon"], r["date"], hit and hit.get("elevation"), weather))
        if n % 100 == 0:
            print(f"  weather: {n}/{len(rows)}")
    cache.flush()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-weather", action="store_true")
    args = ap.parse_args()

    rows = fetch_annotated()
    print(f"{len(rows)} sightings, {sum(r['flowering'] for r in rows)} flowering")
    if args.no_weather:
        for r in rows:
            r.update(F.row(r["lat"], r["lon"], r["date"], None, {"tmax_30": "", "tmin_30": "", "precip_60": ""}))
    else:
        attach_weather(rows, F.WeatherCache(DATA / "weather_cache.json"))

    out = HERE / "train.csv"
    with out.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["id", "date", *F.FEATURES, "flowering"])
        w.writeheader()
        for r in rows:
            w.writerow({k: r[k] for k in w.fieldnames})
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
