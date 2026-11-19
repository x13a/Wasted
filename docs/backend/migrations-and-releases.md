# Migrations and releases (authoritative)

This document is the canonical policy for schema changes and release artifacts. Update it whenever
the workflow changes.

## Folder layout

| Path | Kind | Purpose |
| --- | --- | --- |
| `supabase/migrations/` | Source (immutable) | Flat, timestamped migration history; Supabase CLI requires this layout |
| `supabase/releases.json` | Source | Maps app version → schema version → cumulative migration list |
| `supabase/build-setup-sql.mjs` | Tool | Validates the manifest and generates release SQL |
| `supabase/releases/<appVersion>/` | Generated | `setup.sql`, `update-from-*.sql`, `verify.sql`, `manifest.json`, `README.md` |
| `supabase/releases/README.md` | Generated | Index of releases |
| `supabase/setup.sql` | Generated alias | Current release baseline (convenience + Gradle input) |
| `app/src/main/assets/setup.sql` | Generated | Bundled baseline for the setup wizard |
| `app/src/main/assets/updates/` | Generated | Bundled upgrade bundles for the current release |
| `supabase/tests/` | Source | SQL suites |
| `supabase/verify.sql` | Source | PASS/FAIL checks copied into every release folder |

**Why migrations stay flat:** Supabase CLI requires migration files directly under
`supabase/migrations/`, named `<timestamp>_<name>.sql`. Subfolders break `supabase db push`,
`migration list`, and `db diff`. The flat folder is developer history; users only ever see generated
release folders.

## Naming convention

Existing files keep their names (applied migrations are immutable). New files:

```
<YYYYMMDDHHMMSS>_<area>_<short_change>.sql
```

| Area | Use for |
| --- | --- |
| `schema` | Tables, columns, constraints |
| `rpc` | Functions/RPCs, triggers |
| `rls` | Policies, grants, role hardening |
| `realtime` | Publication membership |
| `maintenance` | pg_cron jobs, cleanup |
| `index` | Indexes/performance |
| `data` | Intentional data fixes/backfills |
| `feature` | Multi-object feature slices (prefer the specific areas) |

Examples: `20261101120000_rpc_debug_mode_audit.sql`,
`20261110120000_schema_device_notes.sql`. One logical change per file; never combine unrelated
changes.

## Release manifest (`supabase/releases.json`)

```json
{
  "current": "3.1.0",
  "releases": [
    {
      "appVersion": "3.0.0",
      "schemaVersion": 1,
      "migrations": ["20261018125854_remote_schema.sql", "…"]
    },
    {
      "appVersion": "3.1.0",
      "schemaVersion": 2,
      "migrations": ["20261018125854_remote_schema.sql", "…", "20261101120000_rpc_….sql"]
    }
  ]
}
```

Rules enforced by the generator:

- Every migration on disk is covered exactly once.
- Each release's migration list starts with the previous release's list (append-only).
- `current` is the newest release.
- `schemaVersion` increments only when SQL changes; an app-only release repeats the migration list
  and produces no update bundle.

## Generated artifacts (never edit)

For every release folder:

- `setup.sql` — fresh install baseline for that exact release.
- `update-from-<older>-to-<release>.sql` — cumulative upgrade bundle containing every migration
  added since `<older>`.
- `verify.sql` — post-setup checks.
- `manifest.json` — app/schema versions, setup/update hashes, and update metadata.
- `README.md` — apply instructions, Auth checklist, file checksums.

Generate with `node supabase/build-setup-sql.mjs`, or automatically via Gradle before asset merging.
Gradle fails the build if the manifest does not cover the migrations.

## Baseline history

Migration history was reset before the public release. `supabase/migrations/` contains a single
baseline, `20261111200000_remote_baseline.sql` (schema version 1), generated from the live project
with the official CLI:

```bash
supabase db dump --linked --keep-comments -f /tmp/schema.sql
```

`pg_dump` does not capture everything, so the baseline additionally contains three things the dump
cannot produce. Keep all three when regenerating it:

