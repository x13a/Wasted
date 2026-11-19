# Documentation

Canonical, self-sufficient documentation for Wasted (Modded) and its Remote Management backend.
Everything needed to set up, operate, upgrade, verify, and reason about the system is here.

## Start here

1. **[Quick start (6 steps)](./backend/quickstart.md)** — the shortest path to a working setup.
2. [Architecture](./architecture.md) — system design, components, safety invariants.
3. [Backend overview](./backend/README.md) — identities, backend component map, documentation map.
4. Then the task-specific guide below.

## Backend

| Document | Use it for |
| --- | --- |
| [backend/quickstart.md](./backend/quickstart.md) | **Six-step setup** (start here) |
| [backend/README.md](./backend/README.md) | Backend overview and component map |
| [backend/setup.md](./backend/setup.md) | Fresh Supabase project setup (SQL + Auth + dashboard + enrollment) |
| [backend/self-hosting-and-setup.md](./backend/self-hosting-and-setup.md) | **Packaging, fresh-project setup, Docker requirements, equivalence proof; [optional self-hosting and upgrades](./backend/self-hosting-and-setup.md#7-self-hosting-supabase-optional-bonus)** |
| [backend/migrations-and-releases.md](./backend/migrations-and-releases.md) | **Authoritative** migration/release organization, naming, upgrade policy, sources |
| [backend/database.md](./backend/database.md) | Tables, constraints, RLS model, RPC catalog, Realtime, maintenance |
| [backend/remote-protocol.md](./backend/remote-protocol.md) | Reconcile contract, command types/states/TTLs, ordering, idempotency, retries |
| [backend/decisions.md](./backend/decisions.md) | ADR log: why the architecture and policies are what they are |
| [backend/updates.md](./backend/updates.md) | User upgrade guide and release-notes template |
| [backend/release-checklist.md](./backend/release-checklist.md) | Step-by-step developer release runbook |
| [backend/security.md](./backend/security.md) | Keys, RLS, RPCs, Debug Mode, wipe boundary, threat model |
| [backend/hosting.md](./backend/hosting.md) | Dashboard generation, Netlify project upload, CSP |
| [backend/capacity.md](./backend/capacity.md) | Free-plan limits, load model, review thresholds, monitoring |
| [backend/troubleshooting.md](./backend/troubleshooting.md) | Common setup/update failures and fixes |
| [backend/verification.md](./backend/verification.md) | Commands, expected results, test/gate matrix |

## Other references

- [`supabase/README.md`](../supabase/README.md) — backend operations quick reference.
- [`supabase/releases/`](../supabase/releases/) — generated SQL per app release (setup, updates,
  verify, manifests, release notes).
- [`.env.example`](../.env.example) — local dashboard and backend values; copy to the gitignored `.env`.
- [`README.md`](../README.md) — user-facing app/remote/hosting guide.

## Rules

- Applied migrations are immutable. Never edit or rename them.
- Every release that changes SQL must update `supabase/releases.json`.
- Generated SQL (`supabase/setup.sql`, `supabase/releases/**`, APK assets) is build output — never
  edit by hand; run `node supabase/build-setup-sql.mjs` or a Gradle build.
- Never put secrets, project refs, credentials, or real device/account data in docs.
