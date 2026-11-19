# Remote protocol and command semantics

Self-sufficient contract for app ↔ Supabase ↔ dashboard behavior. Source of truth for wire names,
states, TTLs, and ordering.

## Reconcile request (`device_reconcile`)

The device sends (all `p_` prefixed):

| Field | Meaning |
| --- | --- |
| `p_device_id` | Device row id |
| `p_applied_global_settings_revision` | Last global settings revision applied |
| `p_applied_device_settings_revision` | Last per-device override revision applied |
| `p_actual_lock_trigger_enabled` | Current local Lock trigger |
| `p_actual_lock_timer_minutes` | Current local Lock delay |
| `p_actual_usb_trigger_enabled` | Current local USB trigger |
| `p_timer_remaining_seconds` / `p_timer_running` | Timer state |
| `p_debug_mode` | Current Debug Mode |
| `p_reset_supported` | Device Admin/reset support |
| `p_remote_enabled` | Remote Management enabled locally |
| `p_battery_percent` / `p_battery_charging` | Reported battery; null means unknown, `0` is real |
| `p_app_version` / `p_android_sdk` / `p_android_release` | Build info; `p_android_release` is `Build.VERSION.RELEASE` from the phone |
| `p_last_error_code` / `p_last_error_message` | Sanitized last error (nullable) |

Response:

```json
{
  "server_time": "…",
  "device": { "id": "…", "account_id": "…", "name": "…", "installation_id": "…", "enrolled_at": "…" },
  "settings": {
    "global_revision": 2,
    "device_revision": 1,
    "lock_trigger_enabled": true,
    "lock_timer_minutes": 1440,
    "usb_trigger_enabled": true,
    "initialized": true
  },
  "commands": [
    { "id": "…", "action_id": "…", "command_type": "reset_timer", "state": "queued", "created_at": "…", "expires_at": "…" }
  ]
}
```

Reconcile also upserts `device_status`, lazily expires overdue commands, and returns only
`queued`/`received` commands that have not expired, ordered by `created_at`.

## Command targeting

Owner commands require an explicit `p_target_scope`:

- `all`: snapshot all enrolled (non-revoked) account devices at creation; newer devices do not receive the action.
- `selected`: require a non-empty set of distinct, owned, enrolled (non-revoked) device IDs. Validation is
  all-or-nothing and no other device receives a command.

An `account_action_targets` row is written for every target before `device_commands`. The command
trigger rejects any command without a matching target. Idempotency keys are bound to the action
type, scope, and, for selected actions, the exact target set.

Devices still receive only their own commands and can never target another device.

## Command types and TTLs

TTLs are account-configurable in **Controls → Command & enrollment limits**. The table shows the
defaults; `update_account_limits` stores the values and `create_account_action` /
`set_device_debug_mode` apply them when a command is created.

| Type | Target | Default TTL | Terminal outcomes |
| --- | --- | --- | --- |
| `reset_timer` | Selected targets or all enrolled devices | 24 h | `applied`, `rejected`, `failed`, `expired` |
| `force_reset` | Selected targets or all enrolled devices | 10 min | `reset_initiated`, `debug_blocked`, `rejected`, `failed`, `expired` |
| `lock_now` | Selected targets or all enrolled devices | 5 min | `applied`, `failed`, `expired` |
| `debug_mode_on` | One selected device | 24 h | `applied`, `rejected`, `failed`, `expired` |
| `debug_mode_off` | One selected device | 10 min | `applied`, `rejected`, `failed`, `expired` |

Enrollment tokens use the account's `enrollment_token_ttl_minutes` (default 10 min). The dashboard
active/inactive label uses the fixed 150-second connection window; it is display-only and is not a
device setting.

States: `queued → received → executing → applied` plus terminal `debug_blocked`, `rejected`,
`failed`, `expired`, and the Force Reset terminal `reset_initiated`.

Legal transitions:

| From | To |
| --- | --- |
| `queued` | `received`, `expired` |
| `received` | `executing`, `debug_blocked`, `rejected`, `failed`, `expired` |
| `executing` | `applied`, `reset_initiated`, `debug_blocked`, `failed` |

Per-type terminal allowlist is enforced server-side. Each transition appends exactly one
`command_events` row (one event per state per command).

## Execution ordering

### Reset Timer

1. `queued → received` (server compare-and-set).
2. `received → executing`.
3. Restart the lock countdown if active (idempotent).
4. `executing → applied` with `restarted` or `inactive`.
5. Interrupted `executing` rows are re-run safely and completed as `applied`.

### Force Reset

1. `queued → received`.
2. Re-read Debug Mode; if ON → `received → debug_blocked` (`debug_mode`), notify, stop.
3. If reset is unsupported → `received → rejected` (`reset_unsupported`), stop.
4. Persist local `executing`, then require server `received → executing`.
5. Re-read Debug Mode at the boundary; if ON → `executing → debug_blocked`.
6. `executing → reset_initiated` (server) before touching the destructive boundary.
7. Call `ResetExecutor` exactly once (it re-reads Debug Mode again).
8. Interrupted `executing` rows fail with `interrupted_before_reset`; never replay destructively.

