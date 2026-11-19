export const FRESHNESS_WINDOW_MS = 150_000;

/** Formats remaining seconds using only non-zero units. */
export function formatRemaining(totalSeconds) {
  const s = Math.max(0, Math.floor(Number(totalSeconds) || 0));
  if (s === 0) return "0 seconds";
  const days = Math.floor(s / 86400);
  const hours = Math.floor((s % 86400) / 3600);
  const minutes = Math.floor((s % 3600) / 60);
  const seconds = s % 60;
  const parts = [];
  if (days > 0) parts.push(`${days} day${days === 1 ? "" : "s"}`);
  if (hours > 0) parts.push(`${hours} hour${hours === 1 ? "" : "s"}`);
  if (minutes > 0) parts.push(`${minutes} minute${minutes === 1 ? "" : "s"}`);
  if (seconds > 0) parts.push(`${seconds} second${seconds === 1 ? "" : "s"}`);
  return parts.join(" ");
}

/** Extrapolates a running timer locally from the last server sample. */
export function extrapolatedRemaining(status, nowMs) {
  if (!status || !status.timer_running) return 0;
  const sampled = Date.parse(status.timer_sampled_at);
  const base = Number(status.timer_remaining_seconds) || 0;
  if (Number.isNaN(sampled)) return Math.max(0, base);
  const elapsed = Math.max(0, Math.floor((nowMs - sampled) / 1000));
  return Math.max(0, base - elapsed);
}

/** A device is "recently connected" when its last contact is within the configured window. */
export function isRecentlyConnected(status, nowMs, windowMs = FRESHNESS_WINDOW_MS) {
  if (!status || !status.last_seen_at) return false;
  const seen = Date.parse(status.last_seen_at);
  if (Number.isNaN(seen)) return false;
  return nowMs - seen <= windowMs;
}

export function relativeTime(timestamp, nowMs = Date.now()) {
  const seconds = Math.max(0, Math.floor((nowMs - timestamp) / 1000));
  if (seconds < 10) return "just now";
  if (seconds < 60) return `${seconds} seconds ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? "" : "s"} ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} hour${hours === 1 ? "" : "s"} ago`;
  const days = Math.floor(hours / 24);
  return `${days} day${days === 1 ? "" : "s"} ago`;
}

export function formatDateTime(timestamp) {
  return new Intl.DateTimeFormat(undefined, {
    weekday: "short",
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
    hour12: true,
  }).format(new Date(timestamp));
}

export function formatMinutes(minutes) {
  const value = Math.max(0, Math.floor(Number(minutes) || 0));
  const days = Math.floor(value / 1440);
  const hours = Math.floor((value % 1440) / 60);
  const mins = value % 60;
  const parts = [];
  if (days > 0) parts.push(`${days} day${days === 1 ? "" : "s"}`);
  if (hours > 0) parts.push(`${hours} hour${hours === 1 ? "" : "s"}`);
  if (mins > 0) parts.push(`${mins} minute${mins === 1 ? "" : "s"}`);
  return parts.length ? parts.join(" ") : "0 minutes";
}

/** Relative + exact last-contact label; never hides staleness. */
export function formatLastContact(status, nowMs = Date.now()) {
  if (!status || !status.last_seen_at) return "never";
  const seen = Date.parse(status.last_seen_at);
  if (Number.isNaN(seen)) return status.last_seen_at;
  return `${relativeTime(seen, nowMs)} · ${formatDateTime(seen)}`;
}
