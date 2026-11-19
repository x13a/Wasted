/** Pure battery presentation helpers for the device card. */

export function batteryPercent(status) {
  if (!status || status.battery_percent === null || status.battery_percent === undefined) return null;
  const value = Number(status.battery_percent);
  return Number.isFinite(value) && value >= 0 && value <= 100 ? value : null;
}

export function batteryLabel(status, { fresh = true } = {}) {
  const percent = batteryPercent(status);
  if (percent === null) return "unknown";
  if (!fresh) return `last known ${percent}%`;
  return status.battery_charging ? `${percent}% · charging` : `${percent}%`;
}

export function batteryTone(status, { fresh = true } = {}) {
  const percent = batteryPercent(status);
  if (percent === null) return "unknown";
  if (!fresh) return "unknown";
  if (percent <= 20) return "low";
  if (percent <= 50) return "medium";
  return "healthy";
}

export function isLowBattery(status) {
  return batteryTone(status) === "low";
}
