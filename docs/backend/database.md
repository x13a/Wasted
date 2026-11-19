# Database schema and RPCs

Self-sufficient reference for the Supabase backend. Generated install/update SQL lives in
`supabase/releases/<version>/`; migration policy is in
[migrations-and-releases.md](./migrations-and-releases.md).

## Tables (public unless noted)

| Table | Purpose | Key points |
| --- | --- | --- |
| `accounts` | Owner profile | `id` = auth user id; unique display name (case-insensitive), 1–32 chars; spaces and normal punctuation allowed |
| `devices` | Enrolled devices | `account_id` FK; unique `installation_id`; `auth_user_id` (anonymous); `revoked_at`; unique `(id, account_id)` |
| `account_settings` | Global default state and account limits | `revision` (monotonic trigger revision), `limits_revision` (monotonic limits revision), `lock_trigger_enabled`, `lock_timer_minutes` (1–525600), `usb_trigger_enabled`, `initialized_at`; command TTLs and enrollment lifetime |
| `device_settings` | Per-device overrides | One row per device when overridden; nullable fields inherit the global value; independent `revision` |
| `device_notes` | Private owner notes | One row per device; owner-only SELECT policy (devices cannot read it); 0–4000 characters; updated through `update_device_notes`; cascades when the device is deleted |
| `device_status` | Last reported device state | One row per device: applied global/device revisions, actual Lock trigger/delay/USB, timer, Debug Mode, reset support, remote enabled, battery/charging, `settings_applied_at`, app version, Android SDK and the device-reported `Build.VERSION.RELEASE`, last error, timer sample, last seen |
| `account_action_targets` | Explicit command targets | One row per action/device; `target_scope` on the action is `all` or `selected`; no implicit fan-out |
| `account_actions` | Command batches | `action_type`, `idempotency_key` unique per account, `target_scope` (`all`/`selected`), `expires_at`; targets are rows in `account_action_targets` |
| `device_commands` | Durable commands | One row per device/action; `command_type`, `state`, timestamps, `result_code`; unique `(action_id, device_id)` |
| `command_events` | Append-only audit | One event per state per command; `event_type`, `result_code`, `detail` |
| `private.device_enrollments` | Enrollment tokens | SHA-256 token hash, account-configured lifetime (default 10 minutes), consumed/revoked timestamps; consumed device FK cascades on device deletion; deny-all RLS (RPC access only) |

Relationships: accounts → devices/global settings/actions; devices → device overrides/status/
commands; actions → targets/commands; events reference `(command_id, account_id, device_id)`.
The effective value of each synced setting is resolved only in Postgres:
`device_settings` override, otherwise `account_settings` global.

## Constraints and indexes (highlights)

- Command/action consistency trigger: a command's `account_id`, `command_type`, and `expires_at`
  must match its action; per-device actions also require a matching `device_id`.
- Idempotency keys are bound to the action type/scope: reusing a key for a different action/device
  raises an error instead of returning the old action.
- Revoking a device terminalizes `queued`/`received` commands as `rejected` and `executing` commands
  as `failed` with `device_revoked_during_execution` (the outcome may be unknown).
- `reset_initiated` is terminal and written before the destructive call; there is no post-wipe
  acknowledgement (the device may be erased).
- Command state allowlist and per-type terminal states (see [remote-protocol.md](./remote-protocol.md)).
- Partial pending-command index `(device_id, created_at) where state in ('queued','received')`.
- Status index `(account_id, last_seen_at desc)`; account action/command history indexes.
- FK-covering indexes on action/device/event relationships.

## Row-level security

- RLS is **enabled and forced** on every remote table.
- `authenticated` gets `SELECT` only; `anon` has no table access; `private` schema usage is revoked.
- Policies:
  - owner sees rows where `account_id = auth.uid()`;
  - a device sees its own `devices`, `account_settings`, `device_settings`, and `device_commands`
    rows via `devices.auth_user_id = auth.uid()` and `revoked_at is null`; devices do **not** read
    `device_status`, `account_actions`, `account_action_targets`, or `command_events` (they receive
    status/settings via reconcile).
- No INSERT/UPDATE/DELETE policies; all writes go through SECURITY DEFINER RPCs.
- Default privileges are stripped so future tables are not auto-exposed.

## RPC catalog

All functions are `security definer`, `search_path = ''`, and executable only by `authenticated`;
anonymous callers are distinguished by the JWT `is_anonymous` claim. Private helpers are revoked
from all roles.

