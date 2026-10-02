/** SPEX Data eSIM — countries and packages. Edit prices here only. */
export interface EsimPackage {
  data: string;
  days: number;
  price: number;
}

export interface EsimCountry {
  code: 'de' | 'at' | 'ch' | 'se' | 'rs' | 'es' | 'tr';
  name: string;
  packages: EsimPackage[];
}

/** Helper so every country lists the same seven tiers in the same order. */
const tiers = (p: [number, number, number, number, number, number, number]): EsimPackage[] => [
  { data: '1GB', days: 7, price: p[0] },
  { data: '3GB', days: 30, price: p[1] },
  { data: '5GB', days: 30, price: p[2] },
  { data: '10GB', days: 30, price: p[3] },
  { data: '20GB', days: 30, price: p[4] },
  { data: '50GB', days: 30, price: p[5] },
  { data: 'Unlimited', days: 30, price: p[6] },
];

export const esimCountries: EsimCountry[] = [
  { code: 'de', name: 'Germany', packages: tiers([4, 7, 9, 12, 22, 55, 79]) },
  { code: 'at', name: 'Austria', packages: tiers([4, 7, 9, 13, 24, 59, 89]) },
  { code: 'ch', name: 'Switzerland', packages: tiers([4, 7, 9, 12, 22, 55, 85]) },
  { code: 'se', name: 'Sweden', packages: tiers([4, 7, 9, 12, 22, 55, 79]) },
  { code: 'rs', name: 'Serbia', packages: tiers([4, 7, 10, 15, 29, 69, 105]) },
  { code: 'es', name: 'Spain', packages: tiers([4, 7, 9, 12, 23, 65, 89]) },
  { code: 'tr', name: 'Turkey', packages: tiers([4, 6, 8, 11, 19, 45, 69]) },
];

export const esimFacts = {
  badges: ['Data only', 'Internet only'],
  excludes: ['No phone number', 'No calls', 'No SMS'],
  compatibility: 'eSIM compatible devices only.',
};

export const allEsimPrices = esimCountries.flatMap((c) => c.packages.map((p) => p.price));
