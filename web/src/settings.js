/** Revision-protected account settings update. */
export async function saveAccountSettings(
  client,
  expectedRevision,
  lockTimerMinutes,
  usbTriggerEnabled,
  lockTriggerEnabled,
) {
  const { data, error } = await client.rpc("update_account_settings", {
    p_expected_revision: expectedRevision,
    p_lock_timer_minutes: lockTimerMinutes,
    p_usb_trigger_enabled: usbTriggerEnabled,
    p_lock_trigger_enabled: lockTriggerEnabled,
  });

  if (error) {
    if (/revision conflict/i.test(error.message || "")) {
      const conflict = new Error("Settings changed elsewhere — refreshed. Review and save again.");
      conflict.code = "REVISION_CONFLICT";
      throw conflict;
    }
    throw new Error(error.message);
  }
  return data;
}

/** Revision-protected account-wide command/token limits. */
export async function saveAccountLimits(client, expectedRevision, limits) {
  const { data, error } = await client.rpc("update_account_limits", {
    p_expected_revision: expectedRevision,
    p_enrollment_token_ttl_minutes: limits.enrollment_token_ttl_minutes,
    p_lock_command_ttl_minutes: limits.lock_command_ttl_minutes,
    p_force_reset_ttl_minutes: limits.force_reset_ttl_minutes,
    p_reset_timer_ttl_minutes: limits.reset_timer_ttl_minutes,
    p_debug_mode_on_ttl_minutes: limits.debug_mode_on_ttl_minutes,
    p_debug_mode_off_ttl_minutes: limits.debug_mode_off_ttl_minutes,
  });

  if (error) {
    if (/revision conflict/i.test(error.message || "")) {
      const conflict = new Error("Limits changed elsewhere — refreshed. Review and save again.");
      conflict.code = "REVISION_CONFLICT";
      throw conflict;
    }
    throw new Error(error.message);
  }
  return data;
}

/** Revision-protected per-device override patch. JSON null clears a field to inherit global. */
export async function saveDeviceSettings(client, deviceId, expectedRevision, patch) {
  const { data, error } = await client.rpc("update_device_settings", {
    p_device_id: deviceId,
    p_expected_revision: expectedRevision,
    p_patch: patch,
  });

  if (error) {
    if (/revision conflict/i.test(error.message || "")) {
      const conflict = new Error("Device settings changed elsewhere — refreshed. Review and save again.");
      conflict.code = "REVISION_CONFLICT";
      throw conflict;
    }
    throw new Error(error.message);
  }
  return data;
}
