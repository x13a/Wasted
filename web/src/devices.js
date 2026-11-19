/** Data access + pure helpers for the device grid. */

export async function fetchDashboardCore(client) {
  const [
    devicesResult,
    statusResult,
    commandsResult,
    effectiveResult,
    notesResult,
  ] = await Promise.all([
    client.from("devices")
      .select("id, account_id, name, installation_id, enrolled_at, revoked_at")
      .order("created_at", { ascending: true }),
    client.from("device_status").select("*"),
    client.rpc("list_latest_device_commands"),
    client.rpc("list_device_effective_settings"),
    client.from("device_notes").select("device_id, notes, updated_at"),
  ]);

  for (const result of [
    devicesResult,
    statusResult,
    commandsResult,
    effectiveResult,
    notesResult,
  ]) {
    if (result.error) throw new Error(result.error.message);
  }

  const devices = activeDevices(devicesResult.data ?? []);
  const statusByDevice = new Map((statusResult.data ?? []).map((s) => [s.device_id, s]));
  const latestCommand = latestCommandByDevice(commandsResult.data ?? []);
  const effectiveSettingsByDevice = new Map(
    (effectiveResult.data ?? []).map((settings) => [settings.device_id, settings]),
  );
  const notesByDevice = new Map(
    (notesResult.data ?? []).map((note) => [note.device_id, note]),
  );

  return {
    devices,
    statusByDevice,
    latestCommand,
    effectiveSettingsByDevice,
    notesByDevice,
    settings: null,
  };
}

export async function fetchAccountSettings(client) {
  const { data, error } = await client.from("account_settings").select("*").maybeSingle();
  if (error) throw new Error(error.message);
  return data ?? null;
}

export async function fetchDashboardData(client) {
  const [core, settings] = await Promise.all([
    fetchDashboardCore(client),
    fetchAccountSettings(client),
  ]);
  return { ...core, settings };
}

/** Non-revoked devices only; revoked history is preserved server-side but not actionable. */
export function activeDevices(rows) {
  return (rows ?? []).filter((row) => row && !row.revoked_at);
}

/** Rows must arrive newest-first; keeps the first (latest) command per device. */
export function latestCommandByDevice(rows) {
  const latest = new Map();
  for (const row of rows ?? []) {
    if (!latest.has(row.device_id)) latest.set(row.device_id, row);
  }
  return latest;
}

export async function renameDevice(client, deviceId, name) {
  const trimmed = String(name ?? "").trim();
  if (trimmed.length < 1 || trimmed.length > 80) {
    throw new Error("Device name must be 1–80 characters.");
  }
  const { data, error } = await client.rpc("rename_device", {
    p_device_id: deviceId,
    p_name: trimmed,
  });
  if (error) throw new Error(error.message);
  return data;
}

export async function revokeDevice(client, deviceId) {
  const { error } = await client.rpc("revoke_device", { p_device_id: deviceId });
  if (error) throw new Error(error.message);
}

export async function updateDeviceNotes(client, deviceId, notes) {
  const value = String(notes ?? "").trim();
  if (value.length > 4000) throw new Error("Notes must be 4000 characters or fewer.");
  const { data, error } = await client.rpc("update_device_notes", {
    p_device_id: deviceId,
    p_notes: value,
  });
  if (error) throw new Error(error.message);
  return data;
}
