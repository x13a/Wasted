/** Use the Android release string reported by the device; no SDK lookup table. */
export function androidReleaseLabel(release) {
  const value = String(release ?? "").trim();
  return value ? `Android ${value}` : "Android unknown";
}

/** Device app versions are shown with a v prefix unless already prefixed. */
export function appVersionLabel(version) {
  const value = String(version ?? "").trim();
  if (!value) return "unknown";
  return /^v/i.test(value) ? value : `v${value}`;
}

export function devicePlatformLabel(status) {
  if (!status) return "unknown";
  return `${appVersionLabel(status.app_version)} · ${androidReleaseLabel(status.android_release)}`;
}
