# Architecture

Canonical system architecture for Wasted (Modded) and its Remote Management backend.

## Purpose and scope

Wasted (Modded) is an Android app (minSdk 24) with Device Admin, local triggers, P2P, and optional
**Remote Management**. Remote Management adds:

- remote Lock trigger / Lock delay / USB Trigger configuration,
- Reset Timer and Force Reset commands,
- device registration, timer/status reporting, and Debug Mode status,
- local notifications (on by default, optional),
- a lightweight, portable, self-hosted dashboard.

The app remains fully functional offline. Remote sync is an optional layer.

## Deployment model

- Every user creates **their own Supabase project** and hosts **their own dashboard copy**. There
  is no shared backend or central dashboard URL.
- The app's setup wizard collects the project URL + publishable key, provides the database SQL and
  Auth checklist, and generates a configured dashboard ZIP saved via Android SAF.
- Supabase Cloud is the managed backend. Docker is only for local development/replay.
- The public APK ships unconfigured, makes no network requests, and stays fail-closed until the
  user configures, enables, and enrolls.

## Components

```
Android app ── HTTPS/WSS ──► Supabase (Auth / PostgREST / Realtime)
     │                              ▲
     │ bundled dashboard template   │ owner signs in from a browser
     └─ setup wizard ── SAF export ─┘
            │
            └─ user hosts the package (Netlify project)
```

| Component | Responsibility |
| --- | --- |
| `RemoteSetupActivity` | Guided offline setup; validates URL/key, copies SQL, renders/exports dashboard |
| `RemoteManagementFragment` | Config, enable/disable, Debug Mode toggle, enrollment, sync, reset enrollment |
| `RemoteEngine` | Composition root shared by foreground service and recovery worker |
| `RemoteRuntime` | One process-wide Supabase client/session owner; prevents refresh-token rotation races |
| `RemoteSyncCoordinator` | Single-flight reconcile, settings revisions, journal, outbox retry |
| `RemoteCommandProcessor` | Executes commands with ordering, idempotency, Debug Mode checks |
| `RemoteRealtimeClient` | Realtime invalidations (signals only; never executes payloads); skipped when the session is missing/expired, with the 60 s heartbeat remaining active |
| `RemoteRecoveryWorker` | WorkManager safety net (one-time retry + 15-minute periodic) |
| `ForegroundService` | One service; reconstructs Lock/USB/P2P/remote subsystems; 60 s heartbeat |
| `ResetExecutor` | Only destructive boundary; re-reads Debug Mode |
| Dashboard (`web/`) | Static multi-file PWA UI: auth, device cards, settings, actions, enrollment |

## Identities

- **Owner:** normal email/password Supabase Auth user. Create Account is explicit; a failed
  sign-in never creates an account.
- **Session:** the encrypted Auth session is owned by one process-wide Supabase client. Supabase
  rotates refresh tokens; separate clients sharing one stored session can race and invalidate each
  other. The Realtime client never subscribes without a valid session, and the heartbeat continues
  if Realtime is unavailable.
- **Boot/reboot:** `LOCKED_BOOT_COMPLETED` / `BOOT_COMPLETED` start the foreground service when
  remote management is active; a persisted JobScheduler job and WorkManager recovery path remain as
  fallbacks. `USER_UNLOCKED` hands the remote subsystem from direct-boot to credential-backed
  storage. Credential-encrypted storage, including the remote session and settings, remains
  unavailable until that first unlock by Android design. Some OEM autostart managers block reboot
  launches until the user allowlists Wasted; see the README reliability section.
- **Device:** separate anonymous Supabase Auth user per installation. It has no access to owner
  data until an authenticated owner creates a one-time enrollment token and the device claims it;
  the claim RPC binds the device row to the owner account.
- Anonymous device identities never gain owner powers; RPCs check `is_anonymous`/ownership.

## P2P trust

