import { cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { renderDashboardHtml } from "./build-lib.mjs";
import { dashboardEnv } from "./env-config.mjs";

const config = dashboardEnv("production");
const version = config.version;

await rm("dist", { recursive: true, force: true });
await mkdir("dist", { recursive: true });

const html = await renderDashboardHtml({
  supabaseUrl: config.supabaseUrl,
  supabasePublishableKey: config.supabasePublishableKey,
  dashboardUrl: config.dashboardUrl,
  version,
});

await writeFile("dist/index.html", html);
await cp("_headers", "dist/_headers");
await cp("manifest.webmanifest", "dist/manifest.webmanifest");
await cp("icons", "dist/icons", { recursive: true });

const serviceWorker = await readFile("sw.js", "utf8");
const buildHash = createHash("sha256").update(html).digest("hex").slice(0, 12);
await writeFile(
  "dist/sw.js",
  serviceWorker.replaceAll("__WASTED_VERSION__", `${version}-${buildHash}`),
);

console.log("Built dist/index.html, manifest, service worker, icons, and _headers.");
