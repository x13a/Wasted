export const DEVICE_FILTERS = [
  "all",
  "active",
  "inactive",
  "debug",
  "low-battery",
  "notes",
];

export const DEVICE_SORTS = ["default", "name", "recent", "enrolled", "battery", "connection"];
export const DEVICE_LAYOUTS = ["grid", "list"];

export const DEFAULT_DEVICE_VIEW = Object.freeze({
  filter: "all",
  sort: "default",
  layout: "grid",
});

export function normalizeDeviceView(value) {
  const source = value && typeof value === "object" ? value : {};
  return {
    filter: DEVICE_FILTERS.includes(source.filter) ? source.filter : DEFAULT_DEVICE_VIEW.filter,
    sort: DEVICE_SORTS.includes(source.sort) ? source.sort : DEFAULT_DEVICE_VIEW.sort,
    layout: DEVICE_LAYOUTS.includes(source.layout) ? source.layout : DEFAULT_DEVICE_VIEW.layout,
  };
}

function active(status, now, windowMs) {
  if (!status?.last_seen_at) return false;
  const seen = Date.parse(status.last_seen_at);
  return !Number.isNaN(seen) && now - seen <= windowMs;
}

function lastSeen(status) {
  const seen = Date.parse(status?.last_seen_at);
  return Number.isNaN(seen) ? null : seen;
}

function battery(status) {
  const value = Number(status?.battery_percent);
  return Number.isFinite(value) && value >= 0 && value <= 100 ? value : null;
}

export function filterDevices(
  devices,
  statusByDevice,
  notesByDevice,
  now,
  windowMs,
  filter = "all",
  query = "",
) {
  const normalizedQuery = String(query ?? "").trim().toLowerCase();
  const matchesQuery = (device) => {
    if (!normalizedQuery) return true;
    const name = String(device.name ?? "").toLowerCase();
    const id = String(device.id ?? "").toLowerCase();
    return name.includes(normalizedQuery) || id.includes(normalizedQuery);
  };

  return (devices ?? []).filter((device) => {
    if (!matchesQuery(device)) return false;
    const status = statusByDevice.get(device.id);
    const on = active(status, now, windowMs);
    if (filter === "active") return on;
    if (filter === "inactive") return !on;
    if (filter === "debug") return Boolean(status?.debug_mode);
    if (filter === "low-battery") {
      const value = battery(status);
      return value !== null && value <= 20;
    }
    if (filter === "notes") {
      return Boolean(String(notesByDevice.get(device.id)?.notes ?? "").trim());
    }
    return true;
  });
}

export function sortDevices(devices, statusByDevice, sort = "default", windowMs = 150_000) {
  const copy = [...(devices ?? [])];
  if (sort === "name") {
    return copy.sort((a, b) =>
      String(a.name || "").localeCompare(String(b.name || ""), undefined, {
        sensitivity: "base",
        numeric: true,
      }),
    );
  }
  if (sort === "recent") {
    return copy.sort((a, b) => {
      const aa = lastSeen(statusByDevice.get(a.id));
      const bb = lastSeen(statusByDevice.get(b.id));
      if (aa === null && bb === null) return 0;
      if (aa === null) return 1;
      if (bb === null) return -1;
      return bb - aa;
    });
  }
  if (sort === "enrolled") {
    return copy.sort((a, b) => {
      const aa = Date.parse(a.enrolled_at);
      const bb = Date.parse(b.enrolled_at);
      if (Number.isNaN(aa) && Number.isNaN(bb)) return 0;
      if (Number.isNaN(aa)) return 1;
      if (Number.isNaN(bb)) return -1;
      return bb - aa;
    });
  }
  if (sort === "battery") {
    return copy.sort((a, b) => {
      const aa = battery(statusByDevice.get(a.id));
      const bb = battery(statusByDevice.get(b.id));
      if (aa === null && bb === null) return 0;
      if (aa === null) return 1;
      if (bb === null) return -1;
      return aa - bb;
    });
  }
  if (sort === "connection") {
    return copy.sort((a, b) => {
      const aa = active(statusByDevice.get(a.id), Date.now(), windowMs) ? 0 : 1;
      const bb = active(statusByDevice.get(b.id), Date.now(), windowMs) ? 0 : 1;
      return aa - bb;
    });
  }
  return copy;
}

export function filterCount(devices, statusByDevice, notesByDevice, now, windowMs, filter) {
  return filterDevices(
    devices,
    statusByDevice,
    notesByDevice,
    now,
    windowMs,
    filter,
    "",
  ).length;
}