1. **pg_cron jobs** — they are rows in `cron.job`, not schema. The dump omits them.
2. **Default-privilege revokes** — the dump records default ACL *grants* but not the matching
   `REVOKE ... FROM anon, authenticated`, so platform defaults would re-expose new objects.
3. **`notify pgrst, 'reload schema'`** so the new RPCs are visible to PostgREST immediately.

The 44 pre-baseline migrations are kept locally in `supabase/archive/migrations/`, which is
gitignored. They are historical only; a fresh project starts from the baseline.

**After a baseline collapse, the migration history of any project that ran the old chain no longer
matches local files.** `supabase db push` on such a project stops with *"Remote migration versions
not found in local migrations directory"*. Reset that project (the intended pre-release action) or
repair its history. Never run both `setup.sql` and `db push` against one project.

Docker is only required for `db dump`, `db pull`, `db diff`, `db reset`, and `migration squash`.
`db push`, `db query`, `migration list`/`repair`, and `gen types` talk to the target directly.

## Adding a migration

1. Create a new timestamped file in `supabase/migrations/` (never edit an applied file).
2. Write additive, backward-compatible SQL; avoid destructive changes. If unavoidable, plan
   expand → migrate → contract across releases.
3. Add the file to the current/new release entry in `supabase/releases.json`.
4. Run the SQL suites and `./gradlew :app:verifyRemoteSchema` (Docker + linked project).
5. Regenerate artifacts and commit them with the release.

## Cutting a release

1. Bump the app version in `app/build.gradle`.
2. Add a release entry to `supabase/releases.json` (see above).
3. Run tests and parity checks (`docs/backend/verification.md`).
4. Build the APK; Gradle regenerates the template, setup SQL, and update bundles into assets.
5. Review `supabase/releases/<version>/README.md` and `manifest.json`.
6. Publish release notes with the exact update bundle name and checksum; warn users to back up.
7. Archive the release folder with the release (it is committed).

Full checklist: [release-checklist.md](./release-checklist.md).

## Upgrade policy

- **Cumulative bundles.** A release generates one update bundle from every earlier release in
  `releases.json` (e.g. `update-from-3.0.0-to-3.2.0.sql`). Users pick the bundle matching their
  installed version; one file covers the whole jump.
- **Tested window.** The release gate tests the previous release path; older jumps are generated but
  best-effort. State the tested path in release notes.
- **No downgrades.** Restore a backup instead.
- **Additive/backward-compatible.** An older APK must still work against a newer schema; that is the
  rollback path for the app.
- **Backups first.** Supabase Free has no PITR; users must export/back up before running an update.
- **No automatic update check.** Release notes are the contract; the app does not poll versions.

## Squashing and archiving

- Do **not** rewrite applied migrations casually. History/checksum tables will disagree.
- Supabase `migration squash` produces a schema-only file and omits data, cron jobs, storage
  buckets, and vault secrets — it cannot replace these generated artifacts.
- If the flat folder becomes unwieldy at a major milestone: create a new baseline release, repair
  the migration history, keep old release folders/update bundles for supported upgrades, and
  document the new minimum schema.

## Verification

- Fresh install: apply `setup.sql` to an empty project, run `verify.sql` and the SQL suites.
  `verify.sql` fails closed on catalog invariants (FORCE RLS, exact policies/grants, RPC properties,
  Realtime scope, cron, schema marker, future-function PUBLIC denial).
- Upgrade: apply the previous release's setup to an empty project, then the new update bundle, run
  `verify.sql` and the suites; `db diff --linked` must be empty.
- Generated files current: run the generator and `git diff --exit-code`.
- More: [verification.md](./verification.md).

The schema marker is `comment on schema public is 'wasted-remote-schema:<version>'`; it is updated by
the relevant migration and checked by `verify.sql`. It gives operators a simple installed-version
signal without an app-side update check.

## Re-running migrations / raw SQL

- `supabase db push` (or `supabase migration up`) is safe to run repeatedly: it compares timestamps
  with `supabase_migrations.schema_migrations` and applies **only pending** migrations.
