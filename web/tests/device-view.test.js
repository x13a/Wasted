import assert from "node:assert/strict";
import test from "node:test";
import {
  DEFAULT_DEVICE_VIEW,
  filterCount,
  filterDevices,
  normalizeDeviceView,
  sortDevices,
} from "../src/device-view.js";

const now = Date.parse("2026-11-11T10:00:00Z");
const windowMs = 150_000;
const devices = [
  { id: "a", name: "Alpha", enrolled_at: "2026-11-01T10:00:00Z" },
  { id: "b", name: "Beta", enrolled_at: "2026-11-03T10:00:00Z" },
  { id: "c", name: "Gamma", enrolled_at: "2026-11-02T10:00:00Z" },
];
const statusByDevice = new Map([
  ["a", { last_seen_at: new Date(now - 10_000).toISOString(), battery_percent: 80, debug_mode: true }],
  ["b", { last_seen_at: new Date(now - 300_000).toISOString(), battery_percent: 15, debug_mode: false }],
  ["c", { last_seen_at: new Date(now - 400_000).toISOString(), battery_percent: 50, debug_mode: false }],
]);
const notesByDevice = new Map([["c", { notes: "Second floor" }]]);

test("invalid saved device views fall back to defaults", () => {
  assert.deepEqual(normalizeDeviceView({ filter: "bad", sort: "bad", layout: "bad" }), DEFAULT_DEVICE_VIEW);
  assert.deepEqual(
    normalizeDeviceView({ filter: "debug", sort: "name", layout: "list" }),
    { filter: "debug", sort: "name", layout: "list" },
  );
});

test("filters cover active, inactive, debug, battery, notes, and search", () => {
  assert.deepEqual(
    filterDevices(devices, statusByDevice, notesByDevice, now, windowMs, "active").map((d) => d.id),
    ["a"],
  );
  assert.deepEqual(
    filterDevices(devices, statusByDevice, notesByDevice, now, windowMs, "inactive").map((d) => d.id),
    ["b", "c"],
  );
  assert.deepEqual(
    filterDevices(devices, statusByDevice, notesByDevice, now, windowMs, "debug").map((d) => d.id),
    ["a"],
  );
  assert.deepEqual(
    filterDevices(devices, statusByDevice, notesByDevice, now, windowMs, "low-battery").map((d) => d.id),
    ["b"],
  );
  assert.deepEqual(
    filterDevices(devices, statusByDevice, notesByDevice, now, windowMs, "notes").map((d) => d.id),
    ["c"],
  );
  assert.deepEqual(
    filterDevices(devices, statusByDevice, notesByDevice, now, windowMs, "all", "bet").map((d) => d.id),
    ["b"],
  );
  assert.equal(filterCount(devices, statusByDevice, notesByDevice, now, windowMs, "all"), 3);
});

test("sorting by name, recent contact, and battery is deterministic", () => {
  assert.deepEqual(
    sortDevices(devices, statusByDevice, "name").map((d) => d.id),
    ["a", "b", "c"],
  );
  assert.deepEqual(
    sortDevices(devices, statusByDevice, "recent").map((d) => d.id),
    ["a", "b", "c"],
  );
  assert.deepEqual(
    sortDevices(devices, statusByDevice, "battery").map((d) => d.id),
    ["b", "c", "a"],
  );
  assert.deepEqual(
    sortDevices(devices, statusByDevice, "enrolled").map((d) => d.id),
    ["b", "c", "a"],
  );
});

test("devices without status sort last for recent and battery", () => {
  const withUnknown = [...devices, { id: "d", name: "Delta" }];
  assert.equal(sortDevices(withUnknown, statusByDevice, "recent").at(-1).id, "d");
  assert.equal(sortDevices(withUnknown, statusByDevice, "battery").at(-1).id, "d");
});
