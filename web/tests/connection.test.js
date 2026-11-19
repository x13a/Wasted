import test from "node:test";
import assert from "node:assert/strict";
import {
  actionUnavailableMessage,
  isReachabilityError,
  retryDelayMs,
} from "../src/connection.js";

test("network and CORS failures are treated as reachability errors", () => {
  assert.equal(isReachabilityError(new TypeError("Failed to fetch")), true);
  assert.equal(
    isReachabilityError(new Error("Access blocked by CORS policy")),
    true,
  );
  assert.equal(isReachabilityError(new Error("HTTP 522 origin timeout")), true);
});

test("authorization and validation errors are not reachability errors", () => {
  assert.equal(isReachabilityError(new Error("Invalid login credentials")), false);
  assert.equal(isReachabilityError(new Error("Account name is required")), false);
});

test("retry delay backs off and caps at 30 seconds", () => {
  assert.equal(retryDelayMs(1), 5_000);
  assert.equal(retryDelayMs(2), 10_000);
  assert.equal(retryDelayMs(3), 20_000);
  assert.equal(retryDelayMs(8), 30_000);
});

test("action messages distinguish offline from unreachable", () => {
  assert.match(actionUnavailableMessage("offline"), /^Offline/);
  assert.match(actionUnavailableMessage("unreachable"), /Can't reach Supabase/);
});
