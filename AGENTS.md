# Developer & Agent Guide: Wasted (Modded)

## Workflow Orchestration

### 1. Plan Node Default
- Enter plan mode for ANY non-trivial task (3+ steps or architectural decisions)
- If something goes sideways, STOP and re-plan immediately - don't keep pushing
- Use plan mode for verification steps, not just building
- Write detailed specs upfront to reduce ambiguity

### 2. Subagent Strategy
- Use subagents liberally to keep main context window clean
- Offload research, exploration, and parallel analysis to subagents
- For complex problems, throw more compute at it via subagents
- One tack per subagent for focused execution

### 3. Self-Improvement Loop
- After ANY correction from the user: write a rule that prevents the same mistake
- Ruthlessly iterate on those rules until the mistake rate drops
- Review them at session start
- Before freezing an architecture plan, reconcile it against the latest client decision; remove rejected services and speculative infrastructure, and turn uncertain delivery claims into explicit real-device test gates.
- Once `plan.md` exists, update it incrementally and preserve prior decisions/evidence; do not delete and regenerate it unless the user explicitly requests a full rewrite.
- For Supabase work, inspect and validate the real linked project through Supabase MCP before and after every change; never assume schema, configuration, capability, migration state, advisor status, or quota headroom.

### 4. Verification Before Done
- Never mark a task complete without proving it works
- Diff behavior between `mod` and your changes when relevant
- Ask yourself: "Would a staff engineer approve this?"
- Run tests, check logs, demonstrate correctness
  
### 5. Demand Elegance (Balanced)
- For non-trivial changes: pause and ask "is there a more elegant way?"
- If a fix feels hacky: "Knowing everything I know now, implement the elegant solution"
- Skip this for simple, obvious fixes - don't over-engineer
- Challenge your own work before presenting it

#### 6. Autonomous Bug Fixing
- When given a bug report: just fix it. Don't ask for hand-holding
- Point at logs, errors, failing tests - then resolve them
- Zero context switching required from the user
- Go fix failing CI tests without being told how

## Task Management
1. **Plan First**: Build a checkable todo list before starting
2. **Verify Plan**: Check in before starting implementation
3. **Track Progress**: Mark items complete as you go
4. **Explain Changes**: High-level summary at each step
5. **Document Results**: Summarize the outcome when the work is done

## Core Principles
- **Simplicity First**: Make every change as simple as possible. Impact minimal code.
- **No Laziness**: Find root causes. No temporary fixes. Senior developer standards.
- **Minimal Impact**: Changes should only touch what's necessary. Avoid introducing bugs.

## Documentation Map (read before backend/release work)

`docs/` is the **canonical, self-sufficient** documentation set and must stay usable if `plan.md`
and `HANDOFF.md` (temporary development artifacts) are deleted.

**Deployment model:** the product is built for **free Supabase Cloud** projects. Every user creates
their own free Supabase project and hosts their own dashboard. Self-hosted Supabase is an optional
bonus, never required, never assumed. Do not introduce anything that needs Docker on the user's
side.

### Start here

- `docs/README.md` — index of all documentation.
- `docs/backend/quickstart.md` — six-step Supabase Cloud setup (start here for onboarding).
- `docs/architecture.md` — system architecture, components, safety invariants.
- `docs/backend/self-hosting-and-setup.md` — **authoritative** for packaging, fresh-project setup,
  the manual Auth steps SQL cannot do, the Docker command matrix, how the shipped `setup.sql` is
  proven equal to the live project, the baseline-rebuild rules, the pre-release checklist, and the
  optional self-hosting/upgrade track.

### Read when touching…

| Area | Read first |
| --- | --- |
| `supabase/migrations/`, `releases.json`, generated release SQL, `build-setup-sql.mjs` | `docs/backend/migrations-and-releases.md` + `self-hosting-and-setup.md` |
| Schema, RLS, RPCs, grants, Realtime, cron | `docs/backend/database.md`, then `security.md` |
| Device wire contract, commands, retries | `docs/backend/remote-protocol.md`, `decisions.md` |
| Setup wizard, Auth toggles, user onboarding | `docs/backend/quickstart.md`, `setup.md` |
| Dashboard generation/hosting | `docs/backend/hosting.md` |
| Release cut, version bump, upgrade bundles | `docs/backend/release-checklist.md`, `updates.md` |
| Free-plan limits, quotas, load model | `docs/backend/capacity.md` |
| Failures/fixes | `docs/backend/troubleshooting.md`, `verification.md` |
| **Any security question or audit** | `docs/backend/security.md` + the `security-audit` skill |

