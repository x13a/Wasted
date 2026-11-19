const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/;

export function validateHandle(value) {
  const handle = String(value ?? "").trim();
  if (!handle) return "Enter an account name.";
  if (handle.length > 32) return "Use 32 characters or fewer.";
  if (CONTROL_CHARACTERS.test(handle)) {
    return "Account name cannot contain control characters.";
  }
  return "";
}

export function isValidHandle(value) {
  return !validateHandle(value);
}

export function handleFromSession(session) {
  const value = session?.user?.user_metadata?.handle;
  return isValidHandle(value) ? String(value).trim() : "";
}

export async function fetchOwnerHandle(client) {
  const { data, error } = await client.from("accounts").select("handle").maybeSingle();
  if (error) {
    const failure = new Error(`Could not load the account profile: ${error.message}`);
    failure.code = "LOAD_FAILED";
    throw failure;
  }
  return data?.handle ?? "";
}

export async function initializeOwnerAccount(client, handle) {
  const validation = validateHandle(handle);
  if (validation) throw new Error(validation);
  const { data, error } = await client.rpc("initialize_owner_account", {
    p_handle: String(handle).trim(),
  });
  if (error) throw accountError(error.message);
  return data;
}

export async function updateOwnerHandle(client, handle) {
  const validation = validateHandle(handle);
  if (validation) throw new Error(validation);
  const trimmed = String(handle).trim();
  const { data, error } = await client.rpc("update_owner_handle", { p_handle: trimmed });
  if (error) throw accountError(error.message);
  await rememberSessionHandle(client, data?.handle ?? trimmed);
  return data;
}

/**
 * Returns the saved account handle. If the profile row is missing, it is created from the
 * signup metadata exactly once; there is no browser-local fallback.
 */
export async function ensureOwnerAccount(client, session) {
  const existing = await fetchOwnerHandle(client);
  if (existing) return existing;

  const handle = handleFromSession(session);
  if (!handle) {
    throw accountError("account handle missing from auth metadata");
  }

  try {
    const account = await initializeOwnerAccount(client, handle);
    return account?.handle ?? handle;
  } catch (error) {
    // A concurrent tab may have created the row first; prefer the persisted value.
    const persisted = await fetchOwnerHandle(client).catch(() => "");
    if (persisted) return persisted;
    throw error;
  }
}

export async function rememberSessionHandle(client, handle) {
  const trimmed = String(handle ?? "").trim();
  if (!isValidHandle(trimmed)) return;
  if (typeof client?.auth?.updateUser !== "function") return;
  try {
    await client.auth.updateUser({ data: { handle: trimmed } });
  } catch {
    // The accounts row is canonical; metadata is only the signup bootstrap copy.
  }
}

function accountError(message) {
  const text = String(message || "Account update failed.");
  const error = new Error(friendlyAccountError(text));
  if (/owner account already initialized/i.test(text)) error.code = "OWNER_EXISTS";
  if (/owner account missing|handle missing from auth metadata/i.test(text)) {
    error.code = "MISSING_PROFILE";
  }
  return error;
}

function friendlyAccountError(message) {
  const text = String(message || "Account update failed.");
  if (/owner account already initialized/i.test(text)) {
    return "This dashboard already has an owner. Sign in with that account instead.";
  }
  if (/handle is already taken/i.test(text)) return "That account name is already taken.";
  if (/owner account missing/i.test(text)) {
    return "This login has no owner profile in this Supabase project. Check the project URL and that setup.sql ran on the same project.";
  }
  if (/handle missing from auth metadata/i.test(text)) {
    return "This login has no saved account name. Check that this is the owner account and that the Supabase project is configured correctly.";
  }
  if (/handle must be|account name must be/i.test(text)) return text;
  return text;
}
