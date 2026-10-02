import type { QuantityPrice } from './types';

/** SPEX Router — single source of truth. */
export const router = {
  name: 'SPEX Router',
  headline: 'Secure mobile connectivity.',
  description:
    'A portable mobile Wi-Fi router, pre-configured by SPEX with VPN protection. Insert a SIM, power on, and connect your devices through one private tunnel.',
  pricing: [
    { qty: 1, price: 349 },
    { qty: 2, price: 649 },
    { qty: 3, price: 929 },
    { qty: 5, price: 1499 },
    { qty: 10, price: 2899 },
  ] satisfies QuantityPrice[],
  features: [
    { title: 'Mullvad VPN — 1 Year', text: 'One year of Mullvad VPN configured on the router.', icon: 'vpn' },
    { title: 'Mobile Wi-Fi', text: 'Your own private hotspot, wherever there is mobile coverage.', icon: 'wifi' },
    { title: 'VPN protection', text: 'Connected devices route their traffic through the VPN tunnel.', icon: 'shield' },
    { title: 'Privacy-focused configuration', text: 'Settings chosen with privacy in mind from the first boot.', icon: 'lock' },
    { title: 'Pre-configured', text: 'Set up by SPEX before delivery — no technical setup on your side.', icon: 'check' },
    { title: 'SIM ready', text: 'Works with a standard SIM card, including SPEX SIM options.', icon: 'sim' },
    { title: 'Portable', text: 'Compact and battery-powered for travel and daily carry.', icon: 'portable' },
    { title: 'Ready to use', text: 'Power on and connect. That is the whole setup.', icon: 'power' },
  ],
  /** Shown verbatim beneath the feature grid. */
  note:
    'Features depend on the device, firmware and the laws of your jurisdiction. SPEX configures only lawful, supported capabilities and does not offer ways to bypass carrier controls or identity requirements.',
};
