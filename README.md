# Wasted (Modded v3.0.0)

**This is a modded version of [Wasted](https://github.com/x13a/Wasted)** — the original open-source emergency lock & factory reset app by [@x13a](https://github.com/x13a).

## What's New in This Fork

- **P2P Network**: Remote factory reset via encrypted local Wi-Fi (TLS on port 9876) with mutual client certificates, pinned peer leaf-certificate hashes, identity-bound messages, and an encrypted peer database.
- **Android 14+ Device Owner Setup**: Interactive Shizuku-based setup to make Wasted Device Owner for full factory reset capability
- **Android 14+ Reset**: On Android 14+, eligible Device Owner installs use `wipeDevice()`; non-Device-Owner installs use the existing best-effort chain (Shizuku + manual cleanup). Android <14 uses the legacy `wipeData()` path. Debug Mode gates every path.
- **Debug Mode**: Enabled by default. While enabled, dangerous commands never execute — they are logged locally, and remote commands are shown as a notification stating they were received but not executed. Disable it locally with a danger confirmation to allow real wipes.
- **Biometric App Unlock**: Optional biometric/device-credential prompt when Wasted opens. It is enabled by default and can be turned off under **Settings → Security**. When disabled, Wasted opens without an authentication prompt.

All original features remain unchanged and fully compatible with Android 7+.

Release notes: [`CHANGELOG.md`](CHANGELOG.md).

---

> [!WARNING]
> This is a modded version. For the original app, see [x13a/Wasted](https://github.com/x13a/Wasted).

Lock a device and wipe its data on emergency.

[<img 
     src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/me.lucky.wasted/)
[<img 
      src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" 
      alt="Get it on Google Play" 
      height="80">](https://play.google.com/store/apps/details?id=me.lucky.wasted)

<img 
     src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" 
     width="30%" 
     height="30%">

You can use [PanicKit](https://guardianproject.info/code/panickit/), tile, shortcut or send a 
message with a secret code. On trigger, using 
[Device Administration API](https://developer.android.com/guide/topics/admin/device-admin), it 
locks a device and optionally runs wipe (factory reset). Or it can send a broadcast message 
instead of the wipe.

Also you can:
* fire when a device was not unlocked for X time
* fire when a USB data connection is made while a device is locked
* fire when a fake messenger app is launched
* fire when a duress password is entered (companion app: [Duress](https://github.com/x13a/Duress))

The app works in `Work Profile` too, but with limitations. Use it to install risky apps and 
`Wasted` in it. Then you can wipe this profile data with one click without wiping the whole device.

For base security take a look at: [Sentry](https://github.com/x13a/Sentry).  
Only encrypted device may guarantee that the data will not be recoverable.

Be aware that the app does not work in _safe mode_.

## Remote Management

This fork adds optional remote management through your own Supabase project. It is disabled by default and performs no network work until it is configured, enrolled, and enabled. Full backend documentation is indexed in [`docs/README.md`](docs/README.md); the six-step setup is [`docs/backend/quickstart.md`](docs/backend/quickstart.md).

### Backend setup

1. Create a free Supabase project and open **SQL Editor**.
2. Paste `supabase/setup.sql` and press **Run** once. It creates the tables, RLS policies, RPCs, grants, Realtime publication, and cleanup jobs. No CLI or Docker is required.
3. In **Authentication → Providers**, enable **Allow anonymous sign-ins** (per-device identities). Keep the **Email provider ON** for email/password sign-in, but turn **Confirm email OFF**. Then open **Authentication → Hooks → Before User Created**: turn the hook ON, set **Hook type: Postgres**, **Postgres schema: public**, select **Postgres function: `hook_single_owner_signup`**, and click **Save**. The first account becomes the owner immediately; later email/password accounts are rejected, while anonymous devices remain allowed. This prevents another person who finds the dashboard URL from creating a second owner account. In **Authentication → Rate Limits**, set anonymous sign-ins to 10/hour/IP and sign-in/sign-up to 10 per 5 minutes/IP (or verify your chosen values). CAPTCHA is optional.
4. Run `supabase/verify.sql`; every row should say PASS.
5. The app setup wizard provides the same SQL and instructions. Developers can instead use the CLI/MCP path documented in `supabase/README.md`.

The app and dashboard store only the project URL and a publishable key. Never use a secret or service-role key.

### Android setup

1. Open **Remote Management** and use **Open setup wizard** (or enter the project URL, publishable key, and dashboard URL manually); save the configuration.
2. In the dashboard, create an enrollment token (valid for the configured enrollment lifetime; default 10 minutes).
3. Enable Remote Management (this forces Debug Mode ON), enter the token, and claim it. The device appears in the dashboard after the first sync.

If Wasted is not the Device Owner on Android 14+, Remote Management shows a **Device Owner setup required** card. Full factory reset commands (remote or local) need that role; tap **Go to Home setup** and follow **Set up Device Owner** there.

> [!IMPORTANT]
> Making Wasted Device Owner is final for that installation. Android blocks uninstalling the app while it owns the device, and Device Owner cannot be removed from Settings. The normal way to remove ownership or uninstall Wasted is a factory reset.

### Keep remote management working after reboot

Wasted registers a boot receiver, a foreground service, a persisted JobScheduler job, and a WorkManager recovery path. Many Android manufacturers still block background auto-start until the user allows the app once. This is enforced by the phone's vendor power manager, not by Wasted.

Common locations (names vary by model and OS version):

- **Google Pixel / AOSP / Motorola / Nokia / Sony / Asus / Nothing:** normally no vendor auto-start switch is needed; leave Wasted unrestricted in battery settings if the option exists.
- **TECNO / Infinix / itel:** Phone Master → Tools → **Auto-start management** → allow Wasted; also set Wasted battery usage to unrestricted.
- **Xiaomi / Redmi / POCO:** enable **Autostart** and set battery saver to **No restrictions**.
- **Samsung:** remove Wasted from **Sleeping apps / Deep sleeping apps**; set battery to **Unrestricted**.
- **OPPO / Realme / OnePlus:** enable **Auto Launch** and unrestricted background activity.
- **Vivo / iQOO:** enable **Autostart** and allow high background power usage.
- **Huawei / Honor:** App launch → manage Wasted manually; allow background activity.

After allowing it, reboot the phone and do **not** open Wasted. The foreground notification should appear and the dashboard's last-contact time should update within about a minute. If it does not, the vendor auto-start rule is still blocking the app.

### Hosting the dashboard

The dashboard package is generated **by the app**, already configured with your own Supabase project URL and publishable key. There is no file editing and no dashboard server: the page talks directly to your Supabase project over HTTPS/WSS.

1. In the app: **Remote Management → Open setup wizard**.
2. Create a free Supabase project, then paste its **Project URL** and **publishable key** into the wizard.
3. In Supabase, open **SQL Editor**, run the setup SQL copied from the wizard, then under **Authentication → Providers** enable **anonymous sign-ins**, keep the **Email provider ON**, and turn **Confirm email OFF**. Enable **Authentication → Hooks → Before User Created** with Hook type **Postgres**, Postgres schema **public**, and Postgres function **`hook_single_owner_signup`**; click Save. Anonymous sign-ins give each device its own private identity; the first email/password account becomes the owner and later email accounts are rejected. If you do not want Remote Management, you can leave anonymous sign-ins off; the rest of the app and the dashboard still work, but devices cannot enroll or sync. Under **Authentication → Rate Limits**, set anonymous sign-ins to 10/hour/IP and sign-in/sign-up to 10 per 5 minutes/IP (or verify your chosen values). CAPTCHA is optional.
4. In the wizard, use **Save dashboard ZIP**. It contains `index.html`,
   `manifest.webmanifest`, `sw.js`, `icons/`, and `_headers`. The package installs as a standalone
   app and provides the offline app shell on supported browsers.
5. In Netlify, sign in and choose **Add new project → Deploy manually**, then upload the ZIP.
   Copy the project’s HTTPS URL. If the project starts private, make it public.
   No Supabase Auth URL configuration is needed for owner signup.
6. Open the hosted URL, create your account, then paste that URL into Remote Management, Save, and
   Enable. Create an enrollment token in the dashboard and claim it in the app. The account name is
   saved to Supabase and appears on every browser; change it under **Account**. The **Account** tab
   also changes your password. The sign-in email is changed from the Supabase dashboard instead
   (**Authentication → Users → Edit user**), because an email change sends confirmation mail and is
   easy to get wrong; the new address works for sign-in immediately.

To update an existing dashboard, open that **same Netlify project → project menu → Deploys**, and
upload the new ZIP there. Do not create another project; that changes the URL and requires updating
Remote Management.

### Install as an app (PWA)

After deploying the ZIP over HTTPS, the dashboard can be installed like an app:

- **Chrome/Edge (Android, Windows, macOS, desktop):** use the browser install icon or the **Install
  app** action in the header menu.
- **Safari (iOS/iPadOS 16.4+, macOS 14+):** use **Share → Add to Home Screen** or **File → Add to
  Dock**. The app icon comes from the included Apple touch icon.
- **Firefox desktop:** Firefox does not install manifest-based PWAs natively; it needs a PWA
  extension. Firefox Android adds a browser-badged home-screen shortcut rather than a standalone
  app. This is a browser limitation, not a dashboard setting.
- **Local preview:** service workers are skipped on `localhost`; append `?pwa=1` to a production
  build preview if you need to test them locally.

### Local dashboard preview

For UI work, you do not need the APK or a redeploy. Use the Vite dev server:

1. Copy `.env.example` to `.env` at the repo root and fill in `SUPABASE_URL`,
   `SUPABASE_PUBLISHABLE_KEY`, and optionally `DASHBOARD_URL=http://localhost:5173/`.
2. `cd web && pnpm install` once.
3. `pnpm dev`, then open <http://localhost:5173/>.
4. Edit `web/index.html`, `web/src/`, or `web/styles/`; Vite reloads automatically
   (HMR for JavaScript, live CSS updates, full reload for HTML).

`.env` is gitignored. The dashboard reads only those three public values; the other
`.env.example` entries are optional local reference for Supabase CLI/database work,
are not read by the app or dashboard, and can remain empty. Production ZIPs generated
by the app still contain that user's own project configuration.

### Building the APK

`./gradlew assembleSignedRelease` (or `assembleDebug` for development) requires Node.js 20+. The
signed task fails unless `keystore.properties` and the keystore exist; unsigned release APKs are
named `Wasted-v<version>-unsigned.apk`. Gradle regenerates the dashboard template,
`supabase/setup.sql`, and the release update bundles from the tracked sources before packaging, so
the APK never contains stale assets; set `NODE_BIN`/`PNPM_BIN` if Node or pnpm is not on `PATH`.
Developers can verify the linked database against the migrations with `./gradlew :app:verifyRemoteSchema`
(requires Supabase CLI, Docker, and `supabase link`).

Release updates are organized by `supabase/releases.json`: each app release lists its cumulative
migrations and schema version, and the build emits a folder per release
(`supabase/releases/<version>/`) with `setup.sql`, cumulative update bundles, `verify.sql`, a
checksummed manifest, and generated apply instructions. There is no automatic update check; release
notes name the exact bundle to run after a backup.

No CORS setup or build step is needed at the host. Updating the dashboard means uploading the new
ZIP to the same Netlify project through **Deploys**; do not replace only `index.html` or create a
new site.

The publishable key is public by design and safe to expose. Never embed a secret/service-role key or the database password. The generated package contains no secret. Anyone who can open the hosted page can attempt to sign up, but only the first owner can initialize the dashboard; extra Auth users cannot access account data.

* Sign In and Create Account are explicit; a failed sign-in never creates an account. Signup is direct, the first account becomes the owner, and the Before User Created hook rejects later email accounts while allowing anonymous devices.
* The account name is stored in Supabase auth metadata at signup and saved to `public.accounts` on first sign-in. It appears on every browser and can be edited under **Account**. Browser storage is not used.
* The page ships a restrictive CSP meta policy (`default-src 'none'`, Supabase-only connections, inline scripts/styles) and `no-referrer`. Meta policies cannot set `frame-ancestors`; production hosting must send `frame-ancestors 'none'` (or `X-Frame-Options: DENY`) via response headers. Use the app's deployable ZIP (`index.html` + `manifest.webmanifest` + `sw.js` + `icons/` + `_headers`) on a header-capable host, or configure the headers there. The page also has a frame-busting fallback.

### Behavior and limits

* Device cards show reported state; Realtime only triggers a refetch and never executes payloads directly. The app uses one process-wide Supabase client/session so the foreground service, worker, and UI cannot race refresh-token rotation. A device counts as "Active" when it contacted the server within the fixed 150-second dashboard window; timers are extrapolated locally.
* Lock trigger / Lock delay / USB Trigger have global defaults plus optional per-device overrides, both revision-protected. The dashboard and app resolve effective values from the server; a device override wins until cleared with “Inherit global”. A local phone change is reported as the device value and does not rewrite either target. Wipe data remains a phone-local setting; it is not a remote setting. When the local flag is ON, a completed Lock trigger starts the reset; Force Reset still runs its own explicit reset.
* Device cards show reported battery percentage and charging state, plus whether the latest global/device settings revisions are current or pending. App and Android versions come directly from the phone; inactive devices show their last-known battery as stale rather than as live data. Each card can be folded and its ID hidden; both choices persist in the browser. Private device notes are stored owner-only in Supabase, never sent to the phone.
* When the countdown completes, Wasted fires the Lock trigger: it locks the phone; if Wipe data is on, it starts the reset, subject to Debug Mode. Reset Timer restarts the active countdown; it does nothing while the phone is unlocked or the Lock trigger is OFF. Lock Devices targets only the devices you select (or all enrolled devices) and locks the screen only, requires two confirmations, and waits for the configured offline time (default 5 minutes) if a device is offline. Force Reset sends a factory reset to the selected targets (typed confirmation) and only executes while Debug Mode is OFF.
* Debug Mode is ON by default and blocks destructive remote commands. The dashboard asks for confirmation in both directions; the device applies remote toggles without a local dialog. Turning it OFF waits for the configured offline time (default 10 minutes) if the device stays offline, and supersedes any pending Force Reset.
* Local notifications (on by default, `remote_events` channel) cover settings applied, Reset Timer, Force Reset, blocked or failed commands, the first sync, and sync restored after a reconnect. Repeated notices replace each other instead of stacking, and ordinary 60-second heartbeats stay silent.
* The header menu offers **System**, **Light**, and **Dark** appearance. The choice is stored per browser and applies immediately; System follows the operating system preference.
* Device cards support search by name or ID, Active/Inactive/Debug/Low battery/With notes filters, sorting (including enrollment date), and a persisted Cards/List layout. Each card shows its enrollment date and last-contact time.
* Delivery is not push: an online device syncs over Realtime, otherwise at the next 60-second heartbeat or WorkManager recovery run. Command and enrollment limits are configurable under **Controls → Command & enrollment limits**; defaults are Reset Timer 24 hours, Force Reset and Debug Mode OFF 10 minutes, Lock 5 minutes, Debug Mode ON 24 hours, and enrollment 10 minutes. Changes apply to new commands and tokens only.
* Free-plan guardrails: the dashboard subscribes to status for live updates, polls only visible tabs as a fallback, fetches one latest command per device, and keeps command history/action rows for 180 days. Stale enrollment tokens are removed after 30 days. The measured real-time envelope is in [`docs/backend/capacity.md`](docs/backend/capacity.md).
* Sign-in persists in browser storage and auto-refreshes; refresh tokens do not expire by default. Sign out asks for confirmation and ends only the current browser session, so other devices stay signed in. Signing out of the dashboard does not stop device sync; existing settings, timers, and queued commands remain on the backend.
* The always-visible header menu shows the configured Supabase Project URL, publishable key, and the real hosted Dashboard URL with copy buttons. The first two come from the generated page config; the Dashboard URL is derived from the hosted address (or an optional local-preview value). Viewing or copying them makes no network request.

### Troubleshooting and rollback

* "Device is not enrolled" — claim a fresh token after enabling Remote Management.
* Access-denied or RLS errors — confirm the owner is a normal (non-anonymous) account and the device still belongs to it.
* Reset enrollment clears the device session and encrypted journal, rotates the installation ID, and disables Remote Management. Local settings, timers, Device Admin, and P2P are untouched.

### Design system

The dashboard uses shadcn-compatible semantic CSS tokens (background, card, primary, muted, destructive, border, input, ring, radius, and shadows) without React, Radix, Tailwind, or a component runtime. It keeps the mono dossier labels and restrained red accents as a hybrid skin. System/Light/Dark themes use the same token contract, so a future shadcn theme can be mapped to the existing `[data-theme]` scopes without rewriting markup or behavior.

## Broadcast

* action: `me.lucky.wasted.action.TRIGGER`
* receiver: `me.lucky.wasted/.TriggerReceiver`
* also you have to send a secret code from Wasted with the key: `code`

## Permissions

* DEVICE_ADMIN - lock and optionally wipe a device
* FOREGROUND_SERVICE - receive lock and USB state events
* RECEIVE_BOOT_COMPLETED - persist lock job and foreground service across reboots

## Localization

[<img 
     height="51" 
     src="https://badges.crowdin.net/badge/dark/crowdin-on-light@2x.png" 
     alt="Crowdin">](https://crwd.in/me-lucky-wasted)

## License

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](https://www.gnu.org/licenses/gpl-3.0.en.html)
