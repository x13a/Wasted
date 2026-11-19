import test from "node:test";
import assert from "node:assert/strict";
import {
  saveAccountLimits,
  saveAccountSettings,
  saveDeviceSettings,
} from "../src/settings.js";

test("saveAccountSettings forwards the revision and values", async () => {
  let captured = null;
  const client = {
    rpc: async (name, params) => {
      captured = { name, params };
      return { data: { revision: 3, lock_timer_minutes: 60, usb_trigger_enabled: true, lock_trigger_enabled: true }, error: null };
    },
  };

  const result = await saveAccountSettings(client, 2, 60, true, true);

  assert.equal(captured.name, "update_account_settings");
  assert.deepEqual(captured.params, {
    p_expected_revision: 2,
    p_lock_timer_minutes: 60,
    p_usb_trigger_enabled: true,
    p_lock_trigger_enabled: true,
  });
  assert.equal(result.revision, 3);
});

test("stale revisions map to a REVISION_CONFLICT error", async () => {
  const client = {
    rpc: async () => ({ data: null, error: { message: "settings revision conflict" } }),
  };

  await assert.rejects(
    () => saveAccountSettings(client, 1, 60, true, false),
    (error) => error.code === "REVISION_CONFLICT" && /changed elsewhere/i.test(error.message),
  );
});

test("other server errors surface their message", async () => {
  const client = {
    rpc: async () => ({ data: null, error: { message: "lock timer minutes must be between 1 and 525600" } }),
  };

  await assert.rejects(
    () => saveAccountSettings(client, 1, 0, true, true),
    /between 1 and 525600/,
  );
});

test("saveAccountLimits sends the current revision and every limit", async () => {
  let captured = null;
  const client = {
    rpc: async (name, params) => {
      captured = { name, params };
      return { data: { limits_revision: 5 }, error: null };
    },
  };
  const limits = {
    enrollment_token_ttl_minutes: 12,
    lock_command_ttl_minutes: 4,
    force_reset_ttl_minutes: 8,
    reset_timer_ttl_minutes: 2880,
    debug_mode_on_ttl_minutes: 1440,
    debug_mode_off_ttl_minutes: 6,
  };

  const result = await saveAccountLimits(client, 4, limits);

  assert.equal(captured.name, "update_account_limits");
  assert.deepEqual(captured.params, {
    p_expected_revision: 4,
    p_enrollment_token_ttl_minutes: 12,
    p_lock_command_ttl_minutes: 4,
    p_force_reset_ttl_minutes: 8,
    p_reset_timer_ttl_minutes: 2880,
    p_debug_mode_on_ttl_minutes: 1440,
    p_debug_mode_off_ttl_minutes: 6,
  });
  assert.equal(result.limits_revision, 5);
});

test("limit revision conflicts map to REVISION_CONFLICT", async () => {
  const client = {
    rpc: async () => ({ data: null, error: { message: "limits revision conflict" } }),
  };

  await assert.rejects(
    () => saveAccountLimits(client, 1, {}),
    (error) => error.code === "REVISION_CONFLICT",
  );
});

test("saveDeviceSettings sends the override patch and expected revision", async () => {
  let captured = null;
  const client = {
    rpc: async (name, params) => {
      captured = { name, params };
      return { data: { device_id: "device-1", device_revision: 3 }, error: null };
    },
  };

  const result = await saveDeviceSettings(client, "device-1", 2, {
    lock_trigger_enabled: false,
    lock_timer_minutes: null,
    usb_trigger_enabled: true,
  });

  assert.equal(captured.name, "update_device_settings");
  assert.deepEqual(captured.params, {
    p_device_id: "device-1",
    p_expected_revision: 2,
    p_patch: {
      lock_trigger_enabled: false,
      lock_timer_minutes: null,
      usb_trigger_enabled: true,
    },
  });
  assert.equal(result.device_revision, 3);
});

test("device settings conflicts map to REVISION_CONFLICT", async () => {
  const client = {
    rpc: async () => ({ data: null, error: { message: "device settings revision conflict" } }),
  };

  await assert.rejects(
    () => saveDeviceSettings(client, "device-1", 1, { lock_timer_minutes: 60 }),
    (error) => error.code === "REVISION_CONFLICT",
  );
});
