# Release checklist

Copy this into the release PR/issue. Do not skip steps; each line has a command or expected result.

## Before building

- [ ] New migrations are immutable, timestamped, additive, and follow the naming convention.
- [ ] Applied migrations were not edited or renamed.
- [ ] `supabase/releases.json` has an entry for this app version with the cumulative migration list.
- [ ] Schema marker comment and `verify.sql` expected schema version match `releases.json`.
- [ ] `schemaVersion` incremented **only** if SQL changed; app-only release repeats the list.
- [ ] SQL suites pass on a fresh/disposable project (schema, RLS, RPC, concurrency, maintenance).
- [ ] `verify.sql` returns all PASS (FORCE RLS, grants/policies, RPC properties, Realtime, cron, ACLs).
- [ ] Both cleanup jobs are active; `wasted-cleanup-remote-history` completed without error.
- [ ] `./gradlew :app:verifyRemoteSchema` reports no changes (Docker + linked project).
- [ ] `supabase migration list --linked` has no local-only/remote-only versions.
- [ ] Fresh-project setup path still works (apply `setup.sql` to an empty project).
- [ ] Upgrade path works: previous release schema + new update bundle → `verify.sql` PASS.
- [ ] `node supabase/build-setup-sql.mjs` produces no unexpected diffs; checksums reviewed.
- [ ] Docs updated (migrations/releases, updates, setup, troubleshooting as needed).
- [ ] Security scans clean: tracked-secret grep, APK key/URL scan, no `.env`/keystore tracked.
- [ ] Version bumped in `app/build.gradle`; APK name is `Wasted-v<version>[-debug].apk`.

## Build

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:assembleSignedRelease
cd web && pnpm test && pnpm run build && pnpm run build:android-template
```

- [ ] Android tests green (currently 112).
- [ ] Web tests green (currently 50).
- [ ] `assembleSignedRelease` succeeds (fails closed without signing inputs); unsigned output is
  named `-unsigned` and is not published.
- [ ] `apksigner verify --print-certs` shows the expected certificate SHA-256.
- [ ] Verify Auth settings in the dashboard: anonymous on, Email provider on, Confirm email off,
  Before User Created hook enabled (Postgres / public / `hook_single_owner_signup`), rate limits as
  documented; record the observed values.
- [ ] Hosting headers observed: `frame-ancestors 'none'`/`X-Frame-Options: DENY`, nosniff,
  no-referrer.
- [ ] `supabase/releases/<version>/README.md` and `manifest.json` reviewed.
- [ ] Generated SQL/APK/template are committed and current (`git diff --exit-code` after generation).

## Publish

- [ ] Release notes include: app version, schema version, exact update bundle + SHA-256, backup
  warning, cumulative instructions, dashboard regeneration note.
- [ ] APK and SQL release folder attached/published.
- [ ] Supported upgrade window stated (any declared prior release via its matching cumulative bundle).
- [ ] Rollback notes included (app reinstall, dashboard re-upload, database restore).

## After

- [ ] Fresh install verified on an empty Supabase project.
- [ ] Upgrade verified from the previous release on a copy.
- [ ] Dashboard connects and enrollment works.
- [ ] Evidence archived (`evidence/`, gitignored).