`reset_initiated` is the final state. There is no post-wipe acknowledgement because the device may
be erased; the dashboard labels it “reset initiated — no completion signal after a wipe.”

### Debug Mode ON/OFF

1. `queued → received → executing`.
2. Read the current preference; if already equal → `applied` with `already_enabled`/`already_disabled`.
3. Otherwise write the preference once → `applied` with `enabled`/`disabled`.
4. A preference write failure → `executing → failed` (`debug_mode_write_failed`).
5. Interrupted `executing` rows re-apply idempotently and complete.
6. This command is never blocked by the current Debug Mode (it is the toggle itself).

## Idempotency and supersede

- Account actions are idempotent per `(account_id, idempotency_key)`; a replay returns the original
  action and does not create commands.
- Keys are bound to the action type/scope: reusing a key for a different type (or for a per-device
  debug action) raises `idempotency key reused for a different action`.
- `set_device_debug_mode` additionally requires the same device/type on replay.
- A new command expires older `queued`/`received` commands **in the same family** for the same
  device: Reset Timer with Reset Timer, Lock with Lock, Force Reset with Force Reset, and Debug
  ON/OFF with each other. Different families remain independently pending. An `executing`
  command is never recalled.
- Queuing `debug_mode_off` first expires that device's pending `force_reset` commands with
  `superseded_by_debug_off`, so a latent wipe cannot fire after the guard is removed.
- Command/action consistency is enforced by trigger: type, account, expiry, and (when present)
  device must match.

## Revocation semantics

- `revoke_device` is atomic and terminalizes every nonterminal command for that device:
  - `queued`/`received` → `rejected` with `device_revoked`;
  - `executing` → `failed` with `device_revoked_during_execution` (the device may already have
    crossed an irreversible boundary and can no longer report);
  - terminal rows (`applied`, `reset_initiated`, `debug_blocked`, `rejected`, `failed`, `expired`)
    are preserved.
- A revoked device cannot reconcile or transition commands; its session/rows remain denied by RLS.
- A revoked `installation_id` cannot be used to claim a new token. Reset enrollment on the device
  first so the installation id rotates, then claim a fresh token; the result is a new device row
  while the revoked row is retained for audit.

## Settings synchronization

- `account_settings.revision` is the global revision; `device_settings.revision` is the per-device
  override revision.
- `device_reconcile` returns the **server-resolved effective values** and both revisions. The
  phone applies when either revision is newer and records both; it never merges client-side.
- `device_settings` stores nullable overrides. `NULL` means inherit; a device override wins over the
  global value until cleared. Global saves never clear overrides.
- The dashboard uses `list_device_effective_settings()`; it never merges tables in JavaScript.
- Lock trigger, Lock Timer (the delay), and USB Trigger are the synced settings; all other
  preferences stay device-local.
- The Lock trigger arms the countdown and the delay sets its wait. A period without an active
  countdown can mean the phone is unlocked or the Lock trigger is OFF.

## Errors and retries

- Retryable: network, timeout, HTTP 429, 5xx. Bounded exponential backoff (1 s → 60 s, honoring
  `retry-after`, injectable jitter).
- Fatal: authentication, RLS/permission, validation, expiry, illegal transition. Surface a
  sanitized error; do not loop.
- Local journal stores commands by id (insert-ignore); transitions retry through an encrypted
  outbox. The processor never duplicates application on retries (server CAS + local state).
- WorkManager recovery: unique one-time retry after retryable failures + unique 15-minute periodic
  reconciliation (5-minute flex). A safety net, not immediate delivery.

## Delivery behavior

- Online: Realtime event invalidates and triggers a reconcile.
- Offline: next 60 s heartbeat or recovery run; commands/settings apply subject to expiry.
- Freshness in the dashboard: “Active” = last contact within 150 s; otherwise “Inactive”, with the
  exact last contact shown and commands remaining queued.
- Timer display extrapolates locally from `timer_sampled_at` + `timer_remaining_seconds` while
  running.

## Debug Mode semantics

- Default ON; stored encrypted; missing/corrupt/read error = ON.
- ON: destructive remote commands are logged (`debug_blocked`) and notified, not executed; Reset
  Timer and settings sync still work.
- OFF: only via local confirmation (app) or owner command (dashboard, confirmation). Remote OFF
  supersedes pending Force Resets.
- Notifications: settings applied, Reset Timer, Force Reset initiated/blocked/rejected, Debug Mode
  changed, command failures, first sync, and sync restored after a reconnect. Ordinary heartbeat
  successes are silent; notification IDs are stable so repeats replace.

## RPC authorization

- Owner RPCs require a non-anonymous auth user with an `accounts` row.
- Device RPCs require the device's `auth_user_id` to match the JWT and `revoked_at is null`.
- Enrollment requires an anonymous auth user and a valid, unexpired, unused token.
- `recover_device_session(device_id, installation_id)` requires a new anonymous auth user plus the
  encrypted installation id; it rebinds only an active device, preserves its row/history, and never
  consumes a new enrollment token.
- RLS additionally limits reads; direct table writes are denied for both roles.
