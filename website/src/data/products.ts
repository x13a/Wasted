/**
 * The SPEX ecosystem overview. "From" prices are derived from the product
 * data files, so they never drift out of sync.
 */
import { phones } from './phones';
import { router } from './router';
import { kits } from './kits';
import { sims } from './sims';
import { allEsimPrices } from './esim';
import { antiSignalBag } from './accessories';

export type CategoryVisual = 'phone' | 'router' | 'kit' | 'sim' | 'esim' | 'bag';

export interface Category {
  id: string;
  name: string;
  text: string;
  href: string;
  visual: CategoryVisual;
  fromPrice: number;
}

export const categories: Category[] = [
  {
    id: 'phones',
    name: 'SPEX Phones',
    text: 'Secure smartphones configured by SPEX.',
    href: '/phones',
    visual: 'phone',
    fromPrice: Math.min(...phones.map((p) => p.price)),
  },
  {
    id: 'router',
    name: 'SPEX Router',
    text: 'Portable privacy-focused mobile connectivity.',
    href: '/router',
    visual: 'router',
    fromPrice: Math.min(...router.pricing.map((p) => p.price)),
  },
  {
    id: 'kits',
    name: 'SPEX Kits',
    text: 'Complete SPEX setups in one package.',
    href: '/kits',
    visual: 'kit',
    fromPrice: Math.min(...kits.map((k) => k.price)),
  },
  {
    id: 'sim',
    name: 'SIM Cards',
    text: 'Physical connectivity solutions and bulk quantities.',
    href: '/sim',
    visual: 'sim',
    fromPrice: Math.min(...sims.flatMap((s) => s.pricing.map((p) => p.price))),
  },
  {
    id: 'esim',
    name: 'SPEX Data eSIM',
    text: 'Fast digital mobile data for multiple countries.',
    href: '/esim',
    visual: 'esim',
    fromPrice: Math.min(...allEsimPrices),
  },
  {
    id: 'accessories',
    name: 'Privacy Accessories',
    text: 'Physical privacy accessories for devices and electronics.',
    href: '/accessories',
    visual: 'bag',
    fromPrice: Math.min(...antiSignalBag.pricing.map((p) => p.price)),
  },
];
