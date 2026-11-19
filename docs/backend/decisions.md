# Design decisions (ADR log)

Short architecture decision records so context is not lost if development artifacts are removed.

## ADR-01 — Per-user self-hosting

**Context:** Client requires a portable, self-hosted website; the APK is free for everyone.
**Decision:** Every user creates their own Supabase project and hosts their own dashboard. No
shared backend or central dashboard.
**Consequences:** No operator cost/control for the maintainer; users own data and updates.
Onboarding/upgrades must be documented and guided by the app. A client-operated shared backend
remains a future option if per-user maintenance is unacceptable.

## ADR-02 — App-generated dashboard, no runtime connect screen

**Context:** Users should not edit HTML or use a terminal.
**Decision:** The app bundles a dashboard template (HTML shell plus PWA assets); the setup wizard
injects the project URL + publishable key and exports the configured package via Android SAF. Users
host it themselves through a Netlify project. The app exports one dashboard ZIP format.
**Consequences:** No file editing, no shared URL, works offline. The package contains the public
publishable key (not a secret). Changing keys requires regenerating/re-uploading. A runtime
connection form was considered and rejected by the client.

## ADR-03 — Fresh setup via one SQL file + Auth toggles

**Context:** End users should not need CLI/Docker.
**Decision:** `setup.sql` (generated from migrations) is pasted once into Supabase SQL Editor.
Required Auth settings: enable anonymous sign-ins, keep the Email provider **on**, turn Confirm
email **off**, and enable the Before User Created hook `public.hook_single_owner_signup` (Hook type
Postgres, schema public).
Signup is direct: the first email/password account becomes the owner, and the hook rejects every
later non-anonymous account while allowing anonymous device identities. This protects the
single-owner control plane if the public dashboard URL is discovered. No email delivery or SMTP is used. Anonymous sign-ins
can be left off if Remote Management is not wanted; the owner dashboard still works, but device
enrollment and sync will not.
**Consequences:** No tooling; Auth settings cannot be set via SQL and remain a manual step.
Docker/CLI stays a developer path only.

## ADR-04 — Flat immutable migrations + per-release bundles

**Context:** Migrations can pile up; Supabase CLI requires flat timestamped files.
**Decision:** Keep `supabase/migrations/` flat and immutable (developer history). Map releases in
`supabase/releases.json` and generate per-release folders: `setup.sql`, cumulative update bundles,
`verify.sql`, checksums, and instructions.
**Consequences:** Users never see granular migrations; each install applies the cumulative bundle
matching its version.
Generated artifacts must be regenerated on every release; applied files never change.

## ADR-05 — No automatic update check

**Context:** Client prefers release-notes-driven updates over in-app polling.
**Decision:** The app does not check schema/app versions. Release notes name the exact SQL bundle;
the APK bundles it.
**Consequences:** Simple, no extra endpoint/permission; users must follow notes. Skipping versions
requires applying each bundle in order.

## ADR-06 — No Docker for end users

**Context:** Docker was considered to “automate” updates.
**Decision:** Docker is only for local development/replay. Hosted Supabase updates use SQL or CLI.
**Consequences:** Avoids server administration (TLS, backups, monitoring) for users. Docker Compose
for self-hosted Supabase remains an advanced operator path, not a product install.

## ADR-07 — Publishable key is public; secret key never ships

**Context:** The dashboard/app must talk to Supabase directly.
**Decision:** Embed/accept only the project URL + publishable key; validate and reject
`sb_secret_*`/`service_role`.
**Consequences:** No secret leakage from the dashboard or APK. Security relies on RLS/RPCs and Auth;
the dashboard URL is public and can receive signup attempts (rate-limited).

## ADR-08 — Explicit Create Account (deviation from spec wording)

**Context:** The client spec suggested auto-creating an account on sign-in “if technically
feasible”.
**Decision:** Keep explicit Sign In and Create Account; a failed sign-in never creates an account.
**Consequences:** Prevents accidental accounts from typos; requires one extra action. Flagged for
client confirmation.

## ADR-09 — Debug Mode is fail-closed and locally stored

**Context:** Remote wipe is dangerous; Debug Mode protects testing.
**Decision:** Debug Mode is stored encrypted on-device, defaults ON, and is re-read inside
`ResetExecutor`. The dashboard can toggle it per device (with confirmations); the device applies
without a local dialog.
**Consequences:** Remote Force Reset is impossible while ON; toggling OFF supersedes pending Force
Resets. No remote path can bypass the reset boundary.

