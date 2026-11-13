# Fresh Supabase setup

Use this for a new installation. For upgrades, see [updates.md](./updates.md).

## Prerequisites

- The Wasted app (v3.0.0+) installed.
- A free Supabase account.
- A Netlify account to host the static dashboard ZIP over HTTPS.

## Accounts vs devices

- **Owner account:** your email + password, used only on the dashboard. This is a normal Supabase
  Auth account; Create Account is explicit.
- **Device identity:** a separate, anonymous Supabase Auth user created by the app for each
  installation. It has no email or password. It is bound to the owner account only when the app
  claims an owner-created, single-use enrollment token.
- **Why:** the owner password never has to be stored on a device, each device has its own credential,
  and a device can be revoked independently.
- **If anonymous sign-ins stay off:** your owner account and dashboard still work, but devices cannot
  enroll or sync. Enable the provider only if you want Remote Management.
- **Owner guard:** only the first authenticated owner can initialize the dashboard account. Other
  Auth users can exist but cannot access your account or devices.

## 1. Create the project

1. Create a project at <https://supabase.com/dashboard>.
2. Open **Connect** (or **Settings → API Keys**) and copy:
   - **Project URL** — `https://<project-ref>.supabase.co`
   - **Publishable key** — `sb_publishable_...`

Never use a **secret/service-role** key in the app or dashboard. The publishable key is public by
design.

## 2. Set up the database

1. Open the project's **SQL Editor**.
2. Open the release's [`setup.sql`](../../supabase/releases/) (the app wizard can copy it or save it
   as a file; it never runs SQL itself) and run it once in **SQL Editor**. Running it from a computer
   is easier than a mobile browser.
3. In **Authentication → Providers**:
   - enable **Allow anonymous sign-ins**. Remote Management uses this to give each device its own
     private, unnamed identity, so the app can link a device to your account without storing your
     email or password on the device.
   - keep the **Email provider ON** for email/password sign-in, but turn **Confirm email OFF**.
     Signup is direct and no email is sent;
   - open **Authentication → Hooks → Before User Created**: turn the hook ON, set
     **Hook type: Postgres**, **Postgres schema: public**, select **Postgres function:
     `hook_single_owner_signup`**, and click Save. This deliberately keeps the dashboard
     single-owner: it blocks every later email/password account, even if someone finds the
     dashboard URL, while anonymous devices remain allowed for enrollment;
   - in **Authentication → Rate Limits**, set anonymous sign-ins to 10/hour/IP and sign-in/sign-up
     to 10 per 5 minutes/IP (or verify your chosen values). CAPTCHA is optional.

   This setting is optional only in the sense that you can leave it off if you do not want Remote
   Management. If it stays off, your owner account and dashboard still work, but no device can
   enroll or sync remote commands.
4. Run the release's [`verify.sql`](../../supabase/releases/) in SQL Editor; every row must say
   **PASS**. The verifier checks FORCE RLS, exact policies/grants, RPC properties, Realtime scope,
   the cleanup jobs, and the schema marker — not just table names.

Do not run `setup.sql` twice and do not mix it with `supabase db push` on the same project.

## 3. Generate and host the dashboard

1. In the app: **Remote Management → Open setup wizard**.
2. Paste the Project URL and publishable key → **Continue**.
3. **Save dashboard ZIP** — the app suggests `wasted-dashboard.zip`; it contains
   `index.html`, `manifest.webmanifest`, `sw.js`, `icons/`, and `_headers`. It installs as a
   standalone app and provides an offline app shell on supported browsers.
4. In Netlify, sign in and choose **Add new project → Deploy manually**, then upload the ZIP.
   Confirm the project appears and copy its final `https://…` URL. If it starts private, make it
   public. To update later, upload the new ZIP to the same project; do not create another project.

The file contains only the project URL + publishable key. It is not a secret, but anyone who can
open the URL can attempt to create an account; RLS protects the data.

## 4. Create the owner and enroll the device

Create the owner account before sharing the dashboard URL: the first authenticated owner claims the
single-owner bootstrap.

1. Open the hosted dashboard → **Create Account** (email, password ≥ 6 characters, confirmed
   password, account name). Signup is direct: the first account becomes the owner immediately and
   no email is sent.
2. The Before User Created hook rejects every later email/password account. Use the owner account.
   Account-name changes are in the dashboard; password/account recovery is handled from the
   Supabase dashboard.
3. In the dashboard, **Enroll a device → Create enrollment token** (valid for the configured
   enrollment lifetime; default 10 minutes).
4. In the app: paste the **Dashboard website URL**, Save.
5. Enable **Remote Management** (forces Debug Mode ON) → paste the token → **Claim**.
6. Wait for the device card to appear, then send **Reset Timer** and confirm the timer updates.

## 5. Post-setup checks

- Dashboard shows the device as “Active” (contact within 150 s) or “Inactive”.
- Debug Mode shows ON.
- Settings changes apply on the device at the next sync.
- Force Reset is blocked with Debug Mode ON.

## Multiple devices

Backend setup is **once per Supabase project/account**, not per device.

- **First device:** complete this whole page.
- **Additional devices:** do not run `setup.sql`, do not change Auth settings, and do not generate
  another dashboard. In the app, enter the same Project URL, publishable key, and dashboard URL,
  enable Remote Management, then create a new enrollment token in the dashboard and claim it.
- Create one token per device; tokens are independent and each expires after the configured
  enrollment lifetime (default 10 minutes).
- Settings have global defaults plus optional per-device overrides; a device override wins until
  cleared. Debug Mode is per device. Commands require choosing all enrolled devices or a specific
  selection; there is no implicit fan-out.

## How enrollment tokens work

The dashboard never adds a device by itself. The token is a one-time pairing code:

1. Signed in on the dashboard, click **Create enrollment token**. The browser calls
   `create_device_enrollment`; Supabase generates a random token, stores only its hash, and returns
   the plaintext once. It expires after the configured enrollment lifetime (default 10 minutes).
2. Copy that token into the **Wasted app on the device** (Remote Management → token → Claim).
3. The app calls `claim_device_enrollment` with the token. Supabase hashes it, verifies it is unused
   and unexpired, binds that device's anonymous identity/installation to your account, and consumes
   the token.
4. Repeat for each device. Tokens are independent now, so you can create several. The first device
   gets no special token; every device uses one of these one-time codes.

The token is not embedded in the HTML file. It is a runtime value created and stored by Supabase;
the HTML is only the UI. The app never sees your owner password, and the dashboard never needs a
secret/service-role key.

More: [hosting.md](./hosting.md) · [security.md](./security.md) ·
[troubleshooting.md](./troubleshooting.md)
