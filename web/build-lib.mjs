import { build } from "esbuild";
import { readFile } from "node:fs/promises";

import { renderConfigBlock } from "./env-config.mjs";

/** Builds the self-contained dashboard HTML with an injected CONFIG block. */
export async function renderDashboardHtml({ supabaseUrl, supabasePublishableKey, dashboardUrl = "", version }) {
  const bundle = await build({
    entryPoints: ["src/main.js"],
    bundle: true,
    format: "esm",
    minify: true,
    sourcemap: false,
    target: ["es2020"],
    write: false,
  });

  const js = bundle.outputFiles[0].text.replace(/<\/script/gi, "<\\/script");
  const css = await readFile("styles/main.css", "utf8");
  let html = await readFile("index.html", "utf8");

  const configBlock = renderConfigBlock({
    supabaseUrl,
    supabasePublishableKey,
    dashboardUrl,
    version,
  });

  // Replacement functions keep `$&`/`$1` sequences inside the bundle literal.
  html = html.replace("<!-- __WASTED_CONFIG__ -->", () => configBlock);
  html = html.replace(
    '<link rel="stylesheet" href="styles/main.css">',
    () => `<style>\n${css}</style>`,
  );
  html = html.replace(
    '<script type="module" src="assets/app.js" defer></script>',
    () => `<script type="module">\n${js}</script>`,
  );
  return html;
}
