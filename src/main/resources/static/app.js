/* Jacaranda Scout: keep screen time short. Plan, pocket the phone, listen. */
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const DEFAULT_START = [-26.17, 28.03];
  const ARRIVE_METERS = 60;
  const store = {
    get(k) { try { return JSON.parse(localStorage.getItem(k)); } catch { return null; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* private mode */ } }
  };

  let start = null;          // [lat, lon] chosen by GPS or tap
  let currentPlan = null;

  // ---------- map ----------
  const hasLeaflet = typeof L !== 'undefined';
  let map, layers;
  if (hasLeaflet) {
    map = L.map('map', { zoomControl: false }).setView(DEFAULT_START, 13);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors'
    }).addTo(map);
    layers = L.layerGroup().addTo(map);
    map.on('click', (e) => setStart([e.latlng.lat, e.latlng.lng]));
  } else {
    $('map').textContent = 'Map unavailable offline. Your route, cues and GPX still work.';
    $('map').style.cssText += 'display:flex;align-items:center;justify-content:center;padding:16px;text-align:center;color:#555';
  }

  let startMarker = null;
  function setStart(ll) {
    start = ll;
    if (!hasLeaflet) return;
    if (startMarker) startMarker.remove();
    startMarker = L.marker(ll, { icon: L.divIcon({ className: 'start-pin', iconSize: [18, 18] }) }).addTo(map);
  }

  // ---------- status ----------
  fetch('api/status').then(r => r.json()).then(s => {
    const el = $('status');
    if (s.ollamaReachable && s.chatModelInstalled) {
      el.textContent = 'local AI: ' + s.chatModel;
      el.className = 'status ok';
    } else if (s.ollamaReachable) {
      el.textContent = 'run: ollama pull ' + s.chatModel;
      el.className = 'status off';
    } else {
      el.textContent = 'no local model: templates';
      el.className = 'status off';
    }
  }).catch(() => { $('status').textContent = 'offline'; $('status').className = 'status off'; });

  // ---------- location ----------
  $('locate').addEventListener('click', () => {
    if (!navigator.geolocation) return alertLite('This browser has no location access. Tap the map instead.');
    $('locate').textContent = 'Locating…';
    navigator.geolocation.getCurrentPosition(
      (p) => {
        setStart([p.coords.latitude, p.coords.longitude]);
        if (hasLeaflet) map.setView(start, 14);
        $('locate').textContent = 'Location set';
      },
      () => {
        $('locate').textContent = 'Use my location';
        alertLite('Location blocked. Over plain http only localhost may use GPS. Tap the map instead, or see README for HTTPS.');
      },
      { enableHighAccuracy: true, timeout: 10000 });
  });

  function alertLite(msg) {
    $('briefing').textContent = msg;
    $('result').hidden = false;
  }

  // ---------- plan ----------
  $('ask').addEventListener('submit', async (e) => {
    e.preventDefault();
    const go = $('go');
    go.disabled = true;
    go.textContent = 'Asking the local model…';
    try {
      const body = { query: $('query').value, lat: start ? start[0] : null, lon: start ? start[1] : null };
      const res = await fetch('api/plan', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
      });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const plan = await res.json();
      store.set('lastPlan', plan);
      render(plan);
    } catch (err) {
      const last = store.get('lastPlan');
      if (last) {
        render(last);
        $('meta').textContent += ' · offline: showing your last saved plan';
      } else {
        alertLite('Could not reach the planner (' + err.message + ').');
      }
    } finally {
      go.disabled = false;
      go.textContent = 'Find blooms';
    }
  });

  function render(plan) {
    currentPlan = plan;
    $('result').hidden = false;
    $('headline').textContent = plan.headline;
    $('meta').textContent = `${plan.routeKm} km ${plan.activity} · about ${plan.durationMin} min · ${plan.stops.length} bloom stop${plan.stops.length === 1 ? '' : 's'}`;
    $('briefing').textContent = plan.briefing;

    const storm = $('storm');
    storm.hidden = !plan.stormWarning;
    storm.textContent = plan.stormWarning || '';

    $('season').textContent = 'Season: ' + plan.season.phase + '. ' + plan.season.note +
      (plan.bloomChance != null ? ` Bloom model: ${Math.round(plan.bloomChance * 100)}% chance of flowers here today.` : '');

    const days = $('days');
    days.innerHTML = '';
    const bestScore = Math.max(...plan.days.map(d => d.score));
    const bestIdx = plan.days.findIndex(d => d.score === bestScore);
    plan.days.forEach((d, i) => {
      const el = document.createElement('div');
      el.className = 'day' + (i === bestIdx ? ' best' : '');
      el.title = d.reason;
      el.innerHTML = `<b>${d.day}</b><div class="bar-fill" style="width:${Math.round(d.score * 100)}%"></div>`;
      days.appendChild(el);
    });

    const ol = $('stops');
    ol.innerHTML = '';
    plan.stops.forEach(s => {
      const li = document.createElement('li');
      const text = document.createElement('div');
      const label = document.createElement('div');
      label.className = 'label';
      label.textContent = s.label;
      const cue = document.createElement('div');
      cue.className = 'cue';
      cue.textContent = s.cue;
      text.append(label, cue);
      li.appendChild(text);
      if (s.photoUrl) {
        const img = document.createElement('img');
        img.src = s.photoUrl;
        img.alt = 'Recent sighting photo';
        img.loading = 'lazy';
        li.appendChild(img);
      }
      ol.appendChild(li);
    });

    $('gpx').href = `api/plan/${plan.id}/gpx`;
    const src = plan.sources;
    $('sources').textContent =
      `Data: ${src.data} · Weather: ${src.weather} · Route: ${src.routing} · Words: ${src.model}` +
      (src.visionUsed ? ' · photos checked by local vision model' : '') +
      (src.bloomModel ? ` · Bloom chance: ${src.bloomModel}` : '') + '. ' + src.attribution;

    drawMap(plan);
  }

  function drawMap(plan) {
    if (!hasLeaflet) return;
    layers.clearLayers();
    const maxScore = Math.max(1, ...plan.hotspots.map(h => h.score));
    plan.hotspots.forEach(h => {
      const size = 14 + 34 * Math.sqrt(h.score / maxScore);
      L.marker([h.lat, h.lon], {
        icon: L.divIcon({ className: 'bloom-pin', iconSize: [size, size] }),
        interactive: false, keyboard: false
      }).addTo(layers);
    });
    if (plan.geometry.length > 1) {
      L.polyline(plan.geometry, { color: '#7b4fb8', weight: 5, opacity: .85 }).addTo(layers);
    }
    plan.stops.forEach(s => {
      L.marker([s.lat, s.lon], {
        icon: L.divIcon({ className: 'stop-pin', html: String(s.index), iconSize: [28, 28] })
      }).bindPopup(`<b>${escapeHtml(s.label)}</b><br>${escapeHtml(s.cue)}`).addTo(layers);
    });
    setStart([plan.startLat, plan.startLon]);
    const pts = plan.geometry.length ? plan.geometry : [[plan.startLat, plan.startLon]];
    map.invalidateSize();
    map.fitBounds(L.latLngBounds(pts).pad(0.15), { maxZoom: 16 });
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  // ---------- pocket mode: black screen, spoken cues on arrival ----------
  let watchId = null;
  let wakeLock = null;
  let visited = new Set();

  $('pocket').addEventListener('click', async () => {
    if (!currentPlan || !currentPlan.stops.length) return;
    visited = new Set();
    $('pocketView').hidden = false;
    $('pocketNext').textContent = currentPlan.stops[0].label;
    $('pocketDist').textContent = 'locating…';
    try { if ('wakeLock' in navigator) wakeLock = await navigator.wakeLock.request('screen'); } catch { /* optional */ }
    speak('Pocket mode on. ' + currentPlan.briefing + ' I will speak at each of ' + currentPlan.stops.length + ' stops.');
    if (!navigator.geolocation) return;
    watchId = navigator.geolocation.watchPosition(onPosition, () => {
      $('pocketDist').textContent = 'no GPS';
    }, { enableHighAccuracy: true, maximumAge: 5000 });
  });

  function onPosition(p) {
    const here = [p.coords.latitude, p.coords.longitude];
    const next = currentPlan.stops.find(s => !visited.has(s.index));
    if (!next) {
      $('pocketNext').textContent = 'Loop complete';
      $('pocketDist').textContent = 'Head home';
      return;
    }
    const d = meters(here, [next.lat, next.lon]);
    $('pocketNext').textContent = next.label;
    $('pocketDist').textContent = d < 1000 ? Math.round(d) + ' m' : (d / 1000).toFixed(1) + ' km';
    if (d <= ARRIVE_METERS) {
      visited.add(next.index);
      if (navigator.vibrate) navigator.vibrate([200, 100, 200]);
      speak(next.cue);
      if (visited.size === currentPlan.stops.length) {
        setTimeout(() => speak('That was the last bloom stop. Enjoy the walk back.'), 6000);
      }
    }
  }

  let lastTap = 0;
  $('pocketView').addEventListener('click', () => {
    const now = Date.now();
    if (now - lastTap < 400) exitPocket();
    lastTap = now;
  });

  function exitPocket() {
    $('pocketView').hidden = true;
    if (watchId !== null) navigator.geolocation.clearWatch(watchId);
    watchId = null;
    if (wakeLock) wakeLock.release().catch(() => {});
    wakeLock = null;
    if ('speechSynthesis' in window) speechSynthesis.cancel();
  }

  function speak(text) {
    if (!('speechSynthesis' in window)) return;
    const u = new SpeechSynthesisUtterance(text);
    u.lang = 'en-ZA';
    u.rate = 0.95;
    speechSynthesis.speak(u);
  }

  function meters(a, b) {
    const R = 6371000, toRad = Math.PI / 180;
    const dLat = (b[0] - a[0]) * toRad, dLon = (b[1] - a[1]) * toRad;
    const x = Math.sin(dLat / 2) ** 2 + Math.cos(a[0] * toRad) * Math.cos(b[0] * toRad) * Math.sin(dLon / 2) ** 2;
    return 2 * R * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));
  }

  // ---------- offline ----------
  const last = store.get('lastPlan');
  if (last) render(last);
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
})();
