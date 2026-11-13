import test from "node:test";
import assert from "node:assert/strict";
import {
  createAccount,
  friendlyAuthError,
  normalizeEmail,
  signIn,
  signOut,
  validateCreateAccount,
  validateSignIn,
  validatePasswordChange,
  changePassword,
} from "../src/auth.js";

test("sign out ends only the current browser session", async () => {
  const calls = [];
  const client = {
    auth: {
      signOut: async (options) => {
        calls.push(options);
      },
    },
  };
  await signOut(client);
  assert.deepEqual(calls, [{ scope: "local" }]);
});

test("normalizeEmail trims, lowercases, and removes whitespace", () => {
  assert.equal(normalizeEmail("  Test @Example.COM  "), "test@example.com");
});

test("normalizeEmail keeps one at-sign and removes other invalid characters", () => {
  assert.equal(normalizeEmail("a@@b.com<script>"), "a@b.comscript");
});

test("normalizeEmail preserves common valid local-part characters", () => {
  assert.equal(normalizeEmail("First.Last+tag@example.co.uk"), "first.last+tag@example.co.uk");
});

test("sign-in validation names the exact field", () => {
  assert.deepEqual(validateSignIn("admin", "secret"), {
    field: "sign-in-email",
    message: "Enter a valid email address.",
  });
  assert.deepEqual(validateSignIn("owner@example.com", ""), {
    field: "sign-in-password",
    message: "Enter your password.",
  });
  assert.equal(validateSignIn("owner@example.com", "secret"), null);
});

test("create-account validation checks password confirmation and handle", () => {
  assert.deepEqual(
    validateCreateAccount("owner@example.com", "short", "short", "Alex"),
    { field: "create-password", message: "Password must be at least 6 characters." },
  );
  assert.deepEqual(
    validateCreateAccount("owner@example.com", "long-enough-1", "different-1", "Alex"),
    { field: "create-password-confirm", message: "Passwords do not match." },
  );
  assert.deepEqual(
    validateCreateAccount("owner@example.com", "long-enough-1", "long-enough-1", ""),
    { field: "create-handle", message: "Enter an account name." },
  );
  assert.equal(
    validateCreateAccount("owner@example.com", "long-enough-1", "long-enough-1", "John Doe"),
    null,
  );
  // The floor matches Supabase's server default so the two cannot disagree.
  assert.deepEqual(validateCreateAccount("owner@example.com", "abcde", "abcde", "John"), {
    field: "create-password",
    message: "Password must be at least 6 characters.",
  });
  assert.equal(validateCreateAccount("owner@example.com", "abcdef", "abcdef", "John"), null);
});

test("signup stores the handle in Supabase auth metadata", async () => {
  globalThis.window = { location: { origin: "https://dashboard.test", pathname: "/" } };
  let payload = null;
  const client = {
    auth: {
      signUp: async (value) => {
        payload = value;
        return { data: { session: { access_token: "token" } }, error: null };
      },
    },
    rpc: async () => ({ data: { handle: "Alex" }, error: null }),
  };

  const session = await createAccount(client, "Owner@Example.com", "long-enough-1", " Alex ");
  assert.ok(session);
  assert.equal(payload.email, "owner@example.com");
  assert.deepEqual(payload.options.data, { handle: "Alex" });
});

test("immediate signup session initializes the owner row", async () => {
  globalThis.window = { location: { origin: "https://dashboard.test", pathname: "/" } };
  const calls = [];
  const client = {
    auth: {
      signUp: async () => ({ data: { session: { access_token: "token" } }, error: null }),
      updateUser: async () => ({ error: null }),
    },
    rpc: async (name, params) => {
      calls.push({ name, params });
      return { data: { handle: "Alex" }, error: null };
    },
  };

  const session = await createAccount(client, "owner@example.com", "long-enough-1", "Alex");
  assert.ok(session);
  assert.deepEqual(calls, [
    { name: "initialize_owner_account", params: { p_handle: "Alex" } },
  ]);
});

test("a signup without a session fails closed", async () => {
  globalThis.window = { location: { origin: "https://dashboard.test", pathname: "/" } };
  const client = {
    auth: {
      signUp: async () => ({ data: { session: null }, error: null }),
    },
    rpc: async () => {
      throw new Error("RPC must not run without a session");
    },
  };

  await assert.rejects(
    () => createAccount(client, "owner@example.com", "long-enough-1", "Alex"),
    /Confirm email is disabled/,
  );
});

test("auth errors are translated without changing unknown messages", () => {
  assert.equal(friendlyAuthError("Invalid login credentials"), "Wrong email or password.");
  assert.equal(
    friendlyAuthError("Failed to fetch"),
    "Could not reach Supabase. Check your connection and try again.",
  );
  assert.equal(
    friendlyAuthError("This dashboard already has an owner."),
    "This dashboard already has an owner. Sign in instead.",
  );
  assert.equal(friendlyAuthError("Custom backend error"), "Custom backend error");
});

test("password change requires current, new and matching confirmation", () => {
  assert.equal(
    validatePasswordChange("", "a-long-new-password1", "a-long-new-password1").message,
    "Enter your current password.",
  );
  assert.equal(
    validatePasswordChange("old-password-1", "", "").message,
    "Enter a new password.",
  );
  assert.equal(
    validatePasswordChange("old-password-1", "short", "short").message,
    "Password must be at least 6 characters.",
  );
  assert.equal(
    validatePasswordChange("same-password-1", "same-password-1", "same-password-1").message,
    "Choose a password different from the current one.",
  );
  assert.equal(
    validatePasswordChange("old-password-1", "a-long-new-password1", "").message,
    "Confirm your new password.",
  );
  assert.equal(
    validatePasswordChange("old-password-1", "a-long-new-password1", "a-long-new-password2").message,
    "Passwords do not match.",
  );
  assert.equal(
    validatePasswordChange("old-password-1", "a-long-new-password1", "a-long-new-password1"),
    null,
  );
});

test("password change re-authenticates before updating", async () => {
  const calls = [];
  const client = {
    auth: {
      signInWithPassword: async (creds) => {
        calls.push(["signInWithPassword", creds]);
        return { error: null };
      },
      updateUser: async (attrs) => {
        calls.push(["updateUser", attrs]);
        return { error: null };
      },
    },
  };

  await changePassword(client, "old-password-1", "a-long-new-password1", "owner@example.com");

  assert.deepEqual(calls, [
    ["signInWithPassword", { email: "owner@example.com", password: "old-password-1" }],
    ["updateUser", { password: "a-long-new-password1" }],
  ]);
});

test("password change aborts when the current password is wrong", async () => {
  let updated = false;
  const client = {
    auth: {
      signInWithPassword: async () => ({ error: { message: "Invalid login credentials" } }),
      updateUser: async () => {
        updated = true;
        return { error: null };
      },
    },
  };

  await assert.rejects(
    () => changePassword(client, "wrong-password", "a-long-new-password1", "owner@example.com"),
    /Wrong email or password/,
  );
  assert.equal(updated, false, "must not change the password after a failed re-authentication");
});