## ADR-10 — Notifications are meaningful-event only

**Context:** Spec lists sync success as a notification; heartbeats occur every 60 s.
**Decision:** Notify on settings applied, Reset Timer, Force Reset outcomes, Debug Mode changes,
command failures, first sync, and sync restored after a reconnect (five-minute gap). Ordinary
heartbeat successes are silent.
**Consequences:** The spec’s sync-success notification is covered without a notice every minute.

## ADR-11 — UI stack and compatibility

**Context:** Existing app uses XML/ViewBinding; minSdk 24.
**Decision:** Stay on Views/ViewBinding (no Compose rewrite); keep minSdk 24; no AVD downloads for
legacy APIs unless needed.
**Consequences:** Consistent UI and smaller APK; Compose-specific patterns are not used.

## ADR-12 — Hosting recommendation: signed-in Netlify project

**Context:** Users must host a static dashboard package easily, and later updates must keep the
same URL.
**Decision:** Support one signed-in Netlify project: **Add new project → Deploy manually**, upload
the ZIP, and update the same project through **Deploys**. This is the only supported dashboard
hosting path.
**Consequences:** The project appears immediately in the user's team and has no anonymous claim or
one-hour expiry. Updates reuse the same URL; creating a new project changes it.

## ADR-13 — Update research and support window

**Context:** Self-hosted products need predictable upgrade paths.
**Decision:** Follow the mature-OSS pattern: immutable versioned migrations, baselines for fresh
installs, stepwise upgrades, backup-first, no downgrades, and a previous-release support window.
**Consequences:** Users crossing multiple versions apply each release bundle in order; old release
folders stay available. Squashing applied migrations is discouraged.

## ADR-14 — Revocation terminalizes executing commands

**Context:** A device revoked during `executing` could no longer report, leaving an unresolved
destructive command forever.
**Decision:** `revoke_device` atomically rejects `queued`/`received` commands and fails `executing`
ones with `device_revoked_during_execution`; terminal rows are preserved.
**Consequences:** Operators see a definitive terminal state, but the outcome is explicitly unknown
because the device may have crossed the irreversible boundary. Tests cover the state transition.

## ADR-15 — Fail-closed verification and schema marker

**Context:** A weak post-install check can report PASS on a drifted, insecure database.
**Decision:** `verify.sql` checks catalog invariants (FORCE RLS, exact policies/grants, RPC
properties, Realtime scope, cron, schema marker, future-function PUBLIC denial) and raises on any
FAIL. Migrations set a schema-version comment.
**Consequences:** Setup/update operators get a meaningful check without a CLI. The full SQL suites
remain required in the release gate.

## ADR-16 — Anti-framing requires host headers

**Context:** Meta CSP cannot set `frame-ancestors`; a page without host headers cannot prevent framing.
**Decision:** Production hosting must send framing headers; the repo ships `web/_headers` for
Netlify, plus a frame-busting fallback in the page.
**Consequences:** Upload the ZIP with `_headers` to Netlify. This is documented as a deployment
constraint.

## ADR-17 — Notifications follow meaningful events, not heartbeats

**Context:** The spec lists “device successfully synchronized”, but heartbeats run every 60 seconds.
**Decision:** Notify on settings applied, Reset Timer, Force Reset outcomes, Debug Mode changes,
command failures, first sync, and sync restored after a five-minute gap. Ordinary heartbeat
successes are silent.
**Consequences:** “Sync success” is covered when it is meaningful (first connect or recovery)
without a notification every minute.

## ADR-18 — Devices use anonymous identities plus enrollment tokens

**Context:** The client requires every device to be associated with the account and to have a unique
authentication/authorization credential, without exposing the owner password to devices. The client
also allows automatic account creation only “if technically feasible”; explicit owner accounts are
kept for safety (ADR-08).
**Decision:** The owner signs in with email/password on the dashboard. Each installation signs in
anonymously and is bound to the owner by a single-use, hashed enrollment token with a configurable
lifetime (default 10 minutes). RPCs
distinguish owner and anonymous sessions; RLS scopes all data to the account.
**Consequences:** Anonymous sign-ins must be enabled for Remote Management, but not for the rest of
the app. Disabling them still allows owner sign-in/dashboard use; devices cannot enroll or sync.
Anonymous endpoint abuse is bounded by rate limits and cleanup. A single-owner guard prevents a
second usable dashboard account. Rename/revoke on the dashboard manage device records without
changing this identity model.
