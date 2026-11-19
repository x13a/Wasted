# Wasted Remote — Architecture & Verification Handoff

**Purpose:** give a fresh reviewer (human or agent) everything needed to understand, verify, and
critique this architecture without re-reading the whole conversation. No secrets are included.

**Status:** implementation complete for v3.0.0; validation gates (emulator/device, soak, Docker
replay, fresh-project install, usage reports, wipe E2E) remain open. `plan.md` is the detailed
checklist; this file is the summary and the "why". Canonical documentation lives in
[`docs/README.md`](docs/README.md) and is self-sufficient; this handoff and `plan.md` are temporary
development artifacts that may be deleted without losing operational knowledge.

---

## 1. What this project is

Wasted (Modded) is an Android app (minSdk 24) with Device Admin, local triggers, P2P, and an
optional **Remote Management** layer. The client specification is `archive/wasted update.txt`:
remote settings (Lock Timer, USB Trigger), Reset Timer, Force Reset, device status/registration,
Debug Mode, optional notifications, offline behavior, and a portable self-hosted website.

**Deployment model chosen by the client:**

- The APK is free/public; **every user creates their own Supabase project** and **hosts their own
  dashboard copy**. There is no shared/central dashboard URL.
- The app's setup wizard collects the user's project URL + publishable key, gives them the SQL and
  Auth checklist, then generates a configured single-file dashboard for them to host.
- Backend: Supabase Cloud (managed). No Docker or CLI is required for end users.

## 2. Current state

- App v3.0.0 (versionCode 42); debug APK builds. The signed-release task is fail-closed, but the
  actual release certificate still needs release-gate verification.
- Tests: Android **110/110**, web **41/41**, SQL suites **4/4** (schema, RLS, RPC, concurrency) plus
  the verifier drift negative test, run against the linked project in rolled-back transactions.
  Schema version is **4**; the linked project has display-name support and the single-owner signup hook.
- Implemented: Debug Mode boundary, ResetExecutor, reset ordering, per-device Debug Mode commands,
  notifications, Android Remote Management screen, setup wizard, dashboard, enrollment, sync,
  recovery worker, docs.
- Offline UX: the PWA shell shows an explicit offline banner, distinguishes cached from missing data, and disables remote actions/settings/enrollment until reconnect.
- Latest dashboard/auth UX: separate Sign In/Create Account forms, live email filtering and
  lowercasing, password visibility toggle, direct owner signup, editable device names,
  dashboard rename/revoke, confirmation dialogs for settings/tokens/commands, setup-guide links, and
  a `npm run dev` source-testing path, and a responsive tabbed dashboard (Devices, Controls, Account, Help) with inline SVG icons, switches, semantic message colors, and an installable PWA package with offline app shell.
- Account persistence fix: the signup handle is stored in Supabase auth metadata, the first
  direct signup creates the owner row immediately, and the dashboard Account panel edits the
  Supabase-backed name. There is no browser-local profile fallback or confirmation-email UI.
  Sign-in shows explicit inline validation and Create Account confirms the password.
- Latest audit fixes: exact verifier policy/ACL checks + real-verifier negative harness, single-owner
  account guard, idempotent legacy command repair, deployable ZIP (`index.html` + `_headers`), Sign In
  accepts existing passwords, clipboard warning, cumulative update documentation, and P2P
  certificate pinning/identity binding with an encrypted P2P database.
- Audit 6 fixes: unavailable P2P storage is now a typed failure caught by both the service and P2P
  screen, with an explicit confirmed local-reset recovery path; legacy import clears the obsolete
  certificate hash, deletes residual plaintext on the completed-marker path, and reports marker
  failures accurately; pairing cancellation is nonce-qualified and outbound pairing now has
  PAIRING/failed/timeout states plus a UI cancel action; remaining device/peer names and profile IDs
  were removed from logs; the verifier harness uses a temp directory and a relative `\i` path.
  Endpoint retry falls back to the last trusted address on the next attempt.
