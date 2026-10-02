/**
 * Official SPEX contact channels — the ONLY place contact links live.
 * Change a username or link here and every button, card and footer icon updates.
 */
export type ChannelId = 'signal' | 'threema' | 'telegram' | 'instagram' | 'viber';

export interface ContactChannel {
  id: ChannelId;
  name: string;
  label: string;
  /** Public handle / ID shown to the visitor, if any. */
  handle?: string;
  description: string;
  url: string;
  button: string;
  tier: 'primary' | 'secondary';
}

export const channels: Record<ChannelId, ContactChannel> = {
  signal: {
    id: 'signal',
    name: 'Signal',
    label: 'Primary contact',
    description: 'End-to-end encrypted messaging. The preferred way to reach SPEX.',
    url: 'https://signal.me/#eu/7GUvuyKXalvLXiAZGKQebduU3UXnXfQFUCKJKHUrLyvBOOI55gVKz6ZHZflVCgYi',
    button: 'Open Signal',
    tier: 'primary',
  },
  threema: {
    id: 'threema',
    name: 'Threema',
    label: 'Primary contact',
    handle: 'WEFY2KSV',
    description: 'Private messaging without a phone number.',
    url: 'https://threema.id/WEFY2KSV',
    button: 'Open Threema',
    tier: 'primary',
  },
  telegram: {
    id: 'telegram',
    name: 'Telegram',
    label: 'Info & contact',
    handle: '@spexinfo',
    description: 'Product information, availability and updates.',
    url: 'https://t.me/spexinfo',
    button: 'Open Telegram',
    tier: 'primary',
  },
  instagram: {
    id: 'instagram',
    name: 'Instagram',
    label: 'Showroom',
    handle: '@ceoofspex',
    description: 'Showroom • Updates • Content',
    url: 'https://www.instagram.com/ceoofspex',
    button: 'Open Instagram',
    tier: 'secondary',
  },
  viber: {
    id: 'viber',
    name: 'Viber',
    label: 'Channel',
    description: 'Official SimPlugElite Channel',
    url: 'https://invite.viber.com/?g2=AQAUlkzlxNktBVc4x58rhm3uOynSDIoREZQboay36T5N1ztaapOad0xjkp6TZhh%2F',
    button: 'Open Viber',
    tier: 'secondary',
  },
};

export const primaryChannels = [channels.signal, channels.threema, channels.telegram];
export const secondaryChannels = [channels.instagram, channels.viber];
export const allChannels = [...primaryChannels, ...secondaryChannels];

export const antiImpersonation = {
  title: 'Official SPEX contacts',
  lead: 'Only use the official contact methods listed on SIMPLUGELITE.COM.',
  warning: 'Beware of impersonation and fake accounts.',
  detail:
    'Do not send money based solely on a username copied from another source. Verify the current official contact details on this website.',
};
