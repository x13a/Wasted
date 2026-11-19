# Updating an existing installation

No automatic update check. Release notes name the exact SQL file; the APK bundles it too.

## User runbook (self-hosted)

1. **Read the release notes.** Note the app version, schema version, and the exact
   `update-from-<old>-to-<new>.sql` file.
2. **Back up first.** Supabase Free has no PITR; export the database (Database → Backups, or
   `supabase db dump`) before applying anything.
3. **Update the app** (install the new APK). Older schemas remain compatible; the app does not need
   the database to be updated first.
4. **Apply the update bundle:** choose the file named `update-from-<your current version>-to-<current>.sql`
   (bundles are cumulative — one file covers the jump), then paste it in SQL Editor and Run once.
   The app's setup wizard can save that file for you; it never executes SQL.
5. **Verify:** run `verify.sql`; every row must say PASS.
6. **Dashboard:** regenerate and re-upload it only if the release notes say so.
7. **Older than the oldest listed bundle?** Step through releases or start from a fresh setup; an
   unknown/very old schema is not a supported upgrade path.

## Enrollment tokens and multiple devices

- Each device needs its own single-use token; tokens are independent and expire after the configured
  enrollment lifetime (default 10 minutes).
- Additional devices never re-run `setup.sql` or change Auth settings; they only need the same
  Project URL/key, the dashboard URL, and a new token.

## Release notes template

```md
## Wasted Remote <appVersion> (schema <schemaVersion>)

### Database update (required if you are on <previousVersion>)
- File: `update-from-<previousVersion>-to-<appVersion>.sql`
- SHA-256: `<hash from releases/<appVersion>/manifest.json>`
- Back up your Supabase project first. Apply once in SQL Editor, then run `verify.sql`.
- If you are on an older version, apply the cumulative bundle named for your installed version;
  you do not need to apply intermediate bundles.

### App
- APK: `Wasted-v<appVersion>.apk`
- Changes: …

### Dashboard
- Regenerate/re-upload only if this release changed the dashboard.

### Notes
- No downgrade path; restore a backup instead.
```

## Client-managed deployment (technical)

Use the Supabase CLI against the linked project:

```bash
supabase link --project-ref <ref>
supabase db push          # applies only pending migrations
supabase migration list --linked
supabase db diff --linked # must be empty
```

Docker is required for `db diff`; it is not required for `db push`.

## Backups and restore

- Supabase Free has **no PITR**; do not assume downloadable backups are available.
- Reliable export uses the Supabase CLI (`supabase db dump`, which runs `pg_dump` in Docker) for
  schema and data. A no-CLI/Docker export workflow must be tested before release; if the dashboard
  offers a backup download on your plan, verify it is restorable.
- Store the backup outside the project; verify it is readable before applying an update.
- Restore: create/choose the project, restore the schema/data dump, then run `verify.sql`.
- Never “roll back” by manually dropping objects; restore the backup or ship a forward fix.
- If you cannot produce and restore a backup, do not apply the update.

## Revoked devices during execution

If a device is revoked while a command is `executing`, the command becomes
`failed`/`device_revoked_during_execution`. The outcome is intentionally marked unknown; inspect the
device locally and never retry a wipe blindly.

## Key rotation

- **Dashboard project URL/key:** if the publishable key is rotated, regenerate the dashboard in the
  app (same project URL, new key) and re-upload it. Update the app's saved key as well.
- **Dashboard URL change:** update Remote Management in the app; enrollment/session are unaffected.
- **Supabase account password:** rotate in Supabase Auth; the app/dashboard never store it.
- **Secret/service-role key:** never in the app/dashboard; rotate it only for server-side tooling.
- **Enrollment token:** issue a new token and claim again on the device if needed. Unused tokens
  expire after the configured enrollment lifetime (default 10 minutes); treat them as secrets and do
  not share them.

## Rollback

- **App:** reinstall the previous APK (compatible with the additive schema).
- **Dashboard:** re-upload the previous ZIP.
- **Database:** restore the backup; do not attempt ad-hoc down-migrations.
- **Remote off:** disable Remote Management locally to stop sync without touching local behavior;
  the device stays enrolled, so re-enabling resumes sync. Use Reset enrollment to remove the local
  identity.

See [migrations-and-releases.md](./migrations-and-releases.md) for the full policy.
