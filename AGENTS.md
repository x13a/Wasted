# Developer & Agent Guide: Fadocx Optimization

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
- After ANY correction from the user: update `tasks/lessons.md` with the pattern
-Write rules for yourself that prevent the same mistake
- Ruthlessly iterate on these lessons until mistake rate drops
- Review lessons at session start for relevant project

### 4. Verification Before Done
- Never mark a task complete without proving it works
- Diff behavior between main and your changes when relevant
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
1. **Plan First**: Write plan to `tasks/todo.md` with checkable items
2. **Verify Plan**: Check in before starting implementation
3. **Track Progress**: Mark items complete as you go
4. **Explain Changes**: High-level summary at each step
5. **Document Results**: Add review section to `tasks/todo.md`
6. **Capture Lessons**: Update `tasks/lessons.md` after corrections

## Core Principles
- **Simplicity First**: Make every change as simple as possible. Impact minimal code.
- **No Laziness**: Find root causes. No temporary fixes. Senior developer standards.
- **Minimal Impact**: Changes should only touch what's necessary. Avoid introducing bugs.

---

## Wasted App 2026 Revival - Mandatory Rules

### Backward Compatibility Guarantee
- **NEVER remove features**. Deprecated ≠ broken.
- Device Admin API stays. If fixing Android 13+ issues, ADD permissions, don't replace.
- minSdk 23 (Android 6) support MUST be maintained.
- Old devices (Android 6-12): Device Admin only, no P2P.
- New devices (Android 13+): Device Admin + P2P both available.

### Execution Discipline
- **Phase order is SACRED**. Do not parallelize or skip.
  - Phase 1: API update + permissions (build foundation first)
  - Phase 2: P2P networking (core system)
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

### Library Selection Principle
- **Support maximum span**: Pick libs that work Android 6-16.
- Prefer: OkHttp (API 21+), Gson (pure Java), Room (AndroidX), Tink (API 19+).
- Avoid: Jetpack Compose (API 21+), experimental/alpha libs.
- If adding a lib, verify minSdk support in official docs.

### Testing Before Commit
- Phase 1: Build + ForegroundService notification check.
- Phase 2: Two real devices discover + pairing works.
- Phase 3: Settings sync in < 500ms.
- Phase 4: Full matrix tested (Android 6, 13, 15 minimum).
- No feature commit without passing test on target API.

### Documentation Requirements
- Keep ROADMAP_2026_REVIVAL.md updated with actual progress.
- If changing timeline, update roadmap with reason.
- Lessons learned → /memories/session/lessons.md (for next session).
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
