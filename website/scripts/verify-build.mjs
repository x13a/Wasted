// Post-build guard rails for the static showcase. Run via `npm run verify`.
// Fails the build if the output would break the strict CSP, leak shop wording,
// make absolute security claims, open unsafe external links or expose wallet addresses.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const dist = new URL('../dist/', import.meta.url).pathname;
const errors = [];
const warnings = [];

const walk = (dir) =>
  readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });

const files = walk(dist);
const html = files.filter((f) => f.endsWith('.html'));
if (html.length === 0) errors.push('No HTML found in dist/. Run `astro build` first.');

const forbiddenWords = [/add to cart/i, /buy now/i, /shopping basket/i, /\bunhackable\b/i, /\buntraceable\b/i, /100\s*%\s*anonymous/i];
const walletPatterns = [/\b(bc1|[13])[a-km-zA-HJ-NP-Z1-9]{25,39}\b/, /\b0x[a-fA-F0-9]{40}\b/];

for (const file of html) {
  const rel = relative(dist, file);
  const src = readFileSync(file, 'utf8');
  const text = src.replace(/<[^>]+>/g, ' ');

  for (const m of src.matchAll(/<script\b([^>]*)>/gi)) {
    if (!/\bsrc=/.test(m[1])) errors.push(`${rel}: inline <script> would be blocked by CSP (script-src 'self').`);
  }
  if (/<style\b/i.test(src)) errors.push(`${rel}: inline <style> would be blocked by CSP (style-src 'self').`);
  if (/\sstyle="/i.test(src)) errors.push(`${rel}: inline style attribute would be blocked by CSP.`);
  if (/\son[a-z]+="/i.test(src)) errors.push(`${rel}: inline event handler found.`);

  for (const re of forbiddenWords) if (re.test(text)) errors.push(`${rel}: forbidden wording ${re}.`);
  for (const re of walletPatterns) if (re.test(text)) errors.push(`${rel}: looks like a wallet address (${re}).`);

  for (const m of src.matchAll(/<a\b[^>]*href="(https?:[^"]+)"[^>]*>/gi)) {
    const tag = m[0];
    const rel_ = /rel="([^"]*)"/.exec(tag)?.[1] ?? '';
    if (!rel_.includes('noopener') || !rel_.includes('noreferrer')) errors.push(`${rel}: external link without rel="noopener noreferrer": ${m[1]}`);
  }
  for (const m of src.matchAll(/(?:src|href)="(https?:\/\/[^"]+)"/gi)) {
    const tagStart = src.slice(src.lastIndexOf('<', m.index), m.index);
    if (/^<(link|script|img|iframe|source|video|audio)/i.test(tagStart) && !/rel="canonical"/.test(tagStart) && !m[1].startsWith('https://simplugelite.com/')) {
      errors.push(`${rel}: third-party resource would be blocked by CSP: ${m[1]}`);
    }
  }
}

// Contact links must come through on the contact page exactly as defined in data/contact.ts.
const contactSrc = readFileSync(new URL('../src/data/contact.ts', import.meta.url), 'utf8');
const contactPage = readFileSync(join(dist, 'contact.html'), 'utf8').replaceAll('&amp;', '&');
for (const m of contactSrc.matchAll(/url:\s*'([^']+)'/g)) {
  if (!contactPage.includes(m[1])) errors.push(`contact.html: missing official link ${m[1]}`);
}

// Headers file must ship with the build.
if (!files.some((f) => f.endsWith('_headers'))) errors.push('dist/_headers missing — security headers would not be applied.');

// Imprint completeness (legal requirement in Germany) — warning only.
const legal = readFileSync(new URL('../src/data/legal.ts', import.meta.url), 'utf8');
for (const field of ['name', 'street', 'city', 'email', 'responsible']) {
  if (new RegExp(`\\b${field}:\\s*''`).test(legal)) warnings.push(`legal.ts: operator.${field} is empty — complete the Imprint before going live.`);
}

for (const w of warnings) console.warn(`⚠  ${w}`);
if (errors.length) {
  for (const e of errors) console.error(`✗ ${e}`);
  console.error(`\nverify-build: ${errors.length} problem(s) found.`);
  process.exit(1);
}
console.log(`✓ verify-build: ${html.length} pages checked — CSP-safe, no shop wording, safe external links.`);
