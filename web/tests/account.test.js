import test from "node:test";
import assert from "node:assert/strict";
import {
  ensureOwnerAccount,
  fetchOwnerHandle,
  handleFromSession,
  updateOwnerHandle,
  validateHandle,
} from "../src/account.js";

function clientWithAccount(handle, rpc = null) {
  return {
    from: () => ({
      select: () => ({
        maybeSingle: async () => ({ data: handle ? { handle } : null, error: null }),
      }),
    }),
    rpc: rpc ?? (async () => {
      throw new Error("RPC must not be called when the account exists");
    }),
    auth: {
      updateUser: async () => ({ error: null }),
    },
  };
}

test("display-name validation accepts spaces and normal punctuation", () => {
  assert.equal(validateHandle("Alex"), "");
  assert.equal(validateHandle("Alex.01"), "");
  assert.equal(validateHandle("John Doe"), "");
  assert.equal(validateHandle("O'Connor"), "");
  assert.equal(validateHandle("José García"), "");
  assert.match(validateHandle(""), /Enter an account name/);
  assert.match(validateHandle("x".repeat(33)), /32 characters/);
  assert.match(validateHandle("bad\nname"), /control characters/);
});

test("handleFromSession only trusts valid metadata", () => {
  assert.equal(handleFromSession({ user: { user_metadata: { handle: "John Doe" } } }), "John Doe");
  assert.equal(handleFromSession({ user: { user_metadata: { handle: "" } } }), "");
  assert.equal(handleFromSession(null), "");
});

test("existing account row wins without an RPC", async () => {
  const client = clientWithAccount("SavedName");
  assert.equal(await fetchOwnerHandle(client), "SavedName");
  assert.equal(
    await ensureOwnerAccount(client, { user: { user_metadata: { handle: "Ignored" } } }),
    "SavedName",
  );
});

test("missing account row is bootstrapped from signup metadata", async () => {
  const calls = [];
  const client = clientWithAccount("", async (name, params) => {
    calls.push({ name, params });
    return { data: { handle: params.p_handle }, error: null };
  });
  const handle = await ensureOwnerAccount(client, {
    user: { user_metadata: { handle: "FromSignup" } },
  });

  assert.equal(handle, "FromSignup");
  assert.deepEqual(calls, [
    { name: "initialize_owner_account", params: { p_handle: "FromSignup" } },
  ]);
});

test("missing account row without metadata is rejected", async () => {
  const client = clientWithAccount("");
  await assert.rejects(
    () => ensureOwnerAccount(client, { user: { user_metadata: {} } }),
    (error) => error.code === "MISSING_PROFILE" && /no saved account name/.test(error.message),
  );
});

test("a second account is rejected by the single-owner RPC", async () => {
  const client = clientWithAccount("", async () => ({
    data: null,
    error: { message: "owner account already initialized" },
  }));
  await assert.rejects(
    () => ensureOwnerAccount(client, {
      user: { user_metadata: { handle: "SecondOwner" } },
    }),
    (error) =>
      error.code === "OWNER_EXISTS" &&
      /already has an owner/.test(error.message),
  );
});

test("account updates call update_owner_handle and refresh metadata", async () => {
  const calls = [];
  let updatedMetadata = null;
  const client = clientWithAccount("OldName", async (name, params) => {
    calls.push({ name, params });
    return { data: { handle: params.p_handle }, error: null };
  });
  client.auth.updateUser = async ({ data }) => {
    updatedMetadata = data;
    return { error: null };
  };

  const account = await updateOwnerHandle(client, "  NewName  ");
  assert.equal(account.handle, "NewName");
  assert.deepEqual(calls, [
    { name: "update_owner_handle", params: { p_handle: "NewName" } },
  ]);
  assert.deepEqual(updatedMetadata, { handle: "NewName" });
});

test("account query errors are surfaced instead of treated as missing", async () => {
  const client = {
    from: () => ({
      select: () => ({
        maybeSingle: async () => ({ data: null, error: { message: "permission denied" } }),
      }),
    }),
  };
  await assert.rejects(
    () => fetchOwnerHandle(client),
    /Could not load the account profile: permission denied/,
  );
});
