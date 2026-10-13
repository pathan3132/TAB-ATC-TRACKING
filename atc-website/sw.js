/* ATC Transport – service worker
 * Strategy: network-first (hamesha naya page), network na ho to cache se.
 * API calls (Apps Script) kabhi cache nahi hoti, kyunki wo doosre domain par hain.
 * Naya version deploy karne par CACHE naam badal dein. */
const CACHE = 'atc-v3';
const SHELL = [
  'track.html', 'admin.html', 'index.html', 'style.css', 'common.js', 'config.js',
  'manifest.json', 'manifest-admin.json',
  'icons/atc-logo.png', 'icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png'
];

self.addEventListener('install', function (e) {
  e.waitUntil(
    caches.open(CACHE).then(function (c) {
      return Promise.all(SHELL.map(function (u) { return c.add(u).catch(function () {}); }));
    }).then(function () { return self.skipWaiting(); })
  );
});

self.addEventListener('activate', function (e) {
  e.waitUntil(
    caches.keys().then(function (keys) {
      return Promise.all(keys.filter(function (k) { return k !== CACHE; }).map(function (k) { return caches.delete(k); }));
    }).then(function () { return self.clients.claim(); })
  );
});

self.addEventListener('fetch', function (e) {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;   // sirf apni site ki files

  // Token wale URL ko cache key mein na rakhein: sirf path.
  const key = new Request(url.origin + url.pathname);

  e.respondWith(
    fetch(req).then(function (res) {
      if (res && res.ok) {
        const copy = res.clone();
        caches.open(CACHE).then(function (c) { c.put(key, copy); });
      }
      return res;
    }).catch(function () {
      return caches.match(key).then(function (hit) {
        return hit || caches.match('track.html');
      });
    })
  );
});
