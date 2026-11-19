import assert from "node:assert/strict";
import test from "node:test";
import {
  LIMIT_DEFAULTS,
  commandExpirySummary,
  formatLimitMinutes,
  limitsFromSettings,
} from "../src/limits.js";

const settings = {
  ...LIMIT_DEFAULTS,
  limits_revision: 3,
};

test("limits are read directly from the current settings row", () => {
  assert.deepEqual(limitsFromSettings(settings), LIMIT_DEFAULTS);
});

test("minute formatting omits zero units", () => {
  assert.equal(formatLimitMinutes(5), "5 minutes");
  assert.equal(formatLimitMinutes(60), "1 hour");
  assert.equal(formatLimitMinutes(90), "1 hour 30 minutes");
  assert.equal(formatLimitMinutes(1440), "1 day");
  assert.equal(formatLimitMinutes(10080), "7 days");
});

test("command expiry summary reflects saved values", () => {
  assert.match(commandExpirySummary(settings), /Reset Timer 1 day/);
  assert.match(commandExpirySummary(settings), /Force Reset 10 minutes/);
  assert.match(commandExpirySummary(settings), /Lock Devices 5 minutes/);
});
