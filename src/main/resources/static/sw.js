/* Offline support: app shell + Leaflet + map tiles you've already viewed. API calls go network-first. */
const SHELL = 'scout-shell-v1';
const TILES = 'scout-tiles-v1';
const MAX_TILES = 600;
const SHELL_FILES = [
  './', 'index.html', 'style.css', 'app.js', 'manifest.webmanifest', 'icon.svg',
  'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css',
  'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(SHELL).then(c => c.addAll(SHELL_FILES)).catch(() => {}));
  self.skipWaiting();
});

self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET') return;

  if (url.hostname === 'tile.openstreetmap.org') {
    e.respondWith(caches.open(TILES).then(async (cache) => {
      const hit = await cache.match(e.request);
      if (hit) return hit;
      const res = await fetch(e.request);
      if (res.ok) {
        cache.put(e.request, res.clone());
        cache.keys().then(keys => { if (keys.length > MAX_TILES) cache.delete(keys[0]); });
      }
      return res;
    }));
    return;
  }

  if (url.pathname.includes('/api/')) {
    e.respondWith(fetch(e.request).then(res => {
      if (res.ok && url.pathname.endsWith('/gpx')) {
        const copy = res.clone();
        caches.open(SHELL).then(c => c.put(e.request, copy));
      }
      return res;
    }).catch(() => caches.match(e.request)));
    return;
  }

  e.respondWith(caches.match(e.request).then(hit => hit || fetch(e.request).then(res => {
    if (res.ok && url.origin === self.location.origin) {
      const copy = res.clone();
      caches.open(SHELL).then(c => c.put(e.request, copy));
    }
    return res;
  })));
});
