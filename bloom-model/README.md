# bloom-model: TabPFN bloom-chance sidecar

**Problem:** the planner used to need someone to have photographed a jacaranda in the last 21 days. In Johannesburg that almost never happens: iNaturalist has ~5 jacaranda sightings per *season* within 25 km of Emmarentia, and 0 in the last three weeks. So the app fell back to demo data.

**Fix:** trees don't move. iNaturalist knows where ~100 jacarandas stand around Joburg and ~360 around Pretoria (any year). What we need is the chance that they're flowering *today*. That's a tabular prediction problem, and it's what [TabPFN](https://github.com/PriorLabs/TabPFN) is for.

```
every phenology-annotated jacaranda on iNaturalist (≈1,900, six continents)
  + Open-Meteo weather in the 30/60 days before each sighting
        │  build_dataset.py  →  train.csv
        ▼
TabPFN classifier (in-context learning: no training loop, no tuning)
        │  server.py  POST /predict
        ▼
Java planner: known tree weight = 0.4 × P(flowering today)   (a fresh "Flowering" photo still counts 1.0)
```

## Features

| column | why |
|---|---|
| `lat`, `lon`, `abs_lat` | jacarandas are planted from Pretoria to Lisbon to LA; the model learns regional timing |
| `season_sin`, `season_cos` | day of year, shifted half a year in the southern hemisphere so seasons line up |
| `elevation_m` | Highveld (1,700 m) blooms later than coastal Durban |
| `tmax_30`, `tmin_30` | mean daily max/min over the 30 days before: warm spring days bring flowering forward |
| `precip_60` | rain over the 60 days before |

Label: `flowering = 1` if the observer annotated **Flowers**, `0` for fruits/seeds, buds, or "no flowers or fruits".

## Results

Temporal hold-out from `python evaluate.py`: train on the 1,528 sightings before 2025-06-20, test on the 393 after (no peeking at the future). Full numbers in `eval.json`.

**All test sightings** (n=393)

| model | ROC AUC ↑ | Brier ↓ | log loss ↓ | accuracy ↑ |
|---|---|---|---|---|
| season-histogram | 0.842 | 0.163 | 0.503 | 0.779 |
| logistic | 0.860 | 0.153 | 0.478 | 0.789 |
| **tabpfn** | **0.908** | **0.126** | **0.408** | **0.842** |

**South Africa only** (n=55)

| model | ROC AUC ↑ | Brier ↓ | log loss ↓ | accuracy ↑ |
|---|---|---|---|---|
| season-histogram | **0.964** | 0.119 | 0.392 | 0.836 |
| logistic | 0.950 | 0.125 | 0.398 | 0.800 |
| **tabpfn** | 0.944 | **0.071** | **0.252** | **0.909** |

In South Africa the bloom season is so regular that the season histogram already ranks sightings well (highest AUC). TabPFN's probabilities are much better calibrated though: Brier score and log loss drop by about 40%. That matters here because the planner uses the probability directly as a weight (`0.4 × P`). The SA test set is small (55), so treat that table as indicative.

TabPFN v2, local CPU, no tuning: 38 s to fit and predict.

## Run it

```bash
cd bloom-model
python -m venv .venv && . .venv/bin/activate            # Windows: .venv\Scripts\activate
pip install --index-url https://download.pytorch.org/whl/cpu torch
pip install -r requirements.txt

python evaluate.py                 # reproduce the table above from the committed train.csv
uvicorn server:app --port 8090     # ~1 min startup on CPU (TabPFN caches the training context), then seconds per call
```

The Spring app calls it when `scout.bloom-model.enabled: true` (the default). If the sidecar isn't running, the planner works exactly as before.

`python build_dataset.py` refreshes `train.csv` from iNaturalist and Open-Meteo (~25 min, because both APIs are paced politely; weather is cached in `data/` so reruns are fast).

## Open-ness and privacy

- **Local by default** (`TABPFN_BACKEND=local`): TabPFN v2 weights run on your CPU; your start point never leaves the machine except as a weather lookup to Open-Meteo, rounded to a ~25 km grid cell.
- `TABPFN_MODEL_VERSION=v2` (default) downloads without sign-in. Newer versions (v2.5+) are gated behind a Hugging Face licence prompt; read their terms before using them.
- `TABPFN_BACKEND=client` + `TABPFN_TOKEN` uses Prior Labs' hosted API instead (for a tiny server without the RAM for torch). That sends tree coordinates to Prior Labs.
- **Offline:** `POST /api/sync` on the Spring app warms the weather cache. On the trail, the sidecar reuses the last week's cached weather; with none at all it still predicts from place + season (TabPFN handles missing values).

Data: iNaturalist contributors (CC licences), Open-Meteo.com (CC BY 4.0).
