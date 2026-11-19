# Backend overview

Remote Management is optional. The app stays fully functional offline; the backend only
synchronizes settings, timers, status, and commands.

## Deployment model

- **Primary path: Supabase Cloud, free plan.** Every user creates their own free Supabase project
  and hosts their own dashboard copy. There is no shared backend or central dashboard URL. This is
  the supported, tested deployment and the one the setup wizard documents.
- **Optional bonus: self-hosted Supabase.** The same `setup.sql` works against a self-hosted
  instance for users who already run one. It is not required, not the default, and not a
  prerequisite anywhere in the product.
- Docker is only for local development/replay and developer-side schema tooling.
- The public APK ships unconfigured and makes no network calls until the user configures, enables,
  and enrolls.

## Components

```
Android app ── HTTPS/WSS ──► Supabase (Auth / PostgREST / Realtime)
     │                              ▲
     │ bundled dashboard template   │ owner signs in from browser
     └─ setup wizard ── SAF export ─┘
            │
            └─ user hosts the package (Netlify project)
```

| Component | Responsibility |
| --- | --- |
| `app/src/main/java/me/lucky/wasted/remote/` | Config, session, sync, command processing, realtime, recovery, UI |
| `app/src/main/java/me/lucky/wasted/admin/ResetExecutor.kt` | Only destructive boundary; Debug Mode re-check |
| `web/` | Dashboard sources; build emits a multi-file PWA package |
| `supabase/migrations/` | Immutable ordered schema history |
| `supabase/releases/` | Generated per-release SQL (setup, updates, verify, manifest) |
| `supabase/tests/` | SQL suites for schema/RLS/RPC/concurrency |

## Identities

- **Owner:** normal email/password Supabase Auth user. Explicit Create Account.
- **Device:** separate anonymous Supabase Auth user per installation, bound to the owner only by a
  one-time enrollment token and the `claim_device_enrollment` RPC.

## Data flow

- Devices reconcile over Realtime (online) or a 60-second heartbeat; commands are durable rows and
  apply at the next sync.
- Realtime events are only invalidations; app and dashboard always refetch durable state.
- Settings are revision-protected; devices apply only newer revisions.
- Command expiry is configurable per account in **Controls → Command & enrollment limits**.
  Defaults: `reset_timer` 24 h; `force_reset` 10 min; `lock_now` 5 min; `debug_mode_on` 24 h;
  `debug_mode_off` 10 min. Enrollment tokens default to 10 minutes.

## Safety and security

- RLS is enabled and forced; direct writes are denied; SECURITY DEFINER RPCs are the only write
  path and enforce ownership.
- Debug Mode defaults ON and is re-read inside `ResetExecutor`; remote Force Reset cannot execute
  while it is ON.
- Per-device Debug Mode control is owner-only; queuing OFF supersedes pending Force Resets.
- Publishable keys are public; secret/service-role keys never ship.

## Documentation map

- Quick start: [quickstart.md](./quickstart.md)
- Architecture: [../architecture.md](../architecture.md)
- Setup: [setup.md](./setup.md)
- Database schema/RPCs: [database.md](./database.md)
- Remote protocol/commands: [remote-protocol.md](./remote-protocol.md)
- Migrations/releases: [migrations-and-releases.md](./migrations-and-releases.md)
- Decisions (ADR): [decisions.md](./decisions.md)
- User updates: [updates.md](./updates.md)
- Release runbook: [release-checklist.md](./release-checklist.md)
- Security: [security.md](./security.md)
- Hosting: [hosting.md](./hosting.md)
- Capacity: [capacity.md](./capacity.md)
- Troubleshooting: [troubleshooting.md](./troubleshooting.md)
- Verification: [verification.md](./verification.md)
