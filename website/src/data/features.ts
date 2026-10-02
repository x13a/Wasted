/** Phone security capabilities, profiles, "Why SPEX" and ordering steps. */
export interface Feature {
  title: string;
  text: string;
  icon: string;
}

export const securityFeatures: Feature[] = [
  {
    title: 'GrapheneOS',
    text: 'A privacy and security-focused Android operating system with strong application sandboxing and exploit mitigations.',
    icon: 'shield',
  },
  { title: 'Remote wipe', text: 'Configured remote security workflows where supported by the selected setup.', icon: 'wipe' },
  { title: 'USB security', text: 'USB security configuration designed to reduce unauthorized physical access.', icon: 'usb' },
  { title: 'Duress / alternative PIN', text: 'Additional device security workflows depending on configuration.', icon: 'fingerprint' },
  { title: 'X-Day security', text: 'Optional automated security workflow after a configured period.', icon: 'timer' },
  {
    title: 'Auto reboot',
    text: 'Automatic reboot configuration helps return sensitive user data to an encrypted-at-rest state.',
    icon: 'refresh',
  },
  { title: 'Theft protection', text: 'Additional protection against unauthorized device access.', icon: 'lock' },
  { title: 'MAC randomization', text: 'Reduces persistent identification across Wi-Fi networks.', icon: 'wifi' },
  {
    title: 'Multiple user profiles',
    text: 'Separate applications, identities and environments on the same device.',
    icon: 'profiles',
  },
  { title: 'Mullvad VPN', text: 'Privacy-focused VPN configuration included with selected SPEX setups.', icon: 'vpn' },
  { title: 'Threema', text: 'Privacy-focused communication application.', icon: 'message' },
];

export interface Profile {
  id: string;
  name: string;
  text: string;
  /** Colour key used by the profile visual. */
  tone: 'blue' | 'cyan' | 'indigo' | 'slate';
  apps: string[];
}

export const profiles: Profile[] = [
  {
    id: 'personal',
    name: 'Personal',
    text: 'Your everyday apps, photos and contacts.',
    tone: 'blue',
    apps: ['Photos', 'Maps', 'Music', 'Notes', 'Camera', 'Browser'],
  },
  {
    id: 'communication',
    name: 'Communication',
    text: 'Messengers kept apart from everything else.',
    tone: 'cyan',
    apps: ['Threema', 'Signal', 'Mail', 'Calls'],
  },
  {
    id: 'work',
    name: 'Work',
    text: 'Business apps and accounts in their own space.',
    tone: 'indigo',
    apps: ['Docs', 'Calendar', 'Mail', 'Files', 'Meet'],
  },
  {
    id: 'isolated',
    name: 'Isolated apps',
    text: 'Apps you need but do not want near your data.',
    tone: 'slate',
    apps: ['Shop', 'Social', 'Games'],
  },
];

export const whySpex: Feature[] = [
  { title: 'Ready out of the box', text: 'The selected SPEX configuration is prepared before delivery.', icon: 'power' },
  { title: 'Privacy-first configuration', text: 'Privacy and security settings are configured from the beginning.', icon: 'shield' },
  { title: 'Complete ecosystem', text: 'Phones, routers, SIM connectivity, eSIM and privacy accessories.', icon: 'grid' },
  { title: 'Personal support', text: 'Direct communication rather than an anonymous checkout experience.', icon: 'message' },
  {
    title: 'One point of contact',
    text: 'Discuss the configuration you actually need before ordering.',
    icon: 'contact',
  },
];

export const orderSteps = [
  { step: '01', title: 'Explore', text: 'Browse phones, routers, kits, SIMs and eSIM options.' },
  { step: '02', title: 'Contact', text: 'Contact SPEX through Signal, Threema or Telegram.' },
  { step: '03', title: 'Configuration', text: 'Discuss product availability and the required configuration.' },
  { step: '04', title: 'Delivery', text: 'Hamburg meetup or supported shipping destination.' },
];
