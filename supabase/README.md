# Remote backend — Supabase

Reproducible infrastructure for the optional Remote Management feature. Git is the source of truth for structure and configuration; it never contains project secrets or client data.

## For app users (no CLI, no Docker)

1. Create a free Supabase project.
2. Open **SQL Editor**, paste `setup.sql`, and press **Run** once. It creates the schema, RLS
   policies, RPCs, grants, Realtime publication, and the cleanup jobs; it also ensures
   `pgcrypto`/`pg_cron` are available.
3. In **Authentication → Providers**: enable **Allow anonymous sign-ins** (device identities),
   keep the **Email provider ON**, and turn **Confirm email** OFF (signup is direct; no email is
   sent). Enable **Authentication → Hooks → Before User Created**: hook ON, Hook type Postgres,
   Postgres schema public, function `hook_single_owner_signup`, then Save. This blocks every later
   email account while allowing anonymous devices. In **Authentication → Rate Limits**, set
   anonymous sign-ins to 10/hour/IP and sign-in/sign-up to 10 per 5 minutes/IP (or verify your
   chosen values). CAPTCHA is optional.
4. Run `verify.sql`; every row must say PASS. The verifier checks FORCE RLS, exact policies/grants,
   RPC properties, Realtime scope, the cleanup job, and the schema marker.
5. The app setup wizard provides the same SQL and instructions.

Do not run `setup.sql` twice, and do not mix it with `supabase db push` on the same project.

## For developers (CLI/MCP)

- Apply migrations in order with `supabase link` + `supabase db push`, or MCP `apply_migration`
  for a hosted project.
- Recorded versions in `supabase_migrations.schema_migrations` must match the filenames in
  `migrations/`.
- Run all five suites in `tests/` inside rolled-back transactions on a fresh/disposable project.
  The RPC and concurrency suites require no existing owner account; on a live owner project use
  `verify.sql` and `remote_maintenance_test.sql` only.
- Regenerate `web/src/database.types.ts` when the schema changes.
- Review advisors with MCP `get_advisors`.
- The setup file is generated from a single baseline migration (`supabase/migrations/`), not from
  the CLI at install time. It already contains the schema, RLS, RPCs, grants, Realtime publication,
  and the two pg_cron maintenance jobs; only the Auth toggles in the setup wizard remain manual.
- Docker is only for local development (`supabase start` / `supabase db reset`). It is not
  required for hosted projects and is not a deployment path.

## Updating an existing project

- Never re-run `setup.sql`.
- `releases.json` maps each app release to its cumulative migration list and schema version;
  `node build-setup-sql.mjs` regenerates per-release folders under `releases/<appVersion>/`
  (`setup.sql`, update bundles, `verify.sql`, `manifest.json`, `README.md`). Never edit generated
  files. Full policy: [`docs/backend/migrations-and-releases.md`](../docs/backend/migrations-and-releases.md).
- Users apply the cumulative update bundle named in the release notes once, after a backup. Apply
  the bundle matching the installed version; no intermediate bundles are required. CLI users:
  `supabase db push` applies only new migrations.
- Migrations are immutable and additive; old app versions keep working with newer schemas.
- Supported upgrade window: any declared prior release via its matching cumulative bundle.
  Downgrades are not supported; restore a backup instead.
- Do not rewrite applied migrations. Supabase's `migration squash` omits data, cron jobs, storage
  buckets, and vault secrets, so it does not replace these generated artifacts.

## Hosted project manifest (non-secret)

Record and verify these values for every project:

| Setting | Expected |
| --- | --- |
| Auth providers | Email/password for owners; anonymous sign-ins for devices |
| Sign-up | Direct, no confirmation email; first email/password account becomes owner; hook blocks later accounts |
| Rate limits | Anonymous sign-ups 10/hour/IP; sign-in/sign-up 10 per 5 minutes/IP |
| Hooks | Before User Created: Postgres / public / `hook_single_owner_signup` |
| Exposed schemas | `public` only; `private` schema usage/grants revoked |
| Extensions | `pgcrypto` (digest, random bytes); `pg_cron` (anonymous-identity and remote-history cleanup) |
| Realtime | Publication `supabase_realtime` contains only `account_settings`, `devices`, `device_status`, `device_commands` |
| RLS | Enabled and forced on every remote table; writes only through SECURITY DEFINER RPCs |
| Advisors | `authenticated_security_definer_function_executable` (owner-only RPC surface by design), `rls_enabled_no_policy` on `private.device_enrollments` (deny-all by design), unused FK indexes on an empty schema |
| Free-plan retention | `wasted-cleanup-remote-history` daily: command/action history 180 days, enrollment tokens 30 days |

## Verification commands

- Database parity: `./gradlew :app:verifyRemoteSchema` (`supabase db diff --linked`) must print no
  schema changes; requires Docker and a linked project.
- Migration history: `supabase migration list --linked` must show no local-only or remote-only
  versions.
- Schema/RLS/RPC/concurrency: run each `tests/*.sql` against the linked database inside a
  transaction and `rollback`.
- Types: regenerate and confirm `web/src/database.types.ts` only changes with schema changes.
- Clean replay: `supabase db reset` (requires Docker) must succeed from migrations plus `seed.sql`; then `supabase db diff --linked` must be empty.
- Secret scan: `git grep -nE 'sb_secret_|service_role|postgres(ql)?://[^ ]+@|BEGIN [A-Z ]*PRIVATE KEY'` must return only validator/test constants.

## Recreate on another account

1. Create the target project and verify its identity through MCP.
2. Apply the tracked migrations in order (CLI/MCP), or run `setup.sql` once for a SQL-editor
   install.
3. Apply the Auth manifest above.
4. Run all five test suites, the advisors, and the clean replay/diff checks.
5. Generate the dashboard in the app for the target project (or build `web/dist/index.html` and inject the target values).
6. Create the first owner through the dashboard Create Account flow, then enroll a spare device with a fresh token.

## Rollback

- Disable Remote Management locally: stops Realtime, clears the active session, and leaves local/P2P behavior untouched.
- Replace the hosted dashboard artifact with the previous build; the RPC surface stays backward compatible.
- Downgrade the APK only to a version compatible with the additive schema.
- Corrections are forward-only compensating migrations. Never drop or rename columns or relax RLS; preserve command and audit evidence.

## Secrets and data

Never commit `.env`, database URLs or passwords, service-role or secret keys, JWT signing keys, signing material, enrollment tokens, real account/device data, or dumps. The publishable key is not a secret, but client-specific values are still kept out of tracked source.
