# Security model

## Threat model

| Threat | Mitigation |
| --- | --- |
| Someone learns the dashboard URL/publishable key | Expected: key is public. RLS limits data; Auth rate limits mitigate signup abuse. CAPTCHA optional |
| Stolen owner password | Owner controls Supabase Auth; password never stored by the app/dashboard. Rotate password in Supabase |
| Stolen/revoked device | `revoke_device` rejects pending commands, terminalizes `executing` with an unknown-outcome result, and blocks reconciliation |
| Replay/duplicate command | Per-submission idempotency keys bound to action type/scope; server compare-and-set state transitions; local journal |
| Remote wipe bypass | Debug Mode re-read by `ResetExecutor`; remote Force Reset blocked while ON; only reset boundary |
| Someone learns an enrollment token | Token is single-use, expires after the configured enrollment lifetime (default 10 minutes), and is hashed at rest; there is no manual token-revocation endpoint, so treat it as a secret and let it expire if unused |
| Direct table tampering | No write policies/grants; writes only through SECURITY DEFINER RPCs |
| Private device notes | Stored in owner-only `device_notes`; devices have no SELECT policy and never receive note text |
| Secret key leakage | Secret/service-role keys are rejected by validation and never shipped; docs forbid them |
| Hosted dashboard contains secrets | False: the generated package contains only URL + publishable key |
| Clickjacking of destructive confirmations | Use the full app package (`index.html` + `manifest.webmanifest` + `sw.js` + `icons/` + `_headers`) on a header-capable host, or configure `frame-ancestors 'none'`/`X-Frame-Options: DENY`; the page also ships a frame-busting fallback |

## Single-owner signup

- Supabase password signup is direct. Keep the Email provider enabled for email/password sign-in,
  but turn Confirm email OFF. No confirmation email is sent and no email delivery service or SMTP
  path is involved.
- The first non-anonymous Auth user becomes the owner. The Before User Created hook
  (`public.hook_single_owner_signup`) permits anonymous device identities and rejects every later
  email/password account with HTTP 403. This is intentional: the dashboard owns devices and
  destructive commands, so knowing the public URL must never allow a second owner.
- The hook serializes concurrent signup attempts with an advisory lock; `initialize_owner_account`
  also retains its single-owner guard as defense in depth.
- Create the owner immediately after deployment and keep the dashboard URL private until that is
  done; the first account can claim ownership.
- Email ownership is not verified by design. Password/account recovery is performed by the project
  owner through the Supabase dashboard, not through email.

## Direct Boot and a rebooted/stolen device

The remote identity, Supabase session, and app settings currently live in
credential-encrypted storage. Android makes that storage unavailable until the user performs the
first unlock after a reboot. The boot receiver and periodic worker still run, but the remote service
deliberately waits for `UserManager.isUserUnlocked`; this is why a phone rebooted to the lockscreen
does not sync or accept commands until it is unlocked.

Android's [Direct Boot documentation](https://developer.android.com/privacy-and-security/direct-boot)
defines the only supported way to change this: use device-encrypted storage
(`createDeviceProtectedStorageContext()`) and mark the components direct-boot aware. That would let
the app open its remote session and execute safe work before first unlock, but it also means the
remote identity/policy material is available after verified boot without the user's credential.
It is a security tradeoff, not a small toggle. We intentionally keep credential-encrypted storage
as the default. Do not move the remote session to device-encrypted storage without an explicit
product/security decision and a separate threat-model review.

## Key boundaries

| Key | Where it lives | Rules |
| --- | --- | --- |
| Project URL + **publishable** key | App config, generated dashboard | Public by design; safe to ship. RLS is the security boundary |
| **Secret/service-role** key | Never in app/dashboard/repo | Full data access, bypasses RLS; used only in trusted server-side tooling if ever needed |
| Supabase account password | User's account | Never stored by the app or dashboard |
| Database password | Supabase dashboard only | Never committed or embedded |
| Enrollment token | Generated in dashboard, pasted in app | Single-use, account-configured lifetime (default 10 minutes), hashed at rest |
| Android signing material | Local keystore (gitignored) | Never committed |

Validation rejects `sb_secret_...` and legacy `service_role` JWTs. The dashboard contains only the
URL + publishable key.

## Database access

- RLS is enabled **and forced** on every remote table.
- `anon` has no access; `authenticated` has select-only grants.
- All writes go through SECURITY DEFINER RPCs that check owner/active-device identity.
- An anonymous device cannot see owner tables or other devices; it can only reconcile/transition
  its own commands.
- Owners are normal non-anonymous users; devices are anonymous users bound by enrollment.

## Destructive boundary