### Other references

- `supabase/README.md` — backend operations quick reference.
- `supabase/releases/<appVersion>/` — generated SQL per release (`setup.sql`, update bundles,
  `verify.sql`, `manifest.json`, `README.md`); never hand-edit.
- `supabase/migrations/` — flat, immutable, CLI-compatible history; never edit applied files.
- `supabase/archive/migrations/` — gitignored pre-release history; reference only, not active.
- `HANDOFF.md` / `plan.md` — temporary artifacts; docs supersede them.

Rules:
- Any release that changes SQL must update `supabase/releases.json`; Gradle regenerates the
  release folders and APK assets automatically.
- Keep docs in sync when workflow/architecture changes; link instead of duplicating, and do not
  make docs depend on `plan.md` or `HANDOFF.md`.
- Never put secrets, project refs, or real account/device data in docs.

---

## Security: zero-vulnerability standard

The app and backend must ship with **no known vulnerabilities**. This is not a one-time gate.

- **Always use the `security-audit` skill** for any security review, pre-release audit, RLS/RPC
  change, auth/session change, or when investigating a suspected vulnerability. Do not do an
  ad-hoc security review instead of it.
- Re-read `docs/backend/security.md` before changing anything that touches RLS policies, grants,
  `SECURITY DEFINER` functions, Auth settings, enrollment tokens, or the wipe boundary.
- Every `SECURITY DEFINER` function MUST authorize internally (`require_owner()`,
  `require_active_device()`, or an explicit `auth.uid()`/`is_anonymous_user()` check) and set
  `search_path = ''`. Never add `SECURITY DEFINER` to work around a permission error.
- Every new table in `public` MUST have RLS enabled **and forced**, a SELECT-only policy scoped to
  the owning identity, and `authenticated` SELECT-only table grants. Direct writes are denied;
  writes go through owner-only RPCs.
- The default-privilege `REVOKE`s in the baseline MUST stay. Without them, PostgreSQL's default
  `EXECUTE`-to-`PUBLIC` re-exposes every future function.
- Before a release, run the `security-audit` skill and Supabase advisors (`get_advisors` security
  + performance) and fix or explicitly justify every finding in writing. Triage that depends on
  design intent (for example anonymous device identities) must be recorded, not silently ignored.
- Verify with real attack tests, not inspection: cross-tenant read/write, anonymous-device blast
  radius, and revoked-device access. A passing RLS test suite ends with `ROLLBACK`.
- Never weaken a control to make a test pass, and never test destructive paths on a real device.

---

## Wasted App 2026 Revival - Mandatory Rules

### Backward Compatibility Guarantee
- **NEVER remove features**. Deprecated ≠ broken.
- Device Admin API stays. If fixing Android 13+ issues, ADD permissions, don't replace.
- minSdk 24 (Android 7) support MUST be maintained.
- Old devices (Android 7-12): Device Admin only, no P2P.
- New devices (Android 13+): Device Admin + P2P both available.

### Execution Discipline
- **Phase order is SACRED**. Do not parallelize or skip.
  - Phase 1: API update + permissions (build foundation first)
  - Phase 2: Supabase remote networking and synchronization; preserve and regression-test existing P2P
  - Phase 3: UI + control (user-facing)
  - Phase 4: Testing (integration + compat)
- Each phase must PASS testing before next begins.
- If Phase N breaks Phase N-1, STOP and re-plan.

### Code Quality Standards
- All networking code: Use TLS 1.2+ (OkHttp 4.11+)
- All device communication: Encrypt with Tink (Google recommended)
- All device data: EncryptedSharedPreferences + Room with encryption
- No HTTP for control commands (TLS only).
- No plain text device secrets.

