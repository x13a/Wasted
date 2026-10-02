import type { QuantityPrice } from './types';

/** Physical SIM cards — single source of truth. */
export interface SimProduct {
  id: string;
  name: string;
  credit: string;
  accent: 'blue' | 'cyan';
  pricing: QuantityPrice[];
}

export const sims: SimProduct[] = [
  {
    id: 'ortel',
    name: 'Ortel',
    credit: '0€ credit',
    accent: 'blue',
    pricing: [
      { qty: 1, price: 15 },
      { qty: 3, price: 40 },
      { qty: 5, price: 65 },
      { qty: 10, price: 120 },
      { qty: 25, price: 275 },
      { qty: 50, price: 525 },
      { qty: 100, price: 950 },
    ],
  },
  {
    id: 'ay-yildiz',
    name: 'Ay Yildiz',
    credit: '10€ credit',
    accent: 'cyan',
    pricing: [
      { qty: 1, price: 20 },
      { qty: 3, price: 55 },
      { qty: 5, price: 90 },
      { qty: 10, price: 170 },
      { qty: 25, price: 400 },
      { qty: 50, price: 750 },
      { qty: 100, price: 1400 },
    ],
  },
];

export const simHighlights = ['Bulk orders available', 'Hamburg Meetups', 'Worldwide Shipping'];

export const simNote =
  'Activation, registration and use are subject to the network provider’s terms and applicable law.';