- Audit 7 fixes: local reset now deletes both secure and legacy database families and treats absent
  artifacts as clean; the secure database is synchronously opened before publishing the singleton so
  wrong-key/corruption surfaces through the typed recovery path; legacy sidecars are cleaned on the
  marker/no-main paths; pairing success is sent only after durable persistence, QR pairing propagates
  the start result, concurrent outbound pairing is rejected, cancellation rethrows and does not
  report failure, and raw service exception messages were removed.
- Audit 8 fixes: outbound pairing is a locked peer/nonce/job record; a consumed response
  cancels/clears only that attempt, user cancel is scoped, inbound PIN sessions block outbound
  starts, and cancellation is rethrown in all pairing paths. A failed pairing-response delivery rolls
  back the local pairing and skips settings exchange; manual PIN and QR starts report the real result.
- Audit 9 fixes: PIN generation atomically detaches the exact observed outbound attempt, stale
  send-failure UI is gated on the matching nonce, response rollback is exception-safe with a durable
  pending-revocation block if unpair cannot be confirmed, and `P2PNetwork.sendToPeer` rethrows
  cancellation and closes the socket on cancellation. Linkage failures are wrapped as the typed
  storage error, manual pairing always shows its result, and the plan no longer claims release
  certificate verification.
- Audit 10 fixes: PIN publication is atomic under the pairing lock; revocation reads/writes are typed
  and fail closed, with a plain app-private fallback marker set and revocation reconciliation before
  network initialization; all message dispatch is blocked for pending revocations; post-persistence
  cancellation runs rollback under `NonCancellable`; socket cancellation uses an on-cancelling close
  handler plus an explicit connect timeout.
- Audit 11 fixes: revocation additions/removals are serialized through one store across the
  encrypted and fallback backends with readback verification; both-write failure writes a durable
  fail-closed latch, startup refuses to initialize P2P while the latch or unresolved markers exist,
  and local P2P reset clears markers/latch.
- Audit 12 fix: local P2P reset now returns success only after encrypted markers, fallback markers,
  and the fail-closed latch are all verified empty/absent; a failed cleanup leaves the failure UI in
  place instead of claiming recovery.
- Remote Management now shows a **Device Owner setup required** card when Wasted is not
  Device Owner/profile owner, with a button that opens the P2P screen. The canonical user-facing label
  is **Set up Device Owner**, used consistently on the Remote Management, P2P, and main setup screens.
- Biometric app lock is now optional: **Settings → Security → Biometric app unlock**. Default ON
  preserves the previous launch prompt; turning it off skips `initBiometric()` on the next launch.
- Open: emulator matrix, 16 h soak, instrumentation/log-redaction, browser/a11y suites, latency and
  usage evidence, Docker clean replay, fresh-project `setup.sql` verification, wipe E2E (needs
  explicit approval), final acceptance gate.

## 3. Architecture

```
Android app ── HTTPS/WSS ──► Supabase project (Auth / PostgREST / Realtime)
     │                              ▲
     │ bundled dashboard template   │ owner signs in from browser
     └─ setup wizard ── SAF export ─┘
            │
            └─ user hosts one HTML file (Netlify Drop etc.)
```

- **Owner identity:** normal email/password Supabase Auth user (explicit Create Account); the
  account name is a dashboard display identifier, not a login or device name.
- **Device identity:** separate Supabase anonymous Auth user per installation; bound to the owner
  only by a one-time enrollment token and the `claim_device_enrollment` RPC. The app sends an
  editable device name (prefilled with the phone model); the dashboard can rename it.
- **P2P:** peer TLS leaf certificates are pinned during pairing; client certificates are required;
  handshake and message identities are bound to the verified peer; pairing responses require a
  pending, unexpired, single-use nonce bound to the expected peer/certificate; the P2P Room database
  is SQLCipher-encrypted and legacy rows require re-pairing. Pairing PINs use `SecureRandom` with
  one-time consumption and attempt lockout.
- **Writes:** only through SECURITY DEFINER RPCs; direct table writes are denied; RLS is forced.
- **Realtime:** events are only "sync required" signals; both app and dashboard always refetch
  durable state.
