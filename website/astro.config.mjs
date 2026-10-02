// @ts-check
import { defineConfig } from 'astro/config';

// Static output only: no server, no database, no auth, no API endpoints.
// Scripts and styles are emitted as external files so the strict CSP in
// public/_headers (script-src 'self'; style-src 'self') never needs
// 'unsafe-inline'.
export default defineConfig({
  site: 'https://simplugelite.com',
  output: 'static',
  trailingSlash: 'never',
  build: {
    format: 'file',
    inlineStylesheets: 'never',
  },
  vite: {
    build: {
      assetsInlineLimit: 0,
    },
  },
  devToolbar: { enabled: false },
});