- `ResetExecutor` is the **only** path to `DeviceAdminManager.wipeData()`.
- Debug Mode defaults ON; `ResetExecutor` re-reads the encrypted preference at entry and immediately
  before the wipe call. Missing/corrupt/read error is treated as ON.
- Remote Force Reset is blocked while Debug Mode is ON (`debug_blocked`), with a local notification.
- Force Reset executes at most once; interrupted `executing` commands are failed, never replayed.
- Per-device Debug Mode OFF requires owner action and supersedes pending Force Resets.

## Anonymous device identities

- Enabling anonymous sign-ins does **not** make the owner account anonymous. Owner accounts remain
  normal email/password accounts; the provider is used only to create per-device identities.
- An anonymous identity alone grants no app data. RLS/RPCs deny access until a device is bound to an
  owner account through a single-use enrollment token.
- The enrollment token is generated only by the signed-in owner, stored hashed, expires after 10
  minutes, and is consumed by one device.
- Abuse is limited by Auth rate limits (use 10 anonymous/hour/IP), daily cleanup of unbound
  identities older than 24 hours, and cleanup of identities for devices revoked for more than 30
  days. CAPTCHA is an optional additional control.
- The publishable key is public by design; it identifies the project but does not authorize data
  access.

## Auth input handling

- Emails are trimmed and lowercased before Supabase Auth; the dashboard uses `type=email`,
  `maxlength=254`, and explicit inline validation for empty or malformed values. Supabase validates
  again server-side.
- Passwords are never trimmed or altered. Create Account and Change Password both require a
  confirmed password and at least 6 characters, matching Supabase's server default
  (`GOTRUE_PASSWORD_MIN_LENGTH`), so the client and server floors agree. Sign In accepts existing
  passwords, including shorter ones if the project permits them.
- Leaked-password protection (HaveIBeenPwned) is **Pro plan and above**, so it cannot be required of
  the free-plan projects this product targets. Leave it off; the password floor and Supabase rate
  limits are the available controls.
- Account names are display names: 1–32 characters, spaces and normal punctuation allowed,
  control characters rejected. They are stored in `public.accounts`; the signup copy is also stored
  in auth metadata as a bootstrap fallback. Device names are 1–80 characters.
- Dynamic values are rendered with `textContent`, not HTML interpolation; RPC parameters are
  parameterized and RLS/RPC ownership checks remain the authorization boundary.

## Single-owner guard

- Only the first authenticated owner can initialize a dashboard account. Additional Auth users can
  exist but cannot access an account, so public email signups cannot create a usable dashboard.
- Create the owner account before sharing the dashboard URL: the first authenticated user wins the
  bootstrap. Disabling email signups after bootstrap or host access control are optional additional
  controls; the single-owner hook is the signup restriction.

## P2P peer authentication

- Each device has a persisted self-signed TLS certificate. Pairing pins the peer leaf certificate
  SHA-256; paired connections require the same certificate and the server requires a client
  certificate.
- The authenticated handshake binds the certificate to one device ID; every message must match that
  ID and the local recipient before dispatch. A changed certificate is rejected and requires
  re-pairing.
- The P2P Room database is SQLCipher-encrypted with a separate Tink-wrapped passphrase. Legacy
  plaintext peer rows are imported as unpaired rows so an old APK-signature hash is never trusted as
  a TLS pin.
- Pairing PINs use `SecureRandom`, are single-use, and have an attempt lockout.

## Device lifecycle

- **Revoke** immediately stops commands for that device and hides it from the dashboard's active
  list. It does not wipe the device. The device row is kept for audit history.
- Thirty days after revocation, the daily cleanup deletes only the anonymous Auth identity. The
  device row remains, with `auth_user_id` set to null; it is not silently deleted.
- A revoked installation cannot re-enroll with the same installation ID. On the device, use
  **Reset enrollment** (which rotates the installation ID), then claim a new token.

## Dashboard exposure

- The generated dashboard is public at its URL; anyone can attempt signups (rate-limited).
- RLS prevents access to another account's data.
- The publishable key is not a secret; do not treat the dashboard URL as one either.
- CAPTCHA for anonymous sign-ins is optional (Supabase recommends it); the client did not request
  it. Rate limits remain the abuse control.

## Secrets hygiene

- Never commit `.env`, keystores, database URLs/passwords, secret keys, signing material, tokens,
  dumps, or real account/device data.
- `.env.example` contains names only; `keystore.properties.example` has placeholders.
- APK scan: no real publishable/secret key, JWT material, project ref, or project URL is embedded
  (the app is unconfigured by default).

## Reporting a security issue

Do not open a public issue with exploit details. Contact the maintainer privately and include a
reproduction. Rotate any exposed key immediately in the Supabase dashboard.
