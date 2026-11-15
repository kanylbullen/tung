// Replaces the old copy's service worker at kanylbullen.github.io/tung/
// (issue #153). A browser that installed the app there checks sw.js for an
// update on its next visit, gets this one, and it drops the cached shell,
// unregisters itself and reloads the open pages, which then load the
// "moved" page from the network instead of the old app from the cache.
self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    // Only a page this worker controls can be navigated.
    await self.clients.claim();
    const keys = await caches.keys();
    await Promise.all(keys.filter((key) => key.startsWith('tung-')).map((key) => caches.delete(key)));
    await self.registration.unregister();
    const clients = await self.clients.matchAll({ type: 'window' });
    clients.forEach((client) => client.navigate(client.url));
  })());
});
