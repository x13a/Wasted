import { cp, mkdir, readFile, writeFile } from "node:fs/promises";
import { renderDashboardHtml } from "./build-lib.mjs";

const html = await renderDashboardHtml({
  supabaseUrl: "__WASTED_SUPABASE_URL__",
  supabasePublishableKey: "__WASTED_PUBLISHABLE_KEY__",
  dashboardUrl: "__WASTED_DASHBOARD_URL__",
  version: "__WASTED_VERSION__",
});

const headers = await readFile("_headers", "utf8");
const serviceWorker = await readFile("sw.js", "utf8");
const assetsDir = "../app/src/main/assets";
const pwaDir = `${assetsDir}/pwa`;

await mkdir(pwaDir, { recursive: true });
await mkdir(`${pwaDir}/icons`, { recursive: true });
await writeFile(`${assetsDir}/dashboard-template.html`, html);
await writeFile(`${assetsDir}/dashboard_headers.txt`, headers);
await writeFile(`${pwaDir}/sw.js`, serviceWorker);
await cp("manifest.webmanifest", `${pwaDir}/manifest.webmanifest`);
await cp("icons", `${pwaDir}/icons`, { recursive: true });

console.log("Built dashboard template, headers, manifest, service worker, and icons.");
