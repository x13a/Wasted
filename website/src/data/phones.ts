/**
 * SPEX Phones — single source of truth for models and prices.
 * Change a price here and the phones page, model pages, homepage,
 * kits comparison and every "from …" label update automatically.
 */
export interface PhoneModel {
  id: string;
  name: string;
  price: number;
  /** Short positioning line shown on cards. */
  tagline: string;
  description: string;
  /** Visual finish used by the device render. */
  finish: 'graphite' | 'slate' | 'obsidian' | 'midnight' | 'titanium';
  badge?: string;
  /** Optional product photo in /public (e.g. "/images/phones/pixel-10a.webp"). Falls back to the device render. */
  image?: string;
  /** Optional higher-resolution versions of the photo, e.g. "/images/phones/pixel-7a@2x.webp 2x". */
  imageSrcset?: string;
}

export const phones: PhoneModel[] = [
  {
    id: 'spex-7a',
    name: 'SPEX 7a',
    price: 499,
    tagline: 'The essential SPEX.',
    description: 'The most accessible way into the SPEX ecosystem — GrapheneOS, configured and ready for daily private use.',
    finish: 'graphite',
    image: '/images/phones/pixel-7a.webp',
    imageSrcset: '/images/phones/pixel-7a.webp 1x, /images/phones/pixel-7a@2x.webp 2x',
  },
  {
    id: 'spex-8a',
    name: 'SPEX 8a',
    price: 599,
    tagline: 'Balanced. Configured.',
    description: 'A balanced everyday device with a newer hardware generation and the full SPEX privacy configuration.',
    finish: 'slate',
    image: '/images/phones/pixel-8a.webp',
    imageSrcset: '/images/phones/pixel-8a.webp 1x, /images/phones/pixel-8a@2x.webp 2x',
  },
  {
    id: 'spex-9a',
    name: 'SPEX 9a',
    price: 699,
    tagline: 'More generation. Same discipline.',
    description: 'A newer generation for customers who want extra headroom with the same privacy-first setup.',
    finish: 'obsidian',
    image: '/images/phones/pixel-9a.webp',
    imageSrcset: '/images/phones/pixel-9a.webp 1x, /images/phones/pixel-9a@2x.webp 2x',
  },
  {
    id: 'spex-10a',
    name: 'SPEX 10a',
    price: 749,
    tagline: 'The current standard.',
    description: 'The latest a-series SPEX — modern hardware, long-term outlook and the complete SPEX configuration.',
    finish: 'midnight',
    image: '/images/phones/pixel-10a.webp',
    imageSrcset: '/images/phones/pixel-10a.webp 1x, /images/phones/pixel-10a@2x.webp 2x',
    badge: 'Recommended',
  },
  {
    id: 'spex-10-pro',
    name: 'SPEX 10 Pro',
    price: 1049,
    tagline: 'The flagship SPEX.',
    description: 'The most capable SPEX device — premium hardware and display, configured for demanding private use.',
    finish: 'titanium',
    image: '/images/phones/pixel-10-pro.webp',
    imageSrcset: '/images/phones/pixel-10-pro.webp 1x, /images/phones/pixel-10-pro@2x.webp 2x',
    badge: 'Flagship',
  },
];

export const phoneById = (id: string) => phones.find((p) => p.id === id);

/** What every SPEX phone setup is configured with (see features.ts for full descriptions). */
export const phoneIncludes = [
  'GrapheneOS installed',
  'Privacy & security configuration',
  'Profiles prepared on request',
  'Personal handover & support',
];
