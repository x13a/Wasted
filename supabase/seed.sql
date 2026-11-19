-- Synthetic local-development fixtures only. Safe to publish and repeat: every row is fake,
-- deterministic, and inserted with on-conflict guards. Loaded by `supabase db reset` (config.toml).
-- Never contains real people, devices, or credentials.

-- ─── Auth identities ─────────────────────────────────────────────────────────

insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
                        raw_app_meta_data, raw_user_meta_data, created_at, updated_at, is_anonymous)
values
    ('00000000-0000-0000-0000-000000000000', '0a0a0a0a-0000-4000-8000-0000000000a1', 'authenticated',
     'authenticated', 'owner-a@example.test', extensions.crypt('SeedOwnerA!pass123', extensions.gen_salt('bf')),
     now(), '{"provider":"email","providers":["email"]}'::jsonb, '{}'::jsonb, now(), now(), false),
    ('00000000-0000-0000-0000-000000000000', '0b0b0b0b-0000-4000-8000-0000000000b1', 'authenticated',
     'authenticated', 'owner-b@example.test', extensions.crypt('SeedOwnerB!pass123', extensions.gen_salt('bf')),
     now(), '{"provider":"email","providers":["email"]}'::jsonb, '{}'::jsonb, now(), now(), false),
    ('00000000-0000-0000-0000-000000000000', '0a1a1a1a-0000-4000-8000-0000000000a1', 'authenticated',
     'authenticated', null, null, null, '{}'::jsonb, '{}'::jsonb, now(), now(), true),
    ('00000000-0000-0000-0000-000000000000', '0a2a2a2a-0000-4000-8000-0000000000a2', 'authenticated',
     'authenticated', null, null, null, '{}'::jsonb, '{}'::jsonb, now(), now(), true),
    ('00000000-0000-0000-0000-000000000000', '0b1b1b1b-0000-4000-8000-0000000000b1', 'authenticated',
     'authenticated', null, null, null, '{}'::jsonb, '{}'::jsonb, now(), now(), true),
    ('00000000-0000-0000-0000-000000000000', '0c0c0c0c-0000-4000-8000-0000000000c1', 'authenticated',
     'authenticated', null, null, null, '{}'::jsonb, '{}'::jsonb, now(), now(), true)
on conflict (id) do nothing;

insert into auth.identities (id, provider_id, user_id, identity_data, provider, last_sign_in_at, created_at, updated_at)
values
    ('1d0a0a0a-0000-4000-8000-0000000000a1', '0a0a0a0a-0000-4000-8000-0000000000a1',
     '0a0a0a0a-0000-4000-8000-0000000000a1',
     jsonb_build_object('sub', '0a0a0a0a-0000-4000-8000-0000000000a1', 'email', 'owner-a@example.test'),
     'email', now(), now(), now()),
    ('1d0b0b0b-0000-4000-8000-0000000000b1', '0b0b0b0b-0000-4000-8000-0000000000b1',
     '0b0b0b0b-0000-4000-8000-0000000000b1',
     jsonb_build_object('sub', '0b0b0b0b-0000-4000-8000-0000000000b1', 'email', 'owner-b@example.test'),
     'email', now(), now(), now())
on conflict do nothing;

-- ─── Accounts ────────────────────────────────────────────────────────────────

insert into public.accounts (id, handle) values
    ('0a0a0a0a-0000-4000-8000-0000000000a1', 'OwnerA'),
    ('0b0b0b0b-0000-4000-8000-0000000000b1', 'OwnerB')
on conflict (id) do nothing;

insert into public.account_settings (account_id, revision, lock_timer_minutes, usb_trigger_enabled, initialized_at)
values
    ('0a0a0a0a-0000-4000-8000-0000000000a1', 0, 10080, false, null),
    ('0b0b0b0b-0000-4000-8000-0000000000b1', 1, 1440, true, now())
on conflict (account_id) do nothing;

-- ─── Devices ─────────────────────────────────────────────────────────────────

insert into public.devices (id, account_id, auth_user_id, installation_id, name, revoked_at)
values
    ('da000000-0000-4000-8000-0000000000a1', '0a0a0a0a-0000-4000-8000-0000000000a1',
     '0a1a1a1a-0000-4000-8000-0000000000a1', '1a000000-0000-4000-8000-0000000000a1', 'Seed Phone A1', null),
    ('da000000-0000-4000-8000-0000000000a2', '0a0a0a0a-0000-4000-8000-0000000000a1',
     '0a2a2a2a-0000-4000-8000-0000000000a2', '1a000000-0000-4000-8000-0000000000a2', 'Seed Phone A2 (revoked)', now()),
    ('db000000-0000-4000-8000-0000000000b1', '0b0b0b0b-0000-4000-8000-0000000000b1',
     '0b1b1b1b-0000-4000-8000-0000000000b1', '1b000000-0000-4000-8000-0000000000b1', 'Seed Phone B1', null)
on conflict (id) do nothing;
