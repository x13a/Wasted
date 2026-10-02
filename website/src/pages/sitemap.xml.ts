import type { APIRoute } from 'astro';
import { phones } from '../data/phones';

const routes = [
  '/',
  '/phones',
  ...phones.map((p) => `/phones/${p.id}`),
  '/router',
  '/kits',
  '/sim',
  '/esim',
  '/accessories',
  '/contact',
  '/shipping',
  '/imprint',
  '/privacy-policy',
  '/terms',
];

export const GET: APIRoute = ({ site }) => {
  const urls = routes.map((r) => `  <url><loc>${new URL(r, site)}</loc></url>`).join('\n');
  const body = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls}\n</urlset>\n`;
  return new Response(body, { headers: { 'Content-Type': 'application/xml' } });
};
