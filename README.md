# Jacaranda Scout

**Find this week's best jacaranda blooms near you, get a walk/run/cycle loop through them, then put the phone away.**

Built for the dev.to *Touch Grass* challenge. The planner runs on a laptop with a local open-weight model (Ollama). It plans from open citizen-science data and only speaks up when you reach a tree.

```
"easy 4 km walk from Zoo Lake with the dog"
        │
        ▼
 ┌─────────────────────┐    local open-weight LLM (Ollama, JSON-schema constrained)
 │ IntentParser        │──► {activity: walk, distanceKm: 4, startPlace: "Zoo Lake"}
 └─────────────────────┘
        │  geocode (OSM Nominatim)
        ▼
 ┌─────────────────────┐    iNaturalist sightings (last 21 days) ─► snapshot on disk ─► demo data
 │ DataHub             │    Open-Meteo 7-day forecast           ─► snapshot on disk ─► demo data
 └─────────────────────┘    iNaturalist multi-year flowering histogram
        │                   iNaturalist known trees (any year) ─► TabPFN sidecar: P(flowering today) per tree
        │
        ▼  pure Java, unit-tested
 BloomScorer ─► HotspotClusterer ─► LoopPlanner (orienteering: insertion + 2-opt)
 SeasonCurve (where is this week on the bloom curve?)   BestDayPicker (storms strip blooms: go before)
        │
        ▼
 OSRM foot/bike routing over OpenStreetMap        (optional) local vision model rates sighting photos
        │
        ▼
 FieldGuideWriter: local LLM writes a grounded briefing + one spoken cue per stop (template fallback)
        │
        ▼
 Phone: map → "Pocket mode" (black screen, GPS geofence, speaks each cue)  or  GPX → OsmAnd / watch
```

## Run it

