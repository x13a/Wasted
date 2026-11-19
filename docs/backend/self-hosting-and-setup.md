# Backend setup, self-hosting, and upgrades (authoritative)

**Deployment model — read this first.**

| Path | Status | Who it is for |
| --- | --- | --- |
| **Free Supabase Cloud project** | **Primary, supported, tested** | Every normal user |
| Self-hosted Supabase | Optional bonus | Users who already run their own instance |

The product is built around a user creating a **free Supabase account** (Cloud) and pasting one
generated `setup.sql`. That is the flow the setup wizard implements, the docs describe, and the
release is verified against. Self-hosting is documented for completeness only: it is never
required, never assumed, and nothing in the product depends on it.

This is the single reference for **how the Wasted Remote backend is packaged, how a user stands
up a fresh Supabase Cloud project, what Docker is and is not needed for, how the schema is proven
equal to the developer's live project, and (optionally) how self-hosting and upgrades work.**

Read this before touching `supabase/`, `releases.json`, or the setup wizard.

---

## 1. What ships to users

Users never run a CLI, a migration tool, or Docker. A user performs exactly this:

1. Create a free Supabase project.
2. In the app: **Remote Management → Open setup wizard**, enter **Project URL** and
   **Publishable key** (never the secret/service-role key).
3. **Save setup SQL** from the wizard, paste it into **Supabase → SQL Editor**, press **Run** once.
4. Toggle Auth settings (see [Manual Auth steps](#3-what-the-setup-file-cannot-do-manual-auth-steps)).
5. Generate the dashboard ZIP, upload it to Netlify.
6. Create the owner account, then create an enrollment token and claim it on the device.

Two generated artifacts make this possible:

| Artifact | Path | Purpose |
| --- | --- | --- |
| Setup SQL | `app/src/main/assets/setup.sql` | Exact file the wizard saves for step 3 |
| Release SQL | `supabase/releases/<version>/setup.sql` | Same bytes, published for manual use |
| Verification | `supabase/releases/<version>/verify.sql` | Read-only PASS/FAIL check to run after setup |
| Manifest | `supabase/releases/<version>/manifest.json` | App/schema version + SHA-256 of each file |

All of these are **generated**. Never hand-edit them.

---

## 2. Source of truth and how the setup file is built

```
supabase/migrations/20261111200000_remote_baseline.sql     (source of truth)
        │
        │  node supabase/build-setup-sql.mjs      (also run by Gradle before asset merging)
        ▼
supabase/setup.sql ──┬──► supabase/releases/3.0.0/setup.sql
                     └──► app/src/main/assets/setup.sql  ──► packaged into the APK
```

Facts to know:

- `supabase/releases.json` maps app version → schema version → ordered migration list. The
  generator **fails the build** if a migration on disk is not covered, or a listed file is missing.
- `supabase/setup.sql`, `supabase/releases/<v>/setup.sql` and `app/src/main/assets/setup.sql` are
  byte-identical. Verify with `shasum -a 256` on all three.
- The wizard's **Save update SQL** button reads `assets/updates/`. When a release adds no schema
  migration the folder is empty and the app correctly reports *"No database update is needed for
  this release."*
- `schemaVersion` increments only when SQL changes.

### Regenerating

```bash
node supabase/build-setup-sql.mjs
./gradlew :app:assembleDebug       # re-runs the generator, then copies into assets
```

---

## 3. What the setup file cannot do (manual Auth steps)

SQL cannot configure the Auth service. These remain manual, and the setup wizard and
`setup.sql` header both list them in this order:

1. **Authentication → Providers → Allow anonymous sign-ins: ON** — each device gets its own
   private identity, so the owner's email/password is never stored on a phone.
2. **Email provider: ON, Confirm email: OFF** — signup is direct, no email is sent.
3. **Authentication → Hooks → Before User Created**: hook **ON**, Hook type **Postgres**,
   Postgres schema **public**, Postgres function **`hook_single_owner_signup`**, **Save**.
   This is what enforces "the first account becomes the owner and no later email account can be
   created"; anonymous device signups are still allowed.
4. **Rate limits**: anonymous 10/hour/IP; sign-in and sign-up 10 per 5 minutes/IP.

Without step 3 the dashboard is still single-owner *by convention only* — a second email signup
would succeed. Treat the hook as part of installation, not hardening.

---

## 3.1. Cleaning an existing project (reuse in place)

`setup.sql` is create-only: it has no `DROP`, so it cannot be re-run over an old schema. To reuse
the same Supabase project for a fresh install, run these in the SQL Editor **in this order**:

```sql
-- 1. Remove the app schema. This also deletes every app RLS policy, trigger and table.
drop schema if exists public cascade;
create schema public;
-- Restore the exact hosted defaults (owner + ACL) so the project looks like a new one.
alter schema public owner to pg_database_owner;
comment on schema public is 'standard public schema';
grant usage on schema public to public;
grant usage on schema public to postgres, anon, authenticated, service_role, supabase_auth_admin;
grant create on schema public to pg_database_owner;
drop schema if exists private cascade;

-- 2. Remove the old owner identity. The single-owner hook (step 5) blocks every new email signup
--    while ANY non-anonymous user with an email exists in auth.users, and dropping the app schema
--    does not touch auth. Deleting these rows is what lets you create the owner again.
delete from auth.users where is_anonymous is false;

-- 3. Clear the stale migration history so `supabase db push` is usable again (optional).
delete from supabase_migrations.schema_migrations;
```

Then:

4. Run `setup.sql` once.
5. Re-check the four [manual Auth steps](#3-what-the-setup-file-cannot-do-manual-auth-steps).
   The Before User Created hook is a dashboard setting that survives the schema drop, so it will
   be pointing at `hook_single_owner_signup` **before** step 4 recreates it — that is expected, but it
   means anonymous device sign-in fails until `setup.sql` has run.
6. Run `verify.sql`; every row must say PASS.

**This deletes every device row**, so enrolled devices must enroll again with a new token. Session
recovery restores an *existing* row for a rotated token; it cannot recreate a deleted one.

Do **not** use this to upgrade a production project: it is only for wiping a project you intend to
treat as brand new.

---

## 4. Docker: what actually needs it

Verified against the installed CLI (v2.121.0) with the Docker daemon stopped and started.

**Needs Docker** (launches `pg_dump`/shadow Postgres in a container):

| Command | Why |
| --- | --- |
| `supabase db dump` | runs `pg_dump` in a one-shot container |
| `supabase db pull` | shadow database diff |
| `supabase db diff` | shadow database diff |
| `supabase db reset` | recreates the local stack database |
| `supabase migration squash` | diff of a natively-provisioned shadow database |
| `supabase start` / `supabase stop` | runs the local stack |

**Does not need Docker** (connects to the target directly):

| Command | Notes |
| --- | --- |
| `supabase db push` | applies migrations straight to the target session |
| `supabase db query --linked` | Management API |
| `supabase migration list` / `repair --linked` | connects to the project |
| `supabase gen types --linked` | connects to the project |
| `supabase db advisors --linked` | Management API |

Practical consequences:

- **Users need no Docker at all.** They paste SQL.
- **Releasing needs no Docker.** Gradle + Node regenerate the setup file; `db push` needs none.
- **Regenerating the baseline from the live project needs Docker** (one-time, developer only).

`db dump`, `db pull` and `migration squash` require `SUPABASE_DB_PASSWORD`. It lives in the
gitignored `.env`:

```bash
set -a; source .env; set +a
```

`db dump`/`db pull` also cannot write to `/tmp` on macOS (the container cannot reach it) — write to
a path inside the repository, e.g. `evidence/audit/live-schema.sql`.

---

## 5. Proving the shipped file equals the live project

The claim "users get exactly what the developer has" must be re-proven whenever the baseline is
regenerated. The proof is a **catalog comparison**, not a visual diff, because `pg_dump` output
ordering and platform defaults differ.

Method (all commands verified):

1. Dump live:
   ```bash
   supabase db dump --linked --keep-comments -f evidence/audit/live-schema.sql
   ```
2. Blank a local database, then apply the **shipped** file (not the migration):
   ```bash
   supabase start
   docker exec -i supabase_db_Wasted psql -U postgres -d postgres -v ON_ERROR_STOP=1 \
     -f - < supabase/releases/3.0.0/setup.sql
   ```
3. Compare these dimensions on both sides. Compare **counts and hashes**, not prose:

| Dimension | Query basis | Result at baseline v1 |
| --- | --- | --- |
| Tables | `pg_tables` (public+private) | 11 — identical |
| Policies | `pg_policies` incl. `qual`/`with_check` | 10 — identical |
| RLS | `relrowsecurity` + `relforcerowsecurity` | 10/10 forced — identical |
| Realtime | `pg_publication_tables` for `supabase_realtime` | 5 — identical |
| Extensions | `pg_extension` + schema | 6 — identical |
| Cron jobs | `cron.job` name+schedule+active | 2 — identical |
| Function signatures | `pg_get_function_identity_arguments` | 29 — identical |
| **Function bodies** | `md5(pg_get_functiondef(oid))` | **29/29 identical** |
| Table grants | `role_table_grants` | 80 — identical |
| Default ACLs | `pg_default_acl` for `postgres` | 7 — identical |
| Function EXECUTE matrix | `has_function_privilege(role, …)` | 38 — identical |

The function-body hash is the strongest proof: it covers the actual SQL, not just the signature.
Record the run in `evidence/audit/REPORT.md`.

**Known, expected difference:** the `public` schema *owner* and its ACL entry. Hosted Supabase
sets the owner to `pg_database_owner` with PUBLIC `USAGE`; the local image leaves it `postgres`.
Both grant `anon`/`authenticated`/`service_role` USAGE, so behaviour and security are unchanged.
This is a platform default, not something the baseline sets.

**Caveat:** `supabase db advisors --local` only evaluates a subset of lints and reported
"No issues found" while the hosted project reported findings. Do not treat the local advisors
result as parity evidence — use hosted advisors plus the catalog comparison.

---

## 6. Rebuilding the baseline from the live project

Only needed if the live project has changed and you want to re-baseline. Keep all three additions:
`pg_dump` alone is **not** a complete setup file.

```bash
# 1. Export DB password
set -a; source .env; set +a

# 2. Dump live schema
supabase db dump --linked --keep-comments -f evidence/audit/live-schema.sql

# 3. Assemble the baseline, then add manually:
#    a) the pg_cron jobs (rows in cron.job — pg_dump omits them)
#    b) default-privilege REVOKEs (pg_dump emits grants but not revokes)
#    c) notify pgrst, 'reload schema';
#    d) set the schema marker comment to the new schemaVersion
```

Why each addition is mandatory:

- **pg_cron jobs are data, not schema.** `pg_dump` never includes them. Without them, anonymous
  identity cleanup and history cleanup silently stop running, and `cron.job` stays empty.
- **Default-privilege revokes.** PostgreSQL grants `EXECUTE` on new functions to `PUBLIC` by
  default, and hosted default ACLs grant `anon`/`authenticated`. The dump records the grants but
  not the matching revokes, so a dump-only baseline **re-exposes every future function**. This was
  caught by `remote_rls_test.sql` ("future tables must not be auto-granted").
- **`notify pgrst, 'reload schema'`** so the new RPCs are callable immediately after setup.

Then update `supabase/releases.json`, bump `schemaVersion` if SQL changed, and regenerate.

### Verifying a rebuilt baseline

```bash
supabase start
supabase db reset --local                 # applies migrations in order
docker exec -i supabase_db_Wasted psql -U postgres -d postgres -v ON_ERROR_STOP=1 -f - < supabase/verify.sql
# then each suite:
for f in remote_schema_test remote_rls_test remote_rpc_test \
         remote_device_settings_targets_test remote_concurrency_test \
         remote_maintenance_test remote_verify_drift_test; do
  docker exec -i supabase_db_Wasted psql -U postgres -d postgres -v ON_ERROR_STOP=1 \
    -f - < supabase/tests/$f.sql
done
```

A passing suite ends with `ROLLBACK`. Notes:

- The SQL suites cannot run on a seeded database — `seed.sql` already inserts the same fixture
  UUIDs. Run `db reset` with seeds disabled (`config.toml` → `[db.seed] sql_paths = []`) or run the
  suites on a database reset without seeds. Restore the seed path afterwards.
- `psql` is not installed on the macOS host; use the container
  (`docker exec -i supabase_db_<project> psql -U postgres -d postgres …`) or `supabase db query --local`.
- `verify.sql` raises if any check fails, so exit code 0 means every row passed.

**Run-once semantics:** reapplying `setup.sql` to an existing project fails partway
(e.g. `relation "command_events_id_seq" already exists`). This is intended: it is a fresh-project
script. Existing projects use a generated update bundle instead.

---

## 7. Self-hosting Supabase (optional bonus)

> This section is **optional**. The supported path is a free Supabase Cloud project — see
> [What ships to users](#1-what-ships-to-users) and
> [Manual Auth steps](#3-what-the-setup-file-cannot-do-manual-auth-steps). Read this only if you
> already run, or want to run, your own Supabase instance. Nothing in the app, wizard, or release
> process requires it.

Wasted works with **Supabase Cloud** or a **self-hosted Supabase**. The setup file is plain SQL
against a Supabase-shaped database, so the same file applies to both — provided the Auth service is
configured the same way.

### 7.1 Requirements (official)

| Resource | Minimum | Recommended |
| --- | --- | --- |
| RAM | 4 GB | 8 GB+ |
| CPU | 2 cores | 4 cores+ |
| Disk | 40 GB SSD | 80 GB+ SSD |

The stack is a single Postgres plus an API gateway (Envoy by default, Kong optional), Auth
(GoTrue), PostgREST, Realtime, Storage, imgproxy, postgres-meta, Studio, Edge Runtime, and
Supavisor. You need Docker Engine + Compose on a Linux host, plus Git. Logs/Analytics (Logflare +
Vector) are **not** in the default configuration; they are an optional override and increase
resource use.

### 7.2 Install (official paths)

Quick start, Linux only — installs prerequisites, sparse-clones `docker/`, generates secrets and an
asymmetric JWT key pair, prompts for URLs, and records `.supabase-version`:

```bash
curl -fsSL https://supabase.link/setup.sh | sh
cd supabase-project && sh run.sh start
sh run.sh secrets           # print generated credentials
```

Manual install — pin a release tag so `update.sh` can merge later:

```bash
git clone --depth 1 --branch self-hosted/v0.8.4 https://github.com/supabase/supabase
mkdir supabase-project
cp -rf supabase/docker/. supabase-project
cd supabase-project && cp .env.example .env
printf 'ref=self-hosted/v0.8.4\n' > .supabase-version
docker compose pull
```

Never start on the example secrets. Run `sh utils/generate-keys.sh` then
`sh utils/add-new-auth-keys.sh`, and review `.env` before starting.

### 7.3 Configuration that matters for Wasted

| Setting | Value |
| --- | --- |
| `SUPABASE_PUBLIC_URL` | Base URL used as the app's **Project URL**, e.g. `https://supabase.example.com` |
| `API_EXTERNAL_URL` | Where Auth builds callbacks, e.g. `https://supabase.example.com/auth/v1` |
| `SITE_URL` | Default redirect URL for Auth |
| `SUPABASE_PUBLISHABLE_KEY` | The app's **publishable key** (never `SUPABASE_SECRET_KEY`) |
| HTTPS | Put Caddy/Nginx in front of the gateway; production needs TLS |

Then the same [manual Auth steps](#3-what-the-setup-file-cannot-do-manual-auth-steps) apply: anonymous sign-ins, Email provider with confirm-email off, the
`hook_single_owner_signup` hook, and the rate limits. Also apply `setup.sql` **and** run
`verify.sql`.

Two self-hosting-specific notes:

- Storage uses a bind mount by default; on macOS that breaks (xattr/permission issues) — use a
  named Docker volume instead. Wasted does not use Storage today, but the stack still starts it.
- Studio is protected by HTTP basic auth (`DASHBOARD_USERNAME` / `DASHBOARD_PASSWORD`); the password
  must contain at least one letter.

### 7.4 What is not available / not guaranteed

- There is no Supabase-hosted management API, so CLI `--linked` commands do not target a
  self-hosted instance; use `--db-url` with the Postgres connection string instead.
- `supabase db advisors` is a hosted Management API feature; on self-hosted, audit by hand or use
  `db lint --local`.
- Free-plan guardrails (capacity numbers in `capacity.md`) describe the hosted free plan. A
  self-hosted instance is limited by your server instead.
- Auth behaviour must be verified per instance; the hook and anonymous-signin toggles are
  user-configured, and `verify.sql` covers the database, not the Auth service.

### 7.5 Updates (official mechanism)

Self-hosted releases are published roughly monthly as `self-hosted/v*` tags. Versions inside a
release are tested together; mixing individual image tags is unsupported.

```bash
sh update.sh --dry-run     # preview; needs .supabase-version
sh update.sh               # three-way merge over your files
sh run.sh pull
sh run.sh recreate
```

Behaviour to plan for:

- `update.sh` preserves `.env`, your data, and gitignored paths, and backs configuration up to
  `backups/pre-update-*.tgz`. It does **not** back up Postgres or Storage — back those up first.
- Breaking changes (e.g. a Postgres major upgrade) are read from the release manifest and must be
  completed before files are applied.
- Conflicts are written as merge markers and the script exits `2` without advancing
  `.supabase-version`; resolve, then re-run.
- Postgres 17 is the default from v0.6.0. Moving an existing Postgres 15 deployment requires the
  dedicated `pg_upgrade` flow, with 2× database size + 5 GB free disk. Never `docker compose down -v`:
  that destroys the `db-config` volume holding the pgsodium root key.

### 7.6 How Wasted's own updates interact

Two independent upgrade tracks:

| Track | Applies to | Mechanism |
| --- | --- | --- |
| Wasted schema | Cloud **and** self-hosted | `update-from-<old>-to-<new>.sql` in SQL Editor |
| Supabase stack | Self-hosted only | `update.sh` + `run.sh pull/recreate` |

Upgrade the stack first, then apply Wasted's SQL bundle. If a release adds no migration, the app
reports that no database update is needed. Never re-run `setup.sql` on an existing project.

---

## 8. Pre-release checklist

Run in this order; all must pass.

```bash
# 1. Generator consistency
node supabase/build-setup-sql.mjs
shasum -a 256 supabase/setup.sql supabase/releases/3.0.0/setup.sql app/src/main/assets/setup.sql
#    → all three hashes identical

# 2. Fresh-install proof (Docker)
supabase start
docker exec -i supabase_db_Wasted psql -U postgres -d postgres -v ON_ERROR_STOP=1 -f - < supabase/releases/3.0.0/setup.sql
docker exec -i supabase_db_Wasted psql -U postgres -d postgres -v ON_ERROR_STOP=1 -f - < supabase/verify.sql

# 3. Suites (see "Rebuilding the baseline from the live project")

# 4. Equivalence proof vs live (see "Proving the shipped file equals the live project")

# 5. APK contains the exact asset
unzip -p app/build/outputs/apk/debug/Wasted-v3.0.0-debug.apk assets/setup.sql | shasum -a 256

# 6. Build and tests
./gradlew :app:testDebugUnitTest :app:assembleDebug
cd web && pnpm test && pnpm build && pnpm build:android-template
```

---

## 9. Known gaps and gotchas

- **`db push` fails after the baseline collapse on any project that still has the old history.**
  The live dev project lists the 34 pre-baseline versions, so `supabase db push --linked` stops
  with *"Remote migration versions not found in local migrations directory"*. That project must
  either be reset (planned before release) or have its history repaired. Never run both
  `setup.sql` **and** `db push` against the same project — pick one path.
- **Local advisors are not parity evidence** — see
  [Equivalence proof](#5-proving-the-shipped-file-equals-the-live-project).
- **SQL suites vs `seed.sql`** collide on fixture UUIDs — see
  [Rebuilding the baseline](#6-rebuilding-the-baseline-from-the-live-project).
- **`/tmp` is unusable for `db dump`/`db pull` on macOS** — see
  [Docker](#4-docker-what-actually-needs-it).
- **`pg_dump` is not a complete setup file** — see
  [Rebuilding the baseline](#6-rebuilding-the-baseline-from-the-live-project).
- **`public` schema owner differs on hosted vs local** — expected, see
  [Equivalence proof](#5-proving-the-shipped-file-equals-the-live-project).
- **Auth settings are never covered by SQL or `verify.sql`** — verify the
  [manual Auth steps](#3-what-the-setup-file-cannot-do-manual-auth-steps) on every new project.
- **Reusing an existing project needs extra steps** — dropping `public`/`private` is not enough;
  the old owner identity in `auth.users` blocks all later signups. See
  [Cleaning an existing project](#31-cleaning-an-existing-project-reuse-in-place).

---

## 10. Quick reference

```bash
# Link / credentials
supabase link --project-ref <ref>
set -a; source .env; set +a

# Schema work that needs Docker
supabase db dump --linked --keep-comments -f evidence/audit/live-schema.sql
supabase db pull --linked
supabase db diff --linked
supabase db reset --local

# No Docker needed
supabase db push --linked
supabase db query --linked "select count(*) from public.devices;"
supabase migration list --linked
supabase migration repair --status applied <version> --linked
supabase gen types --linked

# Local stack
supabase start
supabase stop

# Generate release SQL + assets
node supabase/build-setup-sql.mjs
./gradlew :app:assembleDebug
```

**History note:** the pre-release migration chain (44 files) was collapsed into the single
baseline `20261111200000_remote_baseline.sql` (schema version 1) before the public release. The
old files are kept locally in `supabase/archive/migrations/` (gitignored) for reference only. A
fresh project starts from the baseline; there is no upgrade path from a pre-release chain.

---

## References

- Self-hosting with Docker: <https://supabase.com/docs/guides/self-hosting/docker>
- Updating a self-hosted deployment: <https://supabase.com/docs/guides/self-hosting/updating>
- Upgrade to Postgres 17: <https://supabase.com/docs/guides/self-hosting/postgres-upgrade-17>
- Self-hosted API keys / asymmetric auth: <https://supabase.com/docs/guides/self-hosting/self-hosted-auth-keys>
- Database advisors and lints: <https://supabase.com/docs/guides/database/database-advisors>
