import { fileURLToPath } from "node:url";

import { loadEnv } from "vite";

const repoRoot = fileURLToPath(new URL("..", import.meta.url));

/**
 * Loads only the allowlisted public dashboard values from the repository-root .env.
 * Backend passwords and account credentials are deliberately never exposed to the page.
 */
export function dashboardEnv(mode = "development") {
  const env = loadEnv(mode, repoRoot, "");
  return {
    supabaseUrl: (env.SUPABASE_URL ?? "").trim(),
    supabasePublishableKey: (env.SUPABASE_PUBLISHABLE_KEY ?? "").trim(),
    dashboardUrl: (env.DASHBOARD_URL ?? "").trim(),
    version: (env.WASTED_VERSION ?? "3.0.0").trim(),
  };
}

/** Single config-block renderer shared by Vite and the production build. */
export function renderConfigBlock(config) {
  const payload = {
    supabaseUrl: config.supabaseUrl,
    supabasePublishableKey: config.supabasePublishableKey,
    productName: "Wasted Remote",
    version: config.version,
    dashboardUrl: config.dashboardUrl,
    privacyUrl: "",
    supportUrl: "",
  };
  const json = JSON.stringify(payload, null, 2).replace(/<\/script/gi, "<\\/script");
  return `<script>
  /* CONFIG - generated. Do not edit. */
  window.WASTED_CONFIG = ${json};
</script>`;
}
