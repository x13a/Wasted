import test from "node:test";
import assert from "node:assert/strict";
import { batteryLabel, batteryPercent, batteryTone, isLowBattery } from "../src/battery.js";

test("battery zero is real, not unknown", () => {
  assert.equal(batteryPercent({ battery_percent: 0 }), 0);
  assert.equal(batteryLabel({ battery_percent: 0 }), "0%");
  assert.equal(batteryTone({ battery_percent: 0 }), "low");
  assert.equal(isLowBattery({ battery_percent: 0 }), true);
});

test("battery charging and percentage render together", () => {
  assert.equal(batteryLabel({ battery_percent: 82, battery_charging: true }), "82% · charging");
  assert.equal(batteryTone({ battery_percent: 82 }), "healthy");
});

test("unknown battery is not treated as zero", () => {
  assert.equal(batteryPercent(null), null);
  assert.equal(batteryPercent({ battery_percent: null }), null);
  assert.equal(batteryLabel({}), "unknown");
  assert.equal(batteryTone({ battery_percent: undefined }), "unknown");
  assert.equal(isLowBattery({ battery_percent: null }), false);
});

test("thresholds use the documented boundaries", () => {
  assert.equal(batteryTone({ battery_percent: 20 }), "low");
  assert.equal(batteryTone({ battery_percent: 21 }), "medium");
  assert.equal(batteryTone({ battery_percent: 50 }), "medium");
  assert.equal(batteryTone({ battery_percent: 51 }), "healthy");
});

test("offline battery is labelled as last-known and not charging", () => {
  const offline = { battery_percent: 10, battery_charging: true };
  assert.equal(batteryLabel(offline, { fresh: false }), "last known 10%");
  assert.equal(batteryTone(offline, { fresh: false }), "unknown");
  assert.equal(isLowBattery(offline), true);
});

test("fresh battery keeps the current charging label", () => {
  const device = { battery_percent: 10, battery_charging: true };
  assert.equal(batteryLabel(device), "10% · charging");
  assert.equal(batteryTone(device), "low");
});
