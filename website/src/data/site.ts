/** Brand copy and navigation shared across the site. */
export const brand = {
  name: 'SIMPLUGELITE',
  product: 'SPEX',
  domain: 'SIMPLUGELITE.COM',
  tagline: ['Secure.', 'Private.', 'Ready to use.'],
  taglineInline: 'Secure. Private. Ready to use.',
  support: 'Specialized privacy-tech solutions, fully configured & ready to use.',
  subtitle: 'Privacy technology configured by SPEX.',
  heroCopy:
    'Secure smartphones, mobile routers, connectivity and privacy-focused technology — configured and ready to use.',
  configured: 'Configured by SPEX.',
  outOfBox: 'Ready to use out of the box.',
  /** Official logo files in /public/images/brand. Replace the files to update the logo everywhere. */
  logo: {
    /** Header mark: exact 1x / 2x / 3x sizes so it stays sharp on every screen. */
    small: '/images/brand/spex-logo-h56.png',
    smallSrcset: '/images/brand/spex-logo-h56.png 1x, /images/brand/spex-logo-h112.png 2x, /images/brand/spex-logo-h168.png 3x',
    large: '/images/brand/spex-logo.webp',
    alt: 'SIMPLUGELITE / SPEX logo',
  },
  description:
    'SPEX by SIMPLUGELITE — secure smartphones, mobile routers, SIM connectivity, data eSIM and privacy accessories. Configured and ready to use. Hamburg meetups and worldwide shipping.',
};

export interface NavItem {
  label: string;
  href: string;
}

export const mainNav: NavItem[] = [
  { label: 'Home', href: '/' },
  { label: 'Phones', href: '/phones' },
  { label: 'Router', href: '/router' },
  { label: 'Kits', href: '/kits' },
  { label: 'SIM', href: '/sim' },
  { label: 'eSIM', href: '/esim' },
  { label: 'Privacy', href: '/accessories' },
  { label: 'Contact', href: '/contact' },
];

export const legalNav: NavItem[] = [
  { label: 'Imprint', href: '/imprint' },
  { label: 'Privacy', href: '/privacy-policy' },
  { label: 'Terms', href: '/terms' },
  { label: 'Shipping', href: '/shipping' },
];

export const trustPoints = ['Hamburg Meetups', 'Worldwide Shipping', 'Configured by SPEX'];