- `supabase db reset` intentionally drops and recreates the database, then applies everything from
  scratch. It is safe for local/test databases and destructive for a real project.
- Running `setup.sql` or an old migration file manually in the SQL Editor is **not** idempotent. It
  does not consult migration history and will fail (or partially apply) on existing objects.
- Never edit an applied migration: history/checksums will not match, the change will not reach
  databases that already ran it, and `db push` can stop with sync errors. Add a new forward-only
  migration instead.
- Users never run migrations individually: fresh installs run `setup.sql`; upgrades run one
  generated update bundle. `setup.sql` is regenerated from all migrations, so it always represents
  the full current schema.

## Pre-release baseline (optional; wait for schema freeze)

Migrations are developer history; users never see them. `setup.sql` is regenerated from all
migrations, so it is already the latest complete schema. Do **not** delete migrations while the
schema is still changing.

At a schema freeze (release time), a clean baseline can be created deliberately:

1. Freeze schema changes and verify `setup.sql` on a brand-new project.
2. Generate a new baseline release and archive the old migration files.
3. Reset/repair migration history in the same operation (or start a fresh project).
4. Keep the old release folders/update bundles for supported upgrades.
5. Re-run fresh-install and upgrade-path verification.

Do not use a live schema dump as the baseline: it excludes managed schemas and can miss cron jobs,
publication membership, default ACLs, and the schema marker. The generated `setup.sql` is the
baseline.

## Common mistakes

- Re-running `setup.sql` on an existing project (fails; use the update bundle).
- Editing an applied migration (breaks history/checksums).
- Adding a migration without updating `releases.json` (build fails on purpose).
- Applying an update bundle twice or out of order.
- Mixing SQL-editor installs with `supabase db push` migration history.

## Industry practices and sources

Mature self-hosted products converge on the same pattern used here: immutable versioned history,
a generated baseline for fresh installs, cumulative release deltas, backups before upgrades, and a
supported upgrade window.

- **Flyway-style versioned migrations:** applied exactly once, tracked in a history table with
  checksums; baselines cover existing databases; repeatable migrations handle views/functions.
  <https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations>
- **Expand/contract (parallel change):** add new structures, migrate, then remove old ones across
  releases so old clients keep working.
  <https://martinfowler.com/bliki/ParallelChange.html>
- **Supabase migrations:** `supabase db push` applies pending migrations; `db diff --linked`
  verifies parity. SQL-editor installs bypass migration history, so choose one path per project.
  <https://supabase.com/docs/guides/deployment/database-migrations>
- **Supabase CLI `db dump`:** runs `pg_dump` in Docker, excludes managed schemas, and omits data/
  roles by default; it is a backup/verification tool, not an install script.
  <https://supabase.com/docs/reference/cli/supabase-db-dump>
- **Supabase `migration squash`:** schema-only; omits data, cron jobs, storage buckets, and vault
  secrets.
  <https://supabase.com/docs/reference/cli/supabase-migration-squash>
- **Immich:** semver, read release notes/breaking changes, bump pinned version, restart; migrations
  on startup; no downgrade; backup before risky migrations.
  <https://docs.immich.app/install/upgrading/>
- **Nextcloud:** stepwise major upgrades, maintenance mode, backup first, no skipping majors, no
  downgrade, background migrations must finish.
  <https://docs.nextcloud.com/server/stable/admin_manual/maintenance/upgrade.html>
- **12-factor admin processes:** migrations are one-off admin processes shipped with the release,
  run against the same code/config.
  <https://12factor.net/admin-processes>
- **Auto-updaters (Watchtower):** risky for stateful services; pin tags, opt in per service,
  exclude databases, monitor.
  <https://dockerspot.com/blog/how-to-automate-docker-updates-2026/>
- **Supabase self-hosting with Docker:** operator owns provisioning, TLS, backups, monitoring, and
  updates; Docker is not a normal-user install path.
  <https://supabase.com/docs/guides/self-hosting/docker>
