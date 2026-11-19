import test from "node:test";
import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";

test("PWA manifest has installable identity, scope, and icons", async () => {
  const manifest = JSON.parse(await readFile(new URL("../manifest.webmanifest", import.meta.url)));
  assert.ok(manifest.name);
  assert.ok(manifest.short_name);
  assert.equal(manifest.start_url, "./");
  assert.equal(manifest.scope, "./");
  assert.equal(manifest.display, "standalone");
  for (const size of ["192x192", "512x512"]) {
    assert.ok(manifest.icons.some((icon) => icon.sizes === size));
  }
});

test("PWA shell files and icons are present", async () => {
  for (const file of [
    "sw.js",
    "manifest.webmanifest",
    "icons/icon-192.png",
    "icons/icon-512.png",
    "icons/icon-maskable-512.png",
    "icons/apple-touch-icon-180.png",
  ]) {
    await access(new URL(`../${file}`, import.meta.url));
  }
  const html = await readFile(new URL("../index.html", import.meta.url), "utf8");
  assert.match(html, /rel="manifest"/);
  assert.match(html, /serviceWorker\.register/);
  const sw = await readFile(new URL("../sw.js", import.meta.url), "utf8");
  assert.match(sw, /__WASTED_VERSION__/);
  assert.match(sw, /caches\.match\(INDEX_URL\)/);
});