### Supabase Public-Release and Free-Tier Rules
- Ship one public APK; Remote Management defaults OFF/unconfigured and makes zero Supabase requests until explicitly configured, enrolled, and enabled.
- Never commit or embed the client Supabase URL/key or any privileged secret. Store runtime URL/publishable-key inputs encrypted; a publishable key is not a secret, so RLS/RPC authorization must remain secure if it becomes known.
- Owner email/password Create Account and Sign In must match the archived client specification at `archive/wasted update.txt`; device anonymous Auth is internal only and supplies the required unique per-device identity. Limit anonymous signup to 10/hour/IP and owner signup/sign-in to 10/5 minutes/IP; enrollment tokens, forced RLS, denied direct writes, cleanup, and cross-account/device tests are mandatory.
- Free-plan budget: 500 MB database, 5 GB egress, 50,000 MAU, 2 million Realtime messages/month, 200 peak connections. Review at 1 million messages, 160 connections, 350 MB, or 3.5 GB egress.
- Optimize with one status-row upsert/device, scoped Realtime subscriptions, indexed RLS predicates, coalesced refetches, and no per-second timer writes. Check usage during soak and monthly. The implementation must remain within every Free-plan quota; do not assume a paid upgrade.

### Library Selection Principle
- **Support maximum span**: Pick libs that work Android 7-16.
- Prefer: OkHttp (API 21+), Gson (pure Java), Room (AndroidX), Tink (API 19+).
- Avoid: Jetpack Compose (API 21+), experimental/alpha libs.
- If adding a lib, verify minSdk support in official docs.

### Testing Before Commit
- Phase 1: Build + ForegroundService notification check.
- Phase 2: Supabase RLS/RPC tests pass; two real devices enroll, reconcile, receive commands, and existing P2P pairing still works.
- Phase 3: Settings sync in < 500ms.
- Phase 4: Full matrix tested (Android 7, 13, 15 minimum).
- No feature commit without passing test on target API.

### Documentation Requirements
- Keep README.md in sync with user-facing changes.
- No silent pivots; document deviations.

### Deprecation Handling (Critical)
- Device Admin deprecated in Android 9 (2018) → Still use it, it works.
- Respect the original code intent: lock device, wipe data, USB detection, inactivity timeout.
- Treat P2P as ADDITION, not replacement.
- When Device Admin is replaced (future), it's done AFTER P2P is stable.

### Logging Standards (Extensive Tracking)

**Log Levels:**
- `VERBOSE`: Entry/exit of methods, variable state
- `DEBUG`: Flow checkpoints, decision branches, API calls
- `INFO`: User actions (lock triggered, wipe started, device paired)
- `WARN`: Recoverable errors (retry pending, fallback activated)
- `ERROR`: Failures requiring intervention (TLS failure, peer timeout)

**Key Flows to Log:**

1. **Device Admin Flow:**
   ```
   DEBUG: "lockNow() called"
   DEBUG: "DevicePolicyManager.lockNow() invoked"
   INFO: "Device lock triggered"
   ERROR: "Device Admin not active" (if applicable)
   ```

2. **P2P Discovery Flow:**
   ```
   DEBUG: "mDNS discovery started"
   DEBUG: "Searching for _wasted._tcp.local"
   DEBUG: "Peer found: [device-name]"
   INFO: "Pairing dialog shown"
   DEBUG: "PIN validation: [status]"
   INFO: "Pairing successful"
   ```

3. **Settings Sync Flow:**
   ```
   DEBUG: "Settings change detected: [key]=[value]"
   DEBUG: "Broadcasting to [N] peers via TLS"
   DEBUG: "ACK received from [peer-name]"
   INFO: "Settings synced (latency: [ms])"
   ERROR: "Sync failed for peer [name]"
   ```

4. **Reset Flow:**
   ```
   DEBUG: "Reset button clicked (local/remote)"
   INFO: "Reset confirmation shown"
   DEBUG: "User confirmed reset"
   INFO: "wipeData() called" OR "Reset command sent to [peer]"
   VERBOSE: "wipeData flags: [flags]"
   ```

**Log Tags:**
- Use TAG constants: `DeviceAdminManager`, `P2PNetwork`, `SettingsSync`, `PairingManager`, `MessageQueue`, `SecurityManager`
- One tag per class for easy filtering: `adb logcat DeviceAdminManager:* | grep -v VERBOSE`

**Filtering on Device:**
```bash
# Watch all Wasted logs
adb logcat | grep "Wasted"

# Watch P2P discovery only
adb logcat | grep "P2PNetwork"

# Watch errors
adb logcat *:E | grep -i wasted
```

**Testing Verification:**
- Screenshot or save logcat when verifying flows
- Document expected vs actual log sequences in test reports
- Use logs to prove timing (latency <500ms for settings sync)
- Archive logs per device for cross-device comparison