Prereqs: JDK 17+, Maven (or IntelliJ), [Ollama](https://ollama.com).

```powershell
ollama pull qwen2.5:3b          # ~2 GB, runs fine on CPU. Any chat model works: llama3.2:3b, gemma3:4b, phi4-mini
cd jacaranda-scout
mvn spring-boot:run
# open http://localhost:8080
```

Optional, recommended: start the TabPFN bloom model so trees mapped in earlier years count, not just this week's photos (see [bloom-model/README.md](bloom-model/README.md)):

```powershell
cd bloom-model; pip install -r requirements.txt; uvicorn server:app --port 8090
```

No Ollama running? It still works: intent parsing falls back to rules and the briefing to templates. The status pill shows which mode you're in.

### Full stack, end to end

Three processes: Ollama (port 11434), the TabPFN sidecar (8090) and the planner (8080). This is the run used to verify the build on 2026-10-11 (Windows 11 + WSL2, CPU only, 16 GB RAM).

**1. Ollama.** Start the Ollama app and check the chat model is installed. Any small chat model works; this run used `llama3.2:3b`:

```powershell
ollama list                      # needs qwen2.5:3b (the default) or e.g. llama3.2:3b
```

**2. TabPFN sidecar.** Needs `bloom-model/train.csv` (committed; rebuild with `python build_dataset.py`). The first start downloads the TabPFN v2 weights, then fitting takes about a minute on CPU:

```bash
cd bloom-model
uvicorn server:app --host 0.0.0.0 --port 8090      # in WSL, --host 0.0.0.0 lets the Windows JVM reach it
curl localhost:8090/health
# {"ready":true,"backend":"local","model":"tabpfn-v2-local","trainRows":1921,...}
```

**3. Planner.** JDK 17. To use a model other than the default without editing `application.yml`:

```powershell
mvn spring-boot:run "-Dspring-boot.run.arguments=--scout.ollama.model=llama3.2:3b"
```

**4. Check both models are wired up:**

```powershell
curl.exe http://localhost:8080/api/status
# {"ollamaReachable":true,"chatModel":"llama3.2:3b","chatModelInstalled":true,...,
#  "bloomModelEnabled":true,"bloomModel":{"ready":true,"model":"tabpfn-v2-local",...}}
```

**5. Plan a walk:**

```powershell
curl.exe -H "Content-Type: application/json" -d '{\"query\":\"easy 4 km walk from Zoo Lake with the dog\"}' http://localhost:8080/api/plan
curl.exe -o walk.gpx http://localhost:8080/api/plan/<id>/gpx
```

What came back (abridged, peak bloom week):

```
activity walk · target 4 km · route 2.7 km · 35 min · best day Mon 2026-10-12 (Sun: 8 mm rain)
season    peak (week 41 of 41): "Peak week on the historical curve. Go now."
stops     3 known trees around Parkview, each weight 0.33 = 0.4 × P(flowering) 0.825
sources   inaturalist-live · open-meteo-live · osrm-openstreetmap · ollama llama3.2:3b
          · tabpfn-v2-local on 106 known trees
gpx       200 application/gpx+xml, 3 waypoints, 71 track points
```

The request takes 40–60 s on CPU, almost all of it in Ollama (three `/api/chat` calls: intent, briefing, cues, 7–30 s each). Then open http://localhost:8080 in the browser for the map and Pocket mode.

**What to watch for:**
- **RAM.** With the TabPFN sidecar resident, the first Ollama call failed to allocate its 1.3 GB buffer (`unable to allocate CPU_REPACK buffer`). The planner logged it and fell back to rules and templates as designed, and the next request ran on the LLM. On 8 GB machines use a 1.5B model (`qwen2.5:1.5b`) or start Ollama before the sidecar.
- **Fewer stops than asked.** No fresh sightings near Zoo Lake this week, so every stop is a known tree weighted by TabPFN, and the loop came out shorter than the 4 km target.
- **Cue quality with a 3B model.** Cues are grounded in the stop list but sometimes generic ("listen for the sweet scent...") or point at the wrong stop number. A 7–8B model (`llama3:8b`) writes better cues at about twice the latency.

### Sync once, then go offline

```powershell
curl -X POST http://localhost:8080/api/sync      # caches Joburg + Pretoria sightings, histogram, forecast in ./data
```
Then set `scout.offline: true` (or just lose signal). Planning uses the snapshot. The phone keeps the last plan in local storage, and the service worker caches the app shell and the map tiles you've viewed.

### Optional: let a vision model check the photos

Most sightings aren't tagged "Flowering". If you turn this on, a local VLM looks at each recent photo and rates how purple it is, so a green tree photographed yesterday doesn't count as a bloom:

```powershell
ollama pull gemma3:4b
# application.yml → scout.vision.enabled: true
```

### Using it on your phone

Browsers only give GPS to `https://` pages or `localhost`. Two options:

1. **GPX (most open option):** tap *GPX for watch / OsmAnd*, then open it in [OsmAnd](https://osmand.net) (open source, offline maps). Waypoint descriptions carry the cues.
2. **HTTPS profile:** create a self-signed cert once, then run with the `https` profile and browse to `https://<laptop-ip>:8443` on the same Wi-Fi:
   ```powershell
   keytool -genkeypair -alias scout -keyalg RSA -keysize 2048 -validity 365 -storetype PKCS12 -keystore scout.p12 -storepass changeit -dname "CN=jacaranda-scout"
   mvn spring-boot:run -Dspring-boot.run.profiles=https
   ```

## Deploy on Render (share it with a run club)

The laptop setup is the private, offline option. For a group, [`render.yaml`](render.yaml) deploys the same stack as a Render Blueprint (*New > Blueprint*, pick this repo):

| service | what | why |
|---|---|---|
| `jacaranda-scout` (web) | Spring Boot planner + PWA | public **HTTPS** URL, so phones get GPS for Pocket mode with no self-signed cert |
| `scout-ollama` (private) | Ollama with `qwen2.5:1.5b` baked into the image | the open-weight model runs on your own Render service, not a vendor API |
| `scout-bloom-model` (private) | TabPFN sidecar | hosted TabPFN by default (paste `TABPFN_TOKEN` in the dashboard); see below |

Only the planner is public; the models talk to it over Render's private network. Notes:
- `TABPFN_BACKEND=client` because local TabPFN on one shared CPU takes minutes per call. With a bigger instance, build `bloom-model` with `--build-arg TABPFN_BACKEND=local` to keep everything on open weights.
- Snapshots and saved plans live in `/tmp` and reset on redeploy; the phone still keeps its last plan.
- 1.5B instead of 3B keeps the Ollama service on 2 GB RAM. Change `MODEL` in `render/ollama/Dockerfile` and `OLLAMA_MODEL` in `render.yaml` together.

## API

| Method | Path | |
|---|---|---|
| POST | `/api/plan` | `{"query":"5 km run from Melville","lat":-26.17,"lon":28.03}` → plan JSON |
| GET | `/api/plan/{id}/gpx` | GPX 1.1 track + waypoints |
| POST | `/api/sync` | pre-download data for offline use |
| GET | `/api/status` | Ollama reachable? which models are installed? |

## Config (application.yml)

`scout.ollama.model` swaps the model. `half-life-days` sets how fast a sighting goes stale. `cluster-radius-m` sets how close sightings must be to count as the same street. `routing.*` lets you point at your own OSRM container to be fully self-hosted.

## Tests

`mvn test`. `CoreLogicTest` covers scoring, clustering, loop planning, season curve, the storm/best-day logic, intent parsing and GPX. `PlanApiTest` runs the whole API with **no network and no model**.

## Why open matters here (notes for the post)

- **No signal, no data bundle:** jacaranda streets are in the suburbs, but data in SA is expensive and load-shedding takes towers down. Model, planner and snapshot all run on the laptop, and the phone holds the plan.
- **Location privacy:** your start point and walking habits go to `localhost:11434`, not a vendor. The vision option sends photos to localhost, not to a cloud API.
- **Swap and shrink:** the job is narrow (parse a sentence, write 6 grounded cues). A 3B open model with JSON-schema-constrained decoding does it reliably. A frontier API would be overkill, and paying per call for a hobby app makes no sense.
- **Open data all the way down:** iNaturalist (CC-licensed community science), OpenStreetMap + OSRM, Open-Meteo. Every observation you add on iNaturalist makes next week's plan better for everyone.
- **Tabular foundation model, not a chatbot:** Joburg has ~5 jacaranda sightings per season on iNaturalist, so "where was one photographed this week" doesn't work. TabPFN (open weights, runs on the laptop CPU) learns *when* jacaranda flower from ~1,900 annotated sightings worldwide plus weather, then scores the ~460 trees already mapped around Joburg and Pretoria. No training loop, no GPU, and the result is a probability the planner can reason with.
- **Where closed would be "better":** a big hosted model writes prettier prose. But grounding matters more than prose: the LLM only sees numbers the Java core computed, so it can't invent a street.

## Attribution

Sightings: iNaturalist contributors (CC licences). Map & routing data © OpenStreetMap contributors (ODbL). Weather: Open-Meteo.com (CC BY 4.0). Demo data (shown with "(demo)" labels) is synthetic and only used when no live data or snapshot is available.

## License

Code: [MIT](LICENSE). The data it downloads keeps its own licences (see Attribution): `bloom-model/train.csv` is derived from iNaturalist observations (CC licences, per observation) and Open-Meteo weather (CC BY 4.0).
