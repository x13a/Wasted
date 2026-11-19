# Capacity and Free-plan guidance

Per-user self-hosting keeps load isolated per Supabase project. This page records the sizing model
so it survives without the development plan.

## Free-plan limits (verified November 5, 2026)

- Database: **500 MB** per project
- Egress: **5 GB / month** (plus 5 GB cached egress; not used by this app)
- Monthly active users: **50,000**
- Realtime: **2,000,000 messages / month**, **200 peak connections**
- Storage: **1 GB**; Edge Functions: **500,000 invocations** (neither is used)
- Postgres: **60 concurrent connections** (`max_connections`), not a monthly count
- Free projects can be paused after one week of inactivity; there is no PITR or automatic backup.

## Connections are not a monthly quota

The `60` shown for database connections is the compute instance's **simultaneous open
connection limit**, not something that accumulates during the month. Seventeen connections now and
seventeen later are reused seats, not 34 used connections.

The Free project's live snapshot on November 5, 2026 had `max_connections = 60` and 25 entries in
`pg_stat_activity`: 5 Postgres background processes and 20 client backends owned by Supabase
services. **Zero** were external/user connections. The breakdown included PostgREST
(`authenticator`), Realtime, pg_cron, pg_net, the pooler, the metrics exporter, and the management
API. Those services keep idle connections open, which is why a quiet project still shows a
nonzero number.

This app does not open direct Postgres connections from the browser or phone. Both use the
Supabase Data API (PostgREST over HTTPS) and Realtime over WebSocket. A dashboard tab therefore
does **not** consume one of the 60 Postgres seats; PostgREST uses its own small pool, and Realtime
connections count against the separate **200 concurrent Realtime connections** allowance.

The monthly quotas that can actually grow are database size, egress, MAU, Realtime messages, and
Edge Function invocations. Connections matter only if concurrent clients exceed the limit and
cause "too many connections" failures. Any future custom backend that opens direct Postgres
connections should use the shared pooler, not one direct connection per request.

Official references: [Connection management](https://supabase.com/docs/guides/database/connection-management)
and [Pooling and limits](https://supabase.com/docs/guides/database/connecting-to-postgres/pooling-and-limits).

The project is on the Free plan. The Usage page is the source of truth for monthly egress and
Realtime messages; database size, MAU, and row growth can also be checked from SQL.

## Measured baseline

On November 5, 2026 this project measured:

- Database size: **13 MB** (about 2.6% of 500 MB)
- Auth MAU: **1** (the owner; anonymous device identities are created only when a device enrolls)
- Realtime: **8–10 subscriptions** from the open dashboard clients, **0** broadcast messages retained
- Storage and Edge Functions: **0**

The only unbounded table before this change was command history; the dashboard also refetched up to
200 command rows every 30 seconds. Both are now bounded.

## Load model

- **Device heartbeat:** one `device_reconcile` call per 60 seconds while enrolled, unlocked,
  enabled, and connected → **43,200 calls/month/device**. The status is one overwritten row per
  device, not history. Commands and settings changes also trigger an immediate reconcile through
  the device's Realtime channel.
- **Dashboard polling:** one full refresh per **60 seconds per visible tab**. Hidden tabs do not
  poll, and wake up with an immediate refresh when they become visible.
- **Dashboard Realtime:** subscribes to `devices`, `account_settings`, `device_status`, and
  `device_commands`. Each status heartbeat is delivered once per open dashboard, so live status is
  the main recurring Realtime message source; the 60-second visible-tab poll remains a fallback.
- **Command data:** the dashboard fetches **one latest command per device** through
  `list_latest_device_commands()`. It never downloads command history.
- **Retention:** terminal command/action history is deleted after **180 days**; stale enrollment
  tokens are deleted after **30 days**; unbound/revoked anonymous identities are deleted daily by
  the existing Auth cleanup. Realtime message partitions are managed by Supabase.

## Recommended operating envelope

For this single-owner dashboard, keep the working set within:

- **10 active devices**
- **2 simultaneously visible dashboard tabs**
- Normal command/settings usage (the tables are cleaned, not treated as an unbounded audit archive)

At this envelope, status delivery is about **864,000 messages/month**
(10 × 43,200 × 2), leaving headroom below the 2,000,000 limit for commands/settings. One active
dashboard supports roughly 20 devices at the same status volume. Database growth remains bounded by
the retention job, MAU is small, and Realtime peak connections are well below 200. Re-check the
Usage page before going beyond this; do not assume the Free plan is unlimited.

## Internal review thresholds

Treat these as early warnings, not hard limits:

- 1,000,000 Realtime messages/month
- 160 peak Realtime connections
- 350 MB database size
- 3.5 GB egress/month

At 70% / 85% / 95% of any quota, reduce open dashboards, device count, or retention, then re-check.
Never promise unlimited free operation.

## Retention policy

- `device_status`: current row only (overwritten), no history.
- `device_commands` / `command_events` / `account_actions`: deleted after **180 days**. The daily job
  removes old actions in batches of 5,000; foreign keys cascade to commands and events.
- `private.device_enrollments`: expired, consumed, or revoked rows older than **30 days** are
  deleted.
- Anonymous Auth identities: daily job removes unbound identities older than 24 hours and identities
  for devices revoked for more than 30 days.
- Revoked devices: kept while their command history is retained; device deletion cascades its
  history. Export anything that must be kept longer before changing the retention interval.

## Maintenance jobs

| Job | Schedule | Purpose |
| --- | --- | --- |
| `wasted-cleanup-anonymous-identities` | Daily 03:30 UTC | Delete stale anonymous Auth identities |
| `wasted-cleanup-remote-history` | Daily 03:45 UTC | Delete old command/action history and enrollment tokens |

Both jobs are installed by the migrations and checked by `verify.sql`.

## What to monitor

- Supabase Usage: database size, egress, MAU, and Realtime messages.
- Command/event/action row counts and growth.
- Cron job health and the latest cleanup run.
- Dashboard open-tab count and active device count.

Record the expected device/dashboard count before rollout and check weekly during a soak, monthly
afterward.