- **Agent/device status:** 60 s heartbeat plus Realtime; offline commands wait until the next sync
  and expire (reset_timer 24 h; force_reset/debug_mode_off 10 min; debug_mode_on 24 h).
- **Destructive boundary:** `ResetExecutor` is the only path to `wipeData()`; Debug Mode is re-read
  at entry and immediately before the destructive call. Force Reset runs only when Debug Mode is
  OFF and requires typed confirmation in the dashboard.
- **Per-device Debug Mode control:** `set_device_debug_mode` creates one action/command for one
  device; queuing OFF expires pending Force Resets for that device.
- **No client configuration shipped:** default APK is unconfigured, performs no network work, and
  is fail-closed (`RemoteEngine.create` returns null unless configured + enabled).

## 4. Repository map

- `app/src/main/java/me/lucky/wasted/remote/` — models, repository, sync coordinator, processor,
  engine, realtime, recovery worker, UI (Remote Management + setup wizard).
- `app/src/main/java/me/lucky/wasted/admin/ResetExecutor.kt` — destructive boundary.
- `app/src/main/assets/dashboard-template.html` — generated dashboard template (build product).
- `app/src/main/assets/setup.sql` — generated database setup (build product).
- `web/` — dashboard sources; `dist/index.html` is a build product.
- `supabase/migrations/` — fifteen forward-only migrations (flat, immutable, CLI-compatible
  history).
- `supabase/releases.json` — release manifest (app version → schema version → migrations).
- `supabase/releases/<appVersion>/` — generated SQL per release: `setup.sql`, update bundles,
  `verify.sql`, `manifest.json`, generated `README.md`; `releases/README.md` is the index.
- `supabase/setup.sql` — generated alias of the current release baseline.
- `supabase/verify.sql` — post-install PASS/FAIL checks.
- `supabase/tests/` — four SQL suites plus the verifier drift negative test.
- `supabase/README.md` — install/update/recreate/rollback quick reference.
- `docs/README.md` + `docs/backend/` — canonical setup, migration/release, update, security,
  hosting, troubleshooting, and verification documentation.
- `plan.md` — phase checklists and contract; `HANDOFF.md` — this file.
- `evidence/` — gitignored validation logs.

## 5. Self-host onboarding (what the user does)

1. Create a free Supabase project.
2. In the app: Remote Management → **Open setup wizard**; paste the Project URL + publishable key
   (never the secret/service-role key).
3. In Supabase SQL Editor: paste and run `setup.sql` once.
4. In Authentication → Providers: **enable Anonymous sign-ins** for devices and **turn Confirm
   email OFF**. Under Authentication → Hooks → Before User Created, select Hook type **Postgres**, schema **public**, function
   `hook_single_owner_signup`, then Save. Keep the documented rate limits. The first account becomes
   owner directly; later email accounts are rejected.
5. Run `verify.sql`; every row must say PASS.
6. In the wizard: **Generate dashboard file** → save as `index.html` via SAF, or use **Save
   deployable package (.zip)** for `index.html` + `_headers`.
7. Host the file (Netlify Drop recommended; any HTTPS static host), claim the site, and copy the
   final URL. When updating, replace `index.html` in the **same site**; a new site changes the URL.
8. Open the dashboard → Create Account. The first account becomes the owner immediately; no
   email is sent and later accounts are blocked. In the app: paste the dashboard URL, Save, Enable;
   optionally set a device name; create an enrollment token in the dashboard and claim it in the app.
9. Send Reset Timer and confirm the device card updates.

**Why the Auth settings are needed:** device identities call `signInAnonymously()`; Supabase treats
anonymous sign-ins as an opt-in provider. Confirm email is **off** so signup is direct and no email
delivery can delay or block the owner. The Before User Created hook
`public.hook_single_owner_signup` allows anonymous devices and rejects every later email/password
account; `initialize_owner_account` remains a second single-owner guard.

## 6. Update management (the confusing part — read this)

### 6.1 The problem

There are four independently updated things:

