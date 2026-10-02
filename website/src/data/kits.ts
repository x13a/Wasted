import { phoneById } from './phones';

/**
 * SPEX Kits — the flagship offering. Each kit references a phone by id so
 * names stay in sync; kit prices are set here.
 */
export interface Kit {
  phoneId: string;
  price: number;
  featured?: boolean;
}

const kitList: Kit[] = [
  { phoneId: 'spex-7a', price: 749 },
  { phoneId: 'spex-8a', price: 849 },
  { phoneId: 'spex-9a', price: 949 },
  { phoneId: 'spex-10a', price: 999, featured: true },
  { phoneId: 'spex-10-pro', price: 1299 },
];

export const kits = kitList.map((kit) => {
  const phone = phoneById(kit.phoneId);
  if (!phone) throw new Error(`kits.ts: unknown phoneId "${kit.phoneId}"`);
  return { ...kit, phone, name: `${phone.name} Kit` };
});

export const kitIncluded = [
  { title: 'SPEX Smartphone', icon: 'phone' },
  { title: 'Secure Router', icon: 'router' },
  { title: 'SIM Card', icon: 'sim' },
  { title: 'GrapheneOS', icon: 'shield' },
  { title: 'Mullvad VPN', icon: 'vpn' },
  { title: 'Threema', icon: 'message' },
  { title: 'Complete Setup', icon: 'check' },
];

/** The visual "equation" on the kits page. */
export const kitFormula = ['Phone', 'Router', 'SIM', 'VPN', 'Threema', 'Configuration'];
