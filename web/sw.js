/* Wasted Remote app-shell service worker. Version is injected by the build. */
const CACHE_VERSION = "__WASTED_VERSION__";
const CACHE_NAME = `wasted-dashboard-${CACHE_VERSION}`;
const BASE = new URL("./", self.location.href).href;
const INDEX_URL = new URL("index.html", BASE).href;
const APP_ASSETS = [
  BASE,
  INDEX_URL,
  new URL("manifest.webmanifest", BASE).href,
  new URL("icons/icon-192.png", BASE).href,
  new URL("icons/icon-512.png", BASE).href,
  new URL("icons/icon-maskable-512.png", BASE).href,
  new URL("icons/apple-touch-icon-180.png", BASE).href,
  new URL("icons/apple-touch-icon-167.png", BASE).href,
  new URL("icons/apple-touch-icon-152.png", BASE).href,
  new URL("icons/apple-touch-icon-120.png", BASE).href,
  new URL("icons/favicon-32.png", BASE).href,
  new URL("icons/favicon-48.png", BASE).href,
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    (async () => {
      const cache = await caches.open(CACHE_NAME);
      await cache.addAll(APP_ASSETS);
      await self.skipWaiting();
    })(),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      const keys = await caches.keys();
      await Promise.all(
        keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key)),
      );
      await self.clients.claim();
    })(),
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  // Never cache Vite's development graph.
  if (
    url.pathname.startsWith("/@vite/") ||
    url.pathname.startsWith("/src/") ||
    url.pathname.startsWith("/node_modules/") ||
    url.pathname.startsWith("/styles/")
  ) {
    return;
  }

  if (request.mode === "navigate") {
    event.respondWith(
      (async () => {
        try {
          const response = await fetch(request);
          if (response.ok) {
            const cache = await caches.open(CACHE_NAME);
            cache.put(INDEX_URL, response.clone());
          }
          return response;
        } catch {
          return (await caches.match(INDEX_URL)) || Response.error();
        }
      })(),
    );
    return;
  }

  event.respondWith(
    (async () => {
      const cached = await caches.match(request);
      if (cached) return cached;

      const response = await fetch(request);
      if (response.ok) {
        const cache = await caches.open(CACHE_NAME);
        cache.put(request, response.clone());
      }
      return response;
    })(),
  );
});
