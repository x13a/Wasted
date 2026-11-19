import test from "node:test";
import assert from "node:assert/strict";
import { tokenExpiryLabel } from "../src/enrollment.js";

test("token countdown reports minutes and seconds", () => {
  const now = Date.parse("2026-10-20T12:00:00Z");
  const expires = new Date(now + 90_000).toISOString();
  assert.equal(tokenExpiryLabel(expires, now), "Expires in 1:30");
});

test("expired tokens are labelled explicitly", () => {
  const now = Date.parse("2026-10-20T12:00:00Z");
  assert.equal(
    tokenExpiryLabel(new Date(now - 1_000).toISOString(), now),
    "Expired — create a new token",
  );
});

test("unparseable expiry degrades safely", () => {
  assert.equal(tokenExpiryLabel("not-a-date"), "Expires soon");
});
