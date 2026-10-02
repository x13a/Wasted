import type { QuantityPrice } from './types';

/** Privacy accessories — single source of truth. */
export const antiSignalBag = {
  id: 'anti-signal-bag',
  name: 'Anti-Signal Bag',
  headline: 'Signal isolation you can carry.',
  description:
    'A shielded pouch for smartphones, car keys and small electronics. Close it, and the device inside is isolated from most wireless signals.',
  pricing: [
    { qty: 1, price: 19 },
    { qty: 2, price: 35 },
    { qty: 3, price: 49 },
    { qty: 5, price: 79 },
    { qty: 10, price: 149 },
  ] satisfies QuantityPrice[],
  features: [
    { title: 'Signal blocking', text: 'Shielding layers designed to attenuate mobile, Wi-Fi, Bluetooth and GPS signals.', icon: 'signal-off' },
    { title: 'RFID protection', text: 'Helps shield contactless cards and RFID chips from unwanted reads.', icon: 'card' },
    { title: 'Smartphones & electronics', text: 'Sized for phones and small devices.', icon: 'phone' },
    { title: 'Car keys', text: 'Helps protect keyless-entry fobs against relay attacks.', icon: 'key' },
    { title: 'Portable', text: 'Light and flat enough for a jacket or bag.', icon: 'portable' },
    { title: 'Discreet', text: 'Understated design that does not draw attention.', icon: 'eye-off' },
  ],
  disclaimer:
    'Actual attenuation varies by device, frequency band, how the bag is closed and the product construction. Test with your own device; no shielding product can guarantee complete isolation in every situation.',
};
