// The service worker: the app shell is cached at install, so the app opens
// without a network, and served cache-first after that. @buildId@ is
// replaced at build (web/build.gradle.kts), so every build is a new cache and
// the old one is dropped when the new worker takes over. Requests to the
// server (another origin, or anything that is not a GET) are never touched:
// sync and sign-in always go to the network.
const VERSION = '@buildId@';
const CACHE = 'tung-' + VERSION;
const SHELL = [
  './',
  'index.html',
  'tung.js?v=' + VERSION,
  'styles.css?v=' + VERSION,
  'manifest.webmanifest',
  'fonts/archivo_latin.woff2',
  'catalog/core.json',
  'icons/icon.svg',
  'icons/icon-192.png',
  'icons/icon-512.png',
  'icons/icon-512-maskable.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  // A page load anywhere under the scope is the shell.
  if (request.mode === 'navigate') {
    event.respondWith(caches.match('index.html').then((cached) => cached || fetch(request)));
    return;
  }
  event.respondWith(
    caches.match(request).then((cached) => cached || fetch(request)),
  );
});
