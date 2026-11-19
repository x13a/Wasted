export const RESET_TIMER = "reset_timer";
export const FORCE_RESET = "force_reset";
export const LOCK_NOW = "lock_now";
export const DEBUG_MODE_ON = "debug_mode_on";
export const DEBUG_MODE_OFF = "debug_mode_off";

/** One idempotency UUID per deliberate submission; retries reuse it to avoid duplicates. */
export function newIdempotencyKey() {
  return crypto.randomUUID();
}

export async function createAction(client, actionType, idempotencyKey, targetScope, deviceIds) {
  const { data, error } = await client.rpc("create_account_action", {
    p_action_type: actionType,
    p_idempotency_key: idempotencyKey,
    p_target_scope: targetScope,
    p_device_ids: deviceIds,
  });
  if (error) throw new Error(error.message);
  return data;
}

/** Per-device Debug Mode control; the device applies it without any local dialog. */
export async function setDeviceDebugMode(client, deviceId, enabled, idempotencyKey) {
  const { data, error } = await client.rpc("set_device_debug_mode", {
    p_device_id: deviceId,
    p_debug_mode: enabled,
    p_idempotency_key: idempotencyKey,
  });
  if (error) throw new Error(error.message);
  return data;
}
