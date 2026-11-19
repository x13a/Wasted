import test from "node:test";
import assert from "node:assert/strict";
import {
  FRESHNESS_WINDOW_MS,
  formatRemaining,
  extrapolatedRemaining,
  isRecentlyConnected,
  formatLastContact,
  relativeTime,
  formatDateTime,
  formatMinutes,
} from "../src/timers.js";

test("formatRemaining renders only non-zero live units", () => {
  assert.equal(formatRemaining(0), "0 seconds");
  assert.equal(formatRemaining(3661), "1 hour 1 minute 1 second");
  assert.equal(formatRemaining(90000), "1 day 1 hour");
  assert.equal(formatRemaining(-5), "0 seconds");
});

test("extrapolatedRemaining subtracts elapsed time and clamps at zero", () => {
  const now = Date.parse("2026-10-20T12:00:00Z");
  const sampledAt = new Date(now - 10_000).toISOString();

  assert.equal(
    extrapolatedRemaining(
      { timer_running: true, timer_sampled_at: sampledAt, timer_remaining_seconds: 100 },
      now,
    ),
    90,
  );
  assert.equal(
    extrapolatedRemaining(
      { timer_running: true, timer_sampled_at: sampledAt, timer_remaining_seconds: 5 },
      now,
    ),
    0,
  );
  assert.equal(
    extrapolatedRemaining({ timer_running: false, timer_remaining_seconds: 100 }, now),
    0,
  );
});

test("freshness window is exactly 150 seconds", () => {
  assert.equal(FRESHNESS_WINDOW_MS, 150_000);
  const now = Date.parse("2026-10-20T12:00:00Z");

  const fresh = { last_seen_at: new Date(now - 149_000).toISOString() };
  const stale = { last_seen_at: new Date(now - 151_000).toISOString() };

  assert.equal(isRecentlyConnected(fresh, now), true);
  assert.equal(isRecentlyConnected(stale, now), false);
  assert.equal(isRecentlyConnected(null, now), false);
  assert.equal(
    isRecentlyConnected({ last_seen_at: new Date(now).toISOString() }, now),
    true,
  );
  assert.equal(isRecentlyConnected(fresh, now, 90_000), false);
  assert.equal(isRecentlyConnected(stale, now, 180_000), true);
});

test("last contact is never hidden", () => {
  assert.equal(formatLastContact(null), "never");
  assert.equal(
    formatLastContact({ last_seen_at: "not-a-date" }),
    "not-a-date",
  );
});

test("last contact includes relative and readable time", () => {
  const now = Date.parse("2026-11-05T18:30:00Z");
  const seen = new Date(now - 2 * 60_000).toISOString();
  const label = formatLastContact({ last_seen_at: seen }, now);
  assert.match(label, /^2 minutes ago/);
  assert.match(label, /2026/);
});

test("relative time uses readable units", () => {
  const now = Date.parse("2026-11-05T18:30:00Z");
  assert.equal(relativeTime(now - 5_000, now), "just now");
  assert.equal(relativeTime(now - 65_000, now), "1 minute ago");
  assert.equal(relativeTime(now - 3_600_000, now), "1 hour ago");
  assert.equal(relativeTime(now - 86_400_000, now), "1 day ago");
  assert.match(formatDateTime(now), /2026/);
});

test("lock durations omit zero units", () => {
  assert.equal(formatMinutes(10080), "7 days");
  assert.equal(formatMinutes(1440), "1 day");
  assert.equal(formatMinutes(60), "1 hour");
  assert.equal(formatMinutes(90), "1 hour 30 minutes");
  assert.equal(formatMinutes(1), "1 minute");
});

test("lock durations parse arbitrary device-reported whole minutes", () => {
  assert.equal(formatMinutes(12345), "8 days 13 hours 45 minutes");
  assert.equal(formatMinutes(10081), "7 days 1 minute");
  assert.equal(formatMinutes(59), "59 minutes");
  assert.equal(formatMinutes(0), "0 minutes");
});
