/** Account-wide limits enforced by the backend. Keep these defaults in sync with the SQL migration. */
export const LIMIT_DEFAULTS = Object.freeze({
  enrollment_token_ttl_minutes: 10,
  lock_command_ttl_minutes: 5,
  force_reset_ttl_minutes: 10,
  reset_timer_ttl_minutes: 1440,
  debug_mode_on_ttl_minutes: 1440,
  debug_mode_off_ttl_minutes: 10,
});

export const LIMIT_FIELDS = Object.freeze([
  {
    key: "enrollment_token_ttl_minutes",
    inputId: "limit-enrollment",
    min: 1,
    max: 60,
  },
  {
    key: "lock_command_ttl_minutes",
    inputId: "limit-lock",
    min: 1,
    max: 10080,
  },
  {
    key: "force_reset_ttl_minutes",
    inputId: "limit-force-reset",
    min: 1,
    max: 10080,
  },
  {
    key: "reset_timer_ttl_minutes",
    inputId: "limit-reset-timer",
    min: 1,
    max: 10080,
  },
  {
    key: "debug_mode_on_ttl_minutes",
    inputId: "limit-debug-on",
    min: 1,
    max: 10080,
  },
  {
    key: "debug_mode_off_ttl_minutes",
    inputId: "limit-debug-off",
    min: 1,
    max: 10080,
  },
]);

/** Read the account's current limits. The settings row always has every field. */
export function limitsFromSettings(settings) {
  const limits = {};
  for (const field of LIMIT_FIELDS) {
    limits[field.key] = Number(settings[field.key]);
  }
  return limits;
}

export function commandExpirySummary(settings) {
  const limits = limitsFromSettings(settings);
  return [
    `Reset Timer ${formatLimitMinutes(limits.reset_timer_ttl_minutes)}`,
    `Force Reset ${formatLimitMinutes(limits.force_reset_ttl_minutes)}`,
    `Debug Mode ON ${formatLimitMinutes(limits.debug_mode_on_ttl_minutes)}`,
    `Debug Mode OFF ${formatLimitMinutes(limits.debug_mode_off_ttl_minutes)}`,
    `Lock Devices ${formatLimitMinutes(limits.lock_command_ttl_minutes)}`,
  ].join("; ");
}

export function formatLimitMinutes(minutes) {
  const value = Math.max(0, Math.floor(Number(minutes) || 0));
  if (value === 0) return "0 minutes";
  const days = Math.floor(value / 1440);
  const hours = Math.floor((value % 1440) / 60);
  const mins = value % 60;
  const parts = [];
  if (days > 0) parts.push(`${days} day${days === 1 ? "" : "s"}`);
  if (hours > 0) parts.push(`${hours} hour${hours === 1 ? "" : "s"}`);
  if (mins > 0) parts.push(`${mins} minute${mins === 1 ? "" : "s"}`);
  return parts.join(" ");
}

