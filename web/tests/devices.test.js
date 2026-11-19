import test from "node:test";
import assert from "node:assert/strict";
import {
  activeDevices,
  fetchDashboardData,
  latestCommandByDevice,
  renameDevice,
  revokeDevice,
  updateDeviceNotes,
} from "../src/devices.js";

test("revoked devices are excluded from the actionable list", () => {
  const devices = activeDevices([
    { id: "a", revoked_at: null },
    { id: "b", revoked_at: "2026-10-01T00:00:00Z" },
    null,
  ]);
  assert.deepEqual(devices.map((d) => d.id), ["a"]);
});

test("latest command per device is taken from newest-first rows", () => {
  const latest = latestCommandByDevice([
    { device_id: "a", id: "cmd-2" },
    { device_id: "a", id: "cmd-1" },
    { device_id: "b", id: "cmd-3" },
  ]);

  assert.equal(latest.get("a").id, "cmd-2");
  assert.equal(latest.get("b").id, "cmd-3");
});

test("empty inputs are handled", () => {
  assert.deepEqual(activeDevices(undefined), []);
  assert.equal(latestCommandByDevice([]).size, 0);
});

test("dashboard data uses the bounded latest-command and effective-settings RPCs", async () => {
  let commandTableRead = false;
  const rpcCalls = [];
  const client = {
    rpc: async (name) => {
      rpcCalls.push(name);
      if (name === "list_device_effective_settings") {
        return {
          data: [{
            device_id: "device-1",
            global_revision: 4,
            device_revision: 2,
            lock_trigger_enabled: true,
            lock_timer_minutes: 60,
            usb_trigger_enabled: false,
          }],
          error: null,
        };
      }
      assert.equal(name, "list_latest_device_commands");
      return {
        data: [
          {
            id: "cmd-2",
            device_id: "device-1",
            command_type: "reset_timer",
            state: "applied",
            result_code: null,
            created_at: "2026-11-05T15:00:00Z",
          },
        ],
        error: null,
      };
    },
    from: (table) => {
      if (table === "devices") {
        return {
          select: () => ({
            order: async () => ({
              data: [{ id: "device-1", revoked_at: null }],
              error: null,
            }),
          }),
        };
      }
      if (table === "device_status") {
        return { select: async () => ({ data: [], error: null }) };
      }
      if (table === "account_settings") {
        return { select: () => ({ maybeSingle: async () => ({ data: null, error: null }) }) };
      }
      if (table === "device_notes") {
        return {
          select: async () => ({
            data: [{ device_id: "device-1", notes: "Desk drawer", updated_at: "2026-11-11T08:00:00Z" }],
            error: null,
          }),
        };
      }
      if (table === "device_commands") commandTableRead = true;
      throw new Error(`unexpected table ${table}`);
    },
  };

  const data = await fetchDashboardData(client);

  assert.equal(commandTableRead, false);
  assert.deepEqual(rpcCalls, ["list_latest_device_commands", "list_device_effective_settings"]);
  assert.equal(data.latestCommand.get("device-1").id, "cmd-2");
  assert.equal(data.devices.length, 1);
  assert.equal(data.effectiveSettingsByDevice.get("device-1").device_revision, 2);
  assert.equal(data.notesByDevice.get("device-1").notes, "Desk drawer");
});

test("rename sends a trimmed name through the RPC", async () => {
  const calls = [];
  const client = {
    rpc: async (name, args) => {
      calls.push({ name, args });
      return { data: { id: args.p_device_id }, error: null };
    },
  };
  await renameDevice(client, "device-1", "  Office phone  ");
  assert.deepEqual(calls, [
    { name: "rename_device", args: { p_device_id: "device-1", p_name: "Office phone" } },
  ]);
});

test("device notes are trimmed and sent through the RPC", async () => {
  const calls = [];
  const client = {
    rpc: async (name, args) => {
      calls.push({ name, args });
      return { data: { device_id: "device-1", notes: "Second floor" }, error: null };
    },
  };

  const result = await updateDeviceNotes(client, "device-1", "  Second floor  ");

  assert.deepEqual(calls, [{
    name: "update_device_notes",
    args: { p_device_id: "device-1", p_notes: "Second floor" },
  }]);
  assert.equal(result.notes, "Second floor");
});

test("device notes reject oversized text before calling Supabase", async () => {
  let called = false;
  const client = { rpc: async () => { called = true; } };
  await assert.rejects(
    () => updateDeviceNotes(client, "device-1", "x".repeat(4001)),
    /4000/,
  );
  assert.equal(called, false);
});

test("rename rejects empty and oversized names before calling Supabase", async () => {
  let called = false;
  const client = { rpc: async () => { called = true; } };
  await assert.rejects(() => renameDevice(client, "device-1", "   "), /1–80/);
  await assert.rejects(() => renameDevice(client, "device-1", "x".repeat(81)), /1–80/);
  assert.equal(called, false);
});

test("revoke calls the device RPC", async () => {
  const calls = [];
  const client = {
    rpc: async (name, args) => {
      calls.push({ name, args });
      return { error: null };
    },
  };
  await revokeDevice(client, "device-1");
  assert.deepEqual(calls, [{ name: "revoke_device", args: { p_device_id: "device-1" } }]);
});