### Owner RPCs

| RPC | Purpose / authorization |
| --- | --- |
| `initialize_owner_account(p_handle)` | Creates the account + settings row; idempotent per auth user |
| `update_owner_handle(p_handle)` | Changes the signed-in owner's account name; validates the handle and enforces the case-insensitive unique index |
| `hook_single_owner_signup(event)` | Auth Hook: allows anonymous devices, rejects every later non-anonymous Auth signup once an owner exists; executable only by `supabase_auth_admin` |
| `create_device_enrollment()` | Issues a 64-hex single-use token using the account enrollment lifetime (default 10 minutes); tokens are independent per device |
| `rename_device(p_device_id, p_name)` | Rename an owned active device |
| `revoke_device(p_device_id)` | Revokes device; queued/received commands become `rejected`/`device_revoked`; `executing` commands become `failed`/`device_revoked_during_execution` (outcome unknown); terminal rows are preserved |
| `update_account_settings(p_expected_revision, p_lock_timer_minutes, p_usb_trigger_enabled, p_lock_trigger_enabled)` | Revision-protected global trigger settings update |
| `update_account_limits(p_expected_revision, p_enrollment_token_ttl_minutes, p_lock_command_ttl_minutes, p_force_reset_ttl_minutes, p_reset_timer_ttl_minutes, p_debug_mode_on_ttl_minutes, p_debug_mode_off_ttl_minutes)` | Revision-protected account limits update; independent of trigger revisions |
| `list_device_effective_settings()` | Owner-only effective settings per active device, including override flags and applied revisions |
| `update_device_settings(p_device_id, p_expected_revision, p_patch)` | Revision-protected owner override patch; JSON `null` clears a field to inherit |
| `update_device_notes(p_device_id, p_notes)` | Owner-only private notes for an active device; rejects text over 4000 characters |
| `create_account_action(p_action_type, p_idempotency_key, p_target_scope, p_device_ids)` | Explicit `all` or `selected` action; targets validated and stored before commands |
| `set_device_debug_mode(p_device_id, p_debug_mode, p_idempotency_key)` | Per-device debug command, one explicit target, owner-only |
| `expire_account_commands()` | Owner-triggered expiry of overdue queued/received commands |
| `list_latest_device_commands()` | Read-only, RLS-scoped latest command per device; bounded dashboard fetch |

### Device RPCs

| RPC | Purpose / authorization |
| --- | --- |
| `claim_device_enrollment(p_token, p_installation_id, p_device_name, p_local_lock_timer_minutes, p_local_usb_trigger_enabled, p_local_lock_trigger_enabled)` | Anonymous identity claims a token; binds device; seeds global settings on first claim; replay-safe per installation |
| `recover_device_session(p_device_id, p_installation_id)` | Anonymous-only, active-device recovery: rebinds the same device row to a replacement anonymous identity without another enrollment token; requires the encrypted installation id |
| `device_reconcile(p_device_id, ...)` | Upserts status/battery, lazily expires overdue commands, returns server time, device, effective settings with both revisions, pending commands |
| `device_transition_command(p_device_id, p_command_id, p_expected_state, p_new_state, p_result_code, p_detail)` | Compare-and-set state transition with legal matrix, expiry gate, audit event |

## Realtime

Publication `supabase_realtime` contains exactly: `account_settings`, `devices`, `device_status`,
`device_settings`, `device_commands`. Clients subscribe with filters (device id or account id) and
treat events only as invalidation signals; the server-resolved values remain authoritative. The
dashboard subscribes for timer/connection/Debug Mode/battery and settings changes; the visible-tab
poll remains a fallback. This consumes one Realtime message per device per open dashboard at each
heartbeat, which is included in the capacity envelope.

## Maintenance

Two `pg_cron` jobs run daily:

- `wasted-cleanup-anonymous-identities` removes unbound/stale anonymous identities.
- `wasted-cleanup-remote-history` removes terminal command/action history older than 180 days
  (cascading to commands and events) and enrollment tokens older than 30 days.

If `pg_cron` is unavailable, enable it under Database → Extensions before running setup.

## Generated SQL

- Fresh install: `supabase/releases/<current>/setup.sql` (all migrations + extensions guard).
- Upgrade: `supabase/releases/<current>/update-from-<old>-to-<current>.sql`.
- Checks: `verify.sql`; tests in `supabase/tests/` (schema, RLS, RPC, concurrency, maintenance).

Never run setup on an existing project or edit applied migrations.