- Each installation has a persisted self-signed TLS certificate. Pairing pins the peer's leaf
  certificate SHA-256; paired connections require that exact certificate, and the server requires a
  client certificate.
- The authenticated handshake binds the TLS certificate to one device ID; every message must match
  that ID and the local recipient before dispatch. A paired peer whose certificate changes is
  rejected and must be re-paired. Pairing responses require a pending, unexpired, single-use nonce
  bound to the expected peer and certificate.
- The P2P Room database is SQLCipher-encrypted with a separate Tink-wrapped passphrase. Legacy
  plaintext peer rows are imported as unpaired rows; users re-pair once so the old APK-signature
  hash is never trusted as a TLS pin.
- Pairing PINs use `SecureRandom`, are consumed after one successful use, and have a failed-attempt
  lockout.

## Data flow and synchronization

- Owner changes settings or queues commands in the dashboard through RPCs.
- Devices sync by Realtime invalidation while online, otherwise on the 60 s heartbeat or a
  WorkManager recovery run.
- Realtime events are only “sync required” signals; both app and dashboard always refetch durable
  state. Command payloads are never executed directly from Realtime.
- Devices write status through `device_reconcile`: settings revision, actual timer/USB values,
  timer remaining/running, Debug Mode, reset support, app/OS, last error, last seen.
- Account settings are revision-protected: devices apply only newer revisions.
- Commands are durable rows with legal transitions and expiry; see
  [remote-protocol.md](./backend/remote-protocol.md).

## Safety invariants

1. `ResetExecutor` is the only path to `wipeData()`.
2. Debug Mode defaults ON; it is re-read at the destructive boundary; missing/corrupt/read error
   means blocked.
3. Remote Force Reset never executes while Debug Mode is ON (`debug_blocked`).
4. Force Reset executes at most once; interrupted `executing` rows fail, never replay.
5. Remote Debug Mode OFF supersedes pending Force Resets for that device.
6. Repeating a command keeps only its newest queued copy per device; different command families
   remain independent, and an executing command is never recalled.
7. RLS is forced; direct writes are denied; SECURITY DEFINER RPCs are the only write surface.
8. Publishable keys are public; secret/service-role keys never ship or appear in Git.
9. Default/unconfigured installs perform zero remote work.

## Offline behavior

- The app keeps local settings, timers, triggers, P2P, and Device Admin behavior unchanged without
  network.
- Commands and settings wait for the next successful sync, subject to expiry.
- On reconnect, the device reconciles authoritative state and drains the encrypted transition
  outbox.

## Notifications

Optional (default ON), gated by the stored preference. Meaningful events only: settings applied,
Reset Timer applied, Force Reset initiated/blocked/rejected, Debug Mode changed, command failures,
first sync, and sync restored after a reconnect. Ordinary 60-second heartbeats stay silent; stable
notification IDs replace instead of stacking.

## Configuration boundary

- Values: Supabase project URL, publishable key, dashboard URL. Stored encrypted on-device.
- Validation: https only, no credentials/query/fragment, length limits, reject `sb_secret_*` and
  legacy `service_role` keys.
- `RemoteEngine.create` returns null unless configured and enabled; no client, session, DB, worker,
  WebSocket, or HTTP is created otherwise.

## Known limitations

- Per-user self-hosting shifts update/backup responsibility to the user; see
  [migrations-and-releases.md](./backend/migrations-and-releases.md).
- No automatic update check (by design); release notes name the SQL bundle to apply.
- Custom/self-hosted Supabase domains need an adjusted CSP/build (default allows `*.supabase.co`).
- A dashboard URL is public; anyone can attempt signups (rate-limited), though RLS protects data.

## Related documents

- [Backend overview](./backend/README.md)
- [Database schema and RPCs](./backend/database.md)
- [Remote protocol and command semantics](./backend/remote-protocol.md)
- [Design decisions](./backend/decisions.md)
- [Security model](./backend/security.md)
- [Verification](./backend/verification.md)