| Component | Who updates | How | Rollback |
| --- | --- | --- | --- |
| Android APK | user | install the new APK | reinstall the previous APK (schemas stay backward-compatible) |
| Dashboard HTML | user | regenerate in the app, re-upload | keep the previous file |
| Database schema | user (self-host) | apply the release's update SQL, or CLI `db push` | restore a backup / forward-fix |
| Supabase platform | Supabase | managed by Supabase | n/a |

The hard part is the **database schema**, because it is owned by each user and the app cannot run
DDL with a publishable key (and must not ship a secret key).

### 6.2 How mature self-hosted projects do it (research)

- **Immich:** semantic versioning; users read release notes/breaking changes, bump the pinned
  image version, then `docker compose pull && docker compose up -d`; migrations run on startup;
  downgrades are not supported; backups are required before risky migrations.
- **Nextcloud:** upgrades are explicit, step-by-step (latest point release → next major), with
  release-note review, a fresh backup, maintenance mode, no skipping majors, no downgrades, and
  background migrations that must finish before the next upgrade.
- **Self-hosted Supabase:** monthly tested Docker snapshots, pinned `self-hosted/v*` tags, an
  `update.sh` that three-way merges configuration, a changelog, and operator responsibility for
  backups/TLS/updates. Docker Compose is a deployment path only for people who run servers.
- **12-factor:** database migrations are one-off admin processes shipped with the same release,
  run once against the same code/config — not part of request handling.
- **Auto-updaters (Watchtower):** convenient but risky for stateful services; mature setups pin
  image tags, enable auto-updates per service, exclude databases, and monitor.
- **General migration practice:** versioned, ordered, reviewed migrations; backward-compatible
  (expand → migrate → contract); backup/rehearse on a restored copy; record what ran.
- **Flyway-style tooling:** versioned migrations are applied exactly once, tracked in a history
  table with checksums; a baseline covers existing databases; repeatable migrations handle
  views/functions. Companies test both fresh installs and upgrades.

### 6.3 What this project does today

- `setup.sql` is generated from the tracked migrations for fresh projects (deterministic,
  offline); it is not edited by hand and Gradle regenerates it before every APK build.
- `supabase/releases.json` maps every app release to its cumulative migration list and schema
  version. `supabase/build-setup-sql.mjs` validates that the manifest covers every migration
  exactly once, then emits per-release folders:
  - `supabase/releases/<appVersion>/setup.sql` (fresh installs for that release),
  - `supabase/releases/<appVersion>/update-from-<old>-to-<new>.sql` (cumulative bundles from each
    earlier release),
  - `supabase/releases/<appVersion>/verify.sql`, `manifest.json` (SHA-256 checksums), and a
    generated `README.md` with apply instructions.
  The current release's setup and update SQL are copied into the APK assets; the root
  `supabase/setup.sql` is a generated alias for the current baseline.
- **No automatic update check** (client decision). Release notes state exactly which file to run.
- The remote database is verified against the migrations (`supabase db diff --linked` plus
  `supabase migration list --linked`); an empty diff means the remote equals the tracked schema.
- Never re-run `setup.sql`; users apply the generated update bundle for each release they cross.

### 6.4 Update policy (implemented)

**For end users (no tools):**

1. Release notes name the exact update bundle (for example
   `update-from-3.0.0-to-3.1.0.sql`) and the backup step.
2. The APK bundles the setup SQL and the update bundles, so the user can copy the file from the
   release/app without hunting through Git.
3. Back up the project first (Database → Backups, or `supabase db dump`); Supabase Free has no PITR.
4. Apply the cumulative bundle matching the installed version once in SQL Editor, then run
   `verify.sql`; no intermediate bundles are required.
5. Downgrades are not supported; restoring a backup is the rollback path. Keeping migrations
   additive means an older APK still works against a newer schema.

**For technical users / the client's own deployment:** Supabase CLI (`supabase link`,
`supabase db push`) with migration history; CI can run `db diff --linked` and the SQL suites.
Docker is only required for local development/replay, not for hosted updates.

