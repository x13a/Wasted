# SIMPLUGELITE.COM — SPEX product showcase

A static, dark, premium **product catalog** for SPEX by SIMPLUGELITE.
There is deliberately **no shopping cart, checkout, customer accounts, payment
processing, database, admin area or contact form**. Visitors explore products,
compare models and prices, and then contact SPEX directly via Signal, Threema
or Telegram.

Built with [Astro](https://astro.build) as fully static HTML. The only runtime
JavaScript is three small progressive-enhancement scripts (scroll reveal,
profile switcher, eSIM country selector). The mobile menu uses the native
`popover` attribute and works without JavaScript.

## Commands

```sh
npm install        # install dependencies
npm run dev        # local dev server on http://localhost:4321
npm run build      # build static site into dist/
npm run check      # TypeScript + Astro type check
npm run verify     # check + build + post-build guard rails (see below)
npm run preview    # serve dist/ locally
```

## Changing prices, products and contact links

Every price, product and link lives in **one place** in `src/data/`. Pages and
components only read from these files — change a value once and every page,
card, "from …" label and footer updates on the next build.

| File | What it controls |
| --- | --- |
| `src/data/phones.ts` | SPEX phone models, prices, taglines, badges, optional photos |
| `src/data/kits.ts` | Kit prices (kit names follow the phone names automatically) and kit contents |
| `src/data/router.ts` | SPEX Router quantity pricing and features |
| `src/data/sims.ts` | Ortel / Ay Yildiz SIM quantity pricing |
| `src/data/esim.ts` | eSIM countries and the seven packages per country |
| `src/data/accessories.ts` | Anti-Signal Bag pricing, features and disclaimer |
| `src/data/contact.ts` | Signal / Threema / Telegram / Instagram / Viber links and handles, anti-impersonation text |
| `src/data/shipping.ts` | Shipping rates and delivery notes |
| `src/data/payment.ts` | Payment methods and listed cryptocurrencies (informational only — never add wallet addresses) |
| `src/data/features.ts` | Phone security features, profiles, "Why SPEX", ordering steps |
| `src/data/products.ts` | The six ecosystem categories ("from" prices are computed from the files above) |
| `src/data/site.ts` | Brand copy, taglines, navigation |
| `src/data/legal.ts` | Operator details for the Imprint and Privacy Policy |

Example — change the SPEX 10a price: edit `price: 749` in `src/data/phones.ts`.
The phones page, the model page, the homepage lineup and the kit breakdown all
update.

### Product photography

Devices are currently shown as vector renders. To use real photos, put an image
in `public/images/…` and set `image: '/images/phones/spex-10a.webp'` on the
phone in `src/data/phones.ts`. Self-host all images — the CSP blocks
third-party image hosts.

## Before going live

1. **Complete the Imprint.** Fill in `src/data/legal.ts`. German law (§ 5 DDG)
   requires a complete imprint; `npm run verify` warns while fields are empty.
2. Have the Imprint, Privacy Policy and Terms texts reviewed for your business.
3. Review the badges ("Recommended", "Flagship") and model descriptions in
   `src/data/phones.ts`.

## Security

- **Static only.** No server code, endpoints, database, authentication, forms or
  secrets. Nothing for an attacker to log into or inject into.
- **Security headers** are defined in `public/_headers` (Cloudflare Pages /
  Netlify format): strict CSP (`default-src 'none'`, `script-src 'self'`,
  `style-src 'self'`, `frame-ancestors 'none'`, `form-action 'none'`), HSTS,
  `X-Content-Type-Options`, `Referrer-Policy: no-referrer`, a deny-all
  `Permissions-Policy`, COOP/CORP and `X-Frame-Options`.
- **No inline code.** `astro.config.mjs` forces scripts and styles into external
  files so the CSP never needs `'unsafe-inline'`. `npm run verify` fails the
  build if inline scripts, styles, style attributes or event handlers appear.
- **No third-party requests.** Fonts (Inter) are self-hosted; there is no
  analytics, tracking or CDN code.
- **External links** use `rel="noopener noreferrer external"` and
  `referrerpolicy="no-referrer"`; verify fails on any that don't.
- **Guard rails** in `scripts/verify-build.mjs` also fail on shop wording
  ("add to cart", "buy now"), absolute claims ("unhackable", "untraceable",
  "100% anonymous") and anything that looks like a crypto wallet address.
- **Minimal dependencies:** `astro` and `@fontsource-variable/inter` at runtime
  of the build; `@astrojs/check` and `typescript` for type checking.

## Deploying to Cloudflare Pages

- Root directory: `website`
- Build command: `npm ci && npm run verify`
- Output directory: `dist`
- Node.js: 22+

`public/_headers` is picked up automatically. In the Cloudflare dashboard, keep
features that inject scripts **off** (Rocket Loader, Email Obfuscation, Web
Analytics auto-injection, Zaraz), since the CSP would block them. Enable
"Always Use HTTPS" and a minimum TLS version of 1.2. If you later want HSTS
preloading, add `; preload` to the HSTS header and submit the domain at
hstspreload.org.

For other hosts (nginx, Caddy), copy the headers from `public/_headers` into the
server configuration.
