# Quick start (Supabase Cloud, free plan)

Six steps, no CLI or Docker. Details are linked at the bottom.

1. **Create a free Supabase project** at <https://supabase.com/dashboard> → **New project**.
   Give it a name and a database password (that password protects the database itself and is not
   used by the app). Leave **Region** on Auto. The **Security** defaults are fine as they are:
   *Enable Data API* must stay on (the app talks to the database through it), while
   *Automatically expose new tables* and *Enable automatic RLS* can be left either way — the setup
   SQL grants only `SELECT` to `authenticated`, nothing to `anon`, and enables **and forces** Row
   Level Security on every table it creates, so the setup alone decides access. Then wait for the
   project to finish provisioning.
2. **Open the app**: Remote Management → **Open setup wizard** → paste the **Project URL** and
   **Publishable key** (never the secret/service-role key).
3. **Set up the database**: in Supabase, open **SQL Editor**, run the release's `setup.sql` once,
   then **Authentication → Providers**: enable **Allow anonymous sign-ins**, keep the **Email
   provider ON**, and turn **Confirm email OFF**. Enable **Authentication → Hooks → Before User Created** with Hook type
   **Postgres**, Postgres schema **public**, and Postgres function **`hook_single_owner_signup`**;
   click Save. Signup is direct; the first account becomes the owner and later email accounts are
   rejected, even if someone finds the dashboard URL. Anonymous sign-ins give each device its own private identity, so your
   email/password is never stored on the device. If you do not want Remote Management, you can leave
   them off; the owner account and dashboard still work, but devices cannot enroll or sync. Under
   **Rate Limits**, set anonymous 10/hour/IP and sign-in/sign-up 10 per 5 minutes/IP (or verify your
   values). Run `verify.sql`; every row must say PASS. The app never runs SQL; use **Save setup SQL
   file** in the wizard to move it to a computer if that is easier.
4. **Generate the dashboard** in the wizard and save the deployable ZIP (`index.html`,
   `manifest.webmanifest`, `sw.js`, `icons/`, `_headers`). The package supports PWA install and the
   offline app shell on supported browsers.
   In <https://app.netlify.com/>, sign in, choose **Add new project → Deploy manually**, and upload
   the ZIP. Copy the project URL; make it public if it starts private. For updates, upload the new
   ZIP to the same project; do not create another project.
5. **Create your account** on the hosted dashboard URL. The first account becomes the owner
   immediately; no email is sent. The account name is saved to Supabase and can be changed under
   **Account**.
6. **Connect the app**: paste the dashboard URL into Remote Management, Save, Enable; create an
   enrollment token in the dashboard and claim it in the app. Send Reset Timer to verify.
   Then allow Wasted to **auto-start / run in the background** in the phone's vendor settings.
   Pixel/AOSP-style phones normally need only unrestricted battery usage; TECNO/Infinix/itel use
   Phone Master → Tools → Auto-start management. Other brands use similar Autostart/Battery
   controls. Without that one-time permission, some phones block reboot recovery and commands wait
   until the app is opened. Reboot without opening Wasted to verify the dashboard last-contact time
   updates.

   Command expiry and enrollment-token lifetime can be changed later in
   **Controls → Command & enrollment limits**; the defaults are shown there.

If you are upgrading, save the cumulative bundle whose name matches your installed version
(`update-from-<your version>-to-<current>.sql`); one file covers the jump. Never re-run `setup.sql`
on an existing project. If no generated bundle covers your version, restore or start fresh.

## First device vs additional device vs update

| Situation | Do this |
| --- | --- |
| New deployment (first device) | Create the project, run `setup.sql`, set Auth toggles, generate/host the dashboard, create the owner account, claim a token. |
| Additional device on an existing deployment | **Do not** run SQL, change Auth settings, or generate another dashboard. In Remote Management enter the same Project URL, publishable key, and dashboard URL; Enable; create a new token in the dashboard; claim it. |
| Existing installation after an app update | Install the new APK and run only the update bundle matching your installed version; never run `setup.sql`. |

Create one token per device; tokens are independent and each expires after the configured enrollment
lifetime (default 10 minutes).

**How the token works:** the dashboard asks Supabase for a one-time code (stored hashed), shows it to
you, and you paste it into the app on that device. The app sends it to Supabase, which binds that
device's own anonymous identity to your account and consumes the code. Every device gets its own
code; nothing is stored in the HTML file.

Too technical? A client-operated single backend (one Supabase project + one dashboard) removes all
per-user setup, but that is a product decision outside this flow.

Self-hosting Supabase is **not** required. The supported path is a free Supabase Cloud project as
described above; a self-hosted instance is an optional bonus for users who already run one. See
[optional self-hosting](./self-hosting-and-setup.md#7-self-hosting-supabase-optional-bonus).

Details:
[setup.md](./setup.md) · [hosting.md](./hosting.md) · [updates.md](./updates.md) ·
[troubleshooting.md](./troubleshooting.md) · [security.md](./security.md)