**No automatic update check:** the app does not poll for schema/app versions. Release notes are
the contract. If a future check is ever wanted, it can read a version table, but it is out of scope.

**If this still feels too hard for the audience:** the only truly low-maintenance model is a
**client-operated shared backend** (the client creates one Supabase project and one dashboard; end
users never see Supabase or run SQL). That is the industry-standard consumer model and removes
per-user update management, but it changes the current "everyone self-hosts" requirement and the
"no config in the public APK" rule for that deployment.

### 6.4a How the migration history is organized (company practice)

- **One immutable, timestamped file per logical change** (current convention:
  `20261020131724_remote_debug_mode_commands.sql`). Never edit or renumber an applied migration.
- **Migrations are developer history; users only see generated artifacts.** Granular files can
  accumulate without affecting users: `setup.sql` (baseline) and per-release update bundles are
  generated. This is the standard split between source-of-truth history and distributable
  artifacts.
- **A release manifest maps app version → schema version → migration list** (`releases.json`).
  Schema version increments only when SQL changes; an app-only release reuses the schema version
  and ships no update bundle. Generated artifacts carry checksums for verification.
- **Versioned, applied-once migrations with history/checksums** are the industry norm (Flyway
  tracks a history table and checksums; Supabase uses `supabase_migrations.schema_migrations`).
  Applied files must never change, or history/checksum validation fails.
- **Fresh installs get a baseline, existing installs get deltas.** Flyway calls this baseline +
  versioned migrations; Nextcloud and Immich keep stepwise upgrade paths rather than one giant
  script. Our `setup.sql` is the generated baseline; `updates/` are the deltas.
- **Backward-compatible changes (expand → migrate → contract)** let old clients keep working
  during upgrades. This project only makes additive changes in the initial release; destructive
  changes would need a multi-release expand/contract plan.
- **Test both paths in CI:** fresh apply (setup), upgrade from the previous release (update
  bundle), then verification and `db diff` parity. Testing only fresh installs misses upgrade bugs.
- **On pile-up / squashing:** do not rewrite applied migrations casually. Supabase's
  `migration squash` produces a schema-only file and explicitly omits data, cron jobs, storage
  buckets, and vault secrets, so it cannot replace our generated setup/update artifacts. If the
  folder becomes unwieldy, archive at a major milestone with a new baseline, repair migration
  history, and keep the old update bundles for supported upgrade paths.
- **Supported upgrade window:** publish an explicit matrix (for example, upgrade from the previous
  release only, stepwise for older versions) and keep old update bundles available. Companies
  usually support N-1 or N-2, never arbitrary jumps.

### 6.5 Developer release runbook

1. Add a new timestamped migration; never edit applied migrations.
2. Add a release entry to `supabase/releases.json` with the new `appVersion`, `schemaVersion` (only
   if SQL changed), and the cumulative migration list.
3. Run the SQL suites and `verifyRemoteSchema` (Docker) against the linked project.
4. Build: `./gradlew :app:testDebugUnitTest :app:assembleDebug` — Gradle validates the manifest,
   regenerates `setup.sql`/update bundles, and copies them into assets (Node 20+ required).
5. Publish release notes (breaking changes, exact update bundle, backup warning) and the APK.
6. Verify generated artifacts are current (`git diff --exit-code`) and record the bundle checksums.

### 6.5a User update runbook

1. Read release notes; export a database backup (Supabase Free has no PITR; see [plan.md, Free-plan capacity](plan.md#17-supabase-free-plan-capacity-and-operating-guardrails)).
2. Update the app (APK) — old schemas remain compatible.
3. Apply the named update bundle in SQL Editor (copy it from the release or the app assets).
4. Run `verify.sql`; every row must say PASS.
5. Regenerate and replace `index.html` in the **same Netlify site** (do not create a new site);
   update the Dashboard URL in Remote Management only if the URL actually changed.
6. Apply only the cumulative bundle matching the installed version, then run `verify.sql`.

### 6.6 Why not generate `setup.sql` from a live dump?

- `supabase db dump` runs `pg_dump` in a Docker container against a linked project; it excludes
  Supabase-managed schemas and by default omits data and custom roles.
- Publications and cron jobs are not reliably captured; restoring requires manual patches.
- A dump is a snapshot/backup, not a versioned install or update path.
- Therefore migrations remain the source of truth; dumps verify/backup the remote. `setup.sql`
  is generated from migrations, and parity is checked with `db diff --linked`.

### 6.7 Why not Docker for end users?

Supabase documents Docker Compose as a way to run your own Supabase stack, with the operator
responsible for server provisioning, TLS, backups, monitoring, and updates. That is a server-admin
project, not a normal-user install. The Supabase CLI's local stack is explicitly **not** hardened
for production. Docker would add work, not remove it, for hosted Supabase users.

## 7. Security model (verify these)

- Publishable key is public by design; secret/service-role keys are rejected by validation and
  never embedded. The dashboard contains only URL + publishable key.
- RLS is enabled and forced on all remote tables; direct writes are denied; RPCs are the only
  write surface and enforce ownership/active-device checks.
- Enrollment tokens are single-use, hashed at rest, 10-minute expiry, and revocable.
- Debug Mode defaults ON; `ResetExecutor` re-reads it at the boundary; remote Force Reset is
  impossible while ON.
- No secrets in Git: `.env`, keystore, service keys, dumps, tokens. APK scan shows no key material
  or project URL/ref.
- The generated dashboard URL is public; the first account claims ownership. The Before User Created
  hook rejects every later non-anonymous account and `initialize_owner_account` is a second guard;
  anonymous device identities remain allowed. RLS prevents cross-account access; rate limits and the
  anonymous-identity cleanup limit abuse. Keep the URL private until the owner is created.

## 8. Verification checklist for the reviewing agent

1. Read `archive/wasted update.txt` and compare scope against [sections 1–3](#1-what-this-project-is); flag missing requirements.
2. Inspect RLS/RPCs in `supabase/migrations/` and run the four suites; check cross-account/device
   isolation and denied direct writes.
3. Inspect `ResetExecutor` and `RemoteCommandProcessor`; confirm no other `wipeData()` path and the
   Debug Mode double-check ordering.
4. Build and test:
   - `./gradlew :app:testDebugUnitTest :app:assembleDebug` (Node 20+; assets regenerate).
   - `cd web && npm test && npm run build && npm run build:android-template`.
   - `node supabase/build-setup-sql.mjs` then `git diff --exit-code` (generated files current).
5. Backend: `./gradlew :app:verifyRemoteSchema` (Docker + linked project) must report no changes;
   `supabase migration list --linked` must have no local-only/remote-only versions.
6. Fresh-project gate (open): create an empty project, run `setup.sql`, `verify.sql`, the four
   suites, then connect the generated dashboard and enroll a test device.
7. Security scans: tracked-secret grep; APK key/URL scan; `.env`/keystore git-ignored; check CSP.
8. Review the [update policy](#6-update-management-the-confusing-part--read-this) against the target audience; confirm whether per-user
   self-hosting is still the right product model or a client-hosted backend is needed.

## 9. Known gaps

- **R2-M1 verifier:** exact policy/table/role/mode/predicate matching, extra-policy rejection,
  default-ACL presence, and an effective create-function probe are implemented; verify against a
  fresh database and run the negative drift test (`supabase/tests/remote_verify_drift_test.sql`)
  plus the real-verifier harness (`supabase/tests/verify_negative.sh`).
- **R2-M2 public signup:** a single-owner guard now rejects a second dashboard account, and the
  dashboard maps the error to a sign-in prompt. Public Auth signups may still create inert users;
  rate limits remain, and disabling email signups/host access control is still a deployment choice.
  Verify the live Auth setting.
- **Anti-framing deployment:** the app now exports a deployable ZIP with `index.html` + `_headers`.
  Actual deployed response headers and a benign frame test remain to be verified.
- **R2-L1 sign-in password:** Sign In accepts existing passwords; Create Account keeps the
  12-character rule. Verify no client-side sign-in minimum remains.
- **R2-L2 legacy revocation rows:** the linked project currently has zero `executing` commands;
  the idempotent `private.repair_legacy_revoked_commands()` repair is applied and covered by the RPC
  suite. Re-check on any earlier-schema deployment.
- **R2-L3 docs:** token independence, test counts, notification default, migration count, manifest
  contents, and cumulative update wording are corrected; audit for regressions.
- **R2-L4 clipboard:** dashboard warns that copied tokens remain in OS clipboard/history.
- **P2P hostile-input/runtime tests:** certificate spoof, mismatched handshake/message ID, unpaired
  certificate, replay, legitimate re-pair, encrypted P2P DB, and legacy import still need emulator/
  two-device evidence.
- Upgrade-path CI not wired: apply each generated cumulative bundle to a database at the previous
  release, then run `verify.sql` and the SQL suites.
- Update bundles are in the APK assets but the setup wizard does not list/browse them yet; release
  notes are the current source of which file to run.
- Fresh-project `setup.sql` execution not yet proven end-to-end.
- Emulator/device matrix, soak, latency/battery, browser/a11y, instrumentation/log redaction,
  Docker clean replay + diff, Usage/Realtime reports, wipe E2E approval.
- Custom/self-hosted Supabase domains need a custom CSP/build (the shipped CSP allows
  `*.supabase.co`).
- Auto-create-on-sign-in from the client spec is intentionally replaced by explicit Create Account
  (safer). Confirm this deviation with the client.

## 10. Open decisions / questions

1. Keep per-user self-hosting, or offer a client-operated shared backend for non-technical users?
2. Keep explicit Create Account, or follow the spec's auto-create wording?
3. Add a single-owner signup guard / disable public email signups after owner creation?
4. Is CAPTCHA for anonymous sign-ins required by the client? (Supabase recommends it; not requested.)

## 11. Research sources

- [Supabase CLI — db dump](https://supabase.com/docs/reference/cli/supabase-db-dump)
- [Supabase — Database migrations](https://supabase.com/docs/guides/deployment/database-migrations)
- [Supabase — API keys](https://supabase.com/docs/guides/getting-started/api-keys)
- [Supabase — Anonymous sign-ins](https://supabase.com/docs/guides/auth/auth-anonymous)
- [Supabase — Auth general configuration](https://supabase.com/docs/guides/auth/general-configuration)
- [Supabase — Self-hosting with Docker](https://supabase.com/docs/guides/self-hosting/docker)
- [Supabase — Backup/restore using the CLI](https://supabase.com/docs/guides/platform/migrating-within-supabase/backup-restore)
- [Immich — Upgrading](https://docs.immich.app/install/upgrading/)
- [Nextcloud — How to upgrade](https://docs.nextcloud.com/server/stable/admin_manual/maintenance/upgrade.html)
- [Flyway — Versioned migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations)
- [Martin Fowler — Parallel Change (expand/contract)](https://martinfowler.com/bliki/ParallelChange.html)
- [12-factor — Admin processes](https://12factor.net/admin-processes)
- [Netlify Drop quickstart](https://docs.netlify.com/start/quickstarts/netlify-drop-quickstart/)
- [Watchtower auto-update risks (community overview)](https://dockerspot.com/blog/how-to-automate-docker-updates-2026/)

## 12. Glossary (plain language)

- **Supabase project:** the user's hosted backend (database, auth, realtime). Identified by a URL.
- **Publishable key:** public identifier for that project; safe in web/mobile code. Not a password.
- **Secret/service-role key:** full database access; never in the app or dashboard.
- **RLS:** database rules that limit each signed-in user to their own rows.
- **RPC:** an audited database function; the only write path.
- **Migration:** a versioned SQL change; `setup.sql` is all migrations concatenated for a fresh DB.
- **Dump:** a schema/data snapshot for backup/verification, not an install script.
- **SAF:** Android's file picker; the app uses it to save the generated dashboard.
- **Netlify Drop:** drag-and-drop static hosting; no account needed to start.
- **Anonymous identity:** per-device Supabase account without email/password, bound by enrollment.
