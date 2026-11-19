-- RLS tests with simulated identities (role + JWT claims), wrapped in a rolled-back transaction.
-- Fixtures: owners A/B, devices A1 (active), A2 (revoked), B1, and an unclaimed anonymous identity.
-- Run via Supabase MCP after migrations 001–004.

begin;

-- Legacy lifecycle suites exercise individual commands; the latest-command behavior is
-- covered end-to-end by remote_device_settings_targets_test.sql.
alter table public.device_commands disable trigger device_commands_supersede_pending;

create function pg_temp.assert_true(p_condition boolean, p_message text)
returns void
language plpgsql
as $$
begin
    if not coalesce(p_condition, false) then
        raise exception 'assertion failed: %', p_message;
    end if;
end;
$$;

create function pg_temp.expect_error(p_sql text, p_state text default null)
returns void
language plpgsql
as $$
begin
    begin
        execute p_sql;
    exception when others then
        if p_state is null or sqlstate = p_state then
            return;
        end if;
        raise exception 'expected sqlstate %, got % (%) for: %', p_state, sqlstate, sqlerrm, p_sql;
    end;
    raise exception 'expected an error but the statement succeeded: %', p_sql;
end;
$$;

-- Default function ACL is locked down globally; grant helpers to the roles tests switch to.
grant execute on function pg_temp.assert_true(boolean, text) to authenticated, anon;
grant execute on function pg_temp.expect_error(text, text) to authenticated, anon;

-- ─── Fixtures (as postgres, inside the transaction) ──────────────────────────

insert into auth.users (instance_id, id, aud, role, created_at, updated_at, is_anonymous) values
    ('00000000-0000-0000-0000-000000000000', '0a0a0a0a-0000-4000-8000-0000000000a1', 'authenticated', 'authenticated', now(), now(), false),
    ('00000000-0000-0000-0000-000000000000', '0b0b0b0b-0000-4000-8000-0000000000b1', 'authenticated', 'authenticated', now(), now(), false),
    ('00000000-0000-0000-0000-000000000000', '0a1a1a1a-0000-4000-8000-0000000000a1', 'authenticated', 'authenticated', now(), now(), true),
    ('00000000-0000-0000-0000-000000000000', '0a2a2a2a-0000-4000-8000-0000000000a2', 'authenticated', 'authenticated', now(), now(), true),
    ('00000000-0000-0000-0000-000000000000', '0b1b1b1b-0000-4000-8000-0000000000b1', 'authenticated', 'authenticated', now(), now(), true),
    ('00000000-0000-0000-0000-000000000000', '0c0c0c0c-0000-4000-8000-0000000000c1', 'authenticated', 'authenticated', now(), now(), true);

insert into public.accounts (id, handle) values
    ('0a0a0a0a-0000-4000-8000-0000000000a1', 'RlsOwnerA'),
    ('0b0b0b0b-0000-4000-8000-0000000000b1', 'RlsOwnerB');

insert into public.account_settings (account_id, revision, initialized_at) values
    ('0a0a0a0a-0000-4000-8000-0000000000a1', 1, now()),
    ('0b0b0b0b-0000-4000-8000-0000000000b1', 1, now());

insert into public.devices (id, account_id, auth_user_id, installation_id, name, revoked_at) values
    ('da000000-0000-4000-8000-0000000000a1', '0a0a0a0a-0000-4000-8000-0000000000a1',
     '0a1a1a1a-0000-4000-8000-0000000000a1', '1a000000-0000-4000-8000-0000000000a1', 'A1', null),
    ('da000000-0000-4000-8000-0000000000a2', '0a0a0a0a-0000-4000-8000-0000000000a1',
     '0a2a2a2a-0000-4000-8000-0000000000a2', '1a000000-0000-4000-8000-0000000000a2', 'A2 revoked', now()),
    ('db000000-0000-4000-8000-0000000000b1', '0b0b0b0b-0000-4000-8000-0000000000b1',
     '0b1b1b1b-0000-4000-8000-0000000000b1', '1b000000-0000-4000-8000-0000000000b1', 'B1', null);

insert into public.device_status (device_id, account_id, actual_lock_timer_minutes, actual_usb_trigger_enabled,
                                  timer_remaining_seconds, timer_running, debug_mode, reset_supported,
                                  remote_enabled, app_version, android_sdk, timer_sampled_at, last_seen_at, updated_at)
values ('da000000-0000-4000-8000-0000000000a1', '0a0a0a0a-0000-4000-8000-0000000000a1', 10080, false,
        3600, true, true, true, true, '2.0.0-test', 34, now(), now(), now()),
       ('db000000-0000-4000-8000-0000000000b1', '0b0b0b0b-0000-4000-8000-0000000000b1', 1440, true,
        0, false, true, true, true, '2.0.0-test', 33, now(), now(), now());

insert into public.account_actions (id, account_id, action_type, idempotency_key, target_scope, expires_at) values
    ('aa000000-0000-4000-8000-0000000000a1', '0a0a0a0a-0000-4000-8000-0000000000a1',
     'reset_timer', 'cc000000-0000-4000-8000-0000000000a1', 'all', now() + interval '24 hours');

insert into public.account_action_targets (action_id, account_id, device_id) values
    ('aa000000-0000-4000-8000-0000000000a1', '0a0a0a0a-0000-4000-8000-0000000000a1',
     'da000000-0000-4000-8000-0000000000a1');

insert into public.device_settings (
    device_id, account_id, revision, lock_timer_minutes
) values (
    'da000000-0000-4000-8000-0000000000a1', '0a0a0a0a-0000-4000-8000-0000000000a1', 1, 60
);

insert into public.device_commands (id, action_id, device_id, account_id, command_type, expires_at) values
    ('ee000000-0000-4000-8000-0000000000a1', 'aa000000-0000-4000-8000-0000000000a1',
     'da000000-0000-4000-8000-0000000000a1', '0a0a0a0a-0000-4000-8000-0000000000a1',
     'reset_timer', now() + interval '24 hours');

insert into public.command_events (command_id, device_id, account_id, event_type) values
    ('ee000000-0000-4000-8000-0000000000a1', 'da000000-0000-4000-8000-0000000000a1',
     '0a0a0a0a-0000-4000-8000-0000000000a1', 'queued');

-- ─── RLS flags and schema privileges (as postgres) ───────────────────────────

do $$
declare
    r record;
    public_tables integer := 0;
begin
    for r in
        select c.relname, c.relrowsecurity, c.relforcerowsecurity
          from pg_class c
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'public' and c.relkind = 'r'
    loop
        public_tables := public_tables + 1;
        perform pg_temp.assert_true(r.relrowsecurity, 'RLS not enabled on ' || r.relname);
        perform pg_temp.assert_true(r.relforcerowsecurity, 'RLS not forced on ' || r.relname);
    end loop;

    perform pg_temp.assert_true(public_tables = 10, 'expected exactly 10 public tables, found ' || public_tables);

    perform pg_temp.assert_true(
        not has_schema_privilege('authenticated', 'private', 'usage'),
        'authenticated must not have usage on private schema'
    );
    perform pg_temp.assert_true(
        not has_schema_privilege('anon', 'private', 'usage'),
        'anon must not have usage on private schema'
    );
    perform pg_temp.assert_true(
        not has_table_privilege('authenticated', 'private.device_enrollments', 'select'),
        'authenticated must not read enrollment records'
    );
    perform pg_temp.assert_true(
        not has_table_privilege('anon', 'public.devices', 'select'),
        'anon must not read devices'
    );

    perform pg_temp.assert_true(
        not exists (
            select 1 from pg_default_acl
             where defaclrole = 'postgres'::regrole
               and defaclnamespace = 'public'::regnamespace
               and defaclobjtype = 'r'
               and (defaclacl::text like '%anon=%' or defaclacl::text like '%authenticated=%')
        ),
        'future tables must not be auto-granted to anon/authenticated'
    );
    perform pg_temp.assert_true(
        not exists (
            select 1 from pg_default_acl
             where defaclrole = 'postgres'::regrole
               and defaclnamespace = 'public'::regnamespace
               and defaclobjtype = 'f'
               and (defaclacl::text like '%anon=%' or defaclacl::text like '%authenticated=%')
        ),
        'future functions must not be auto-granted to anon/authenticated'
    );
end;
$$;

-- ─── Effective privileges (not just ACL text) ───────────────────────────────

do $$
declare
    table_name text;
    signature text;
    proc_oid oid;
begin
    foreach table_name in array array[
        'accounts', 'devices', 'account_settings', 'device_status',
        'device_settings', 'device_notes', 'account_actions', 'account_action_targets',
        'device_commands', 'command_events'
    ] loop
        perform pg_temp.assert_true(
            has_table_privilege('authenticated', format('public.%I', table_name), 'SELECT'),
            'authenticated must read ' || table_name
        );
        perform pg_temp.assert_true(
            not has_table_privilege('authenticated', format('public.%I', table_name), 'INSERT')
            and not has_table_privilege('authenticated', format('public.%I', table_name), 'UPDATE')
            and not has_table_privilege('authenticated', format('public.%I', table_name), 'DELETE'),
            'authenticated must not write ' || table_name
        );
        perform pg_temp.assert_true(
            not has_table_privilege('anon', format('public.%I', table_name), 'SELECT'),
            'anon must not read ' || table_name
        );
    end loop;

    foreach signature in array array[
        'public.initialize_owner_account(text)',
        'public.update_owner_handle(text)',
        'public.create_device_enrollment()',
        'public.rename_device(uuid,text)',
        'public.revoke_device(uuid)',
        'public.update_account_settings(bigint,integer,boolean,boolean)',
        'public.create_account_action(text,uuid,text,uuid[])',
        'public.expire_account_commands()',
        'public.claim_device_enrollment(text,uuid,text,integer,boolean,boolean)',
        'public.device_reconcile(uuid,bigint,bigint,integer,boolean,boolean,bigint,boolean,boolean,boolean,boolean,smallint,boolean,text,smallint,text,text,text)',
        'public.device_transition_command(uuid,uuid,text,text,text,text)',
        'public.set_device_debug_mode(uuid,boolean,uuid)',
        'public.list_device_effective_settings()',
        'public.update_device_settings(uuid,bigint,jsonb)',
        'public.update_device_notes(uuid,text)'
    ] loop
        proc_oid := signature::regprocedure;
        perform pg_temp.assert_true(
            (select prosecdef from pg_proc where oid = proc_oid),
            signature || ' must be security definer'
        );
        perform pg_temp.assert_true(
            (select coalesce('search_path=""' = any(proconfig), false) from pg_proc where oid = proc_oid),
            signature || ' must pin an empty search_path'
        );
        perform pg_temp.assert_true(
            has_function_privilege('authenticated', proc_oid, 'EXECUTE'),
            signature || ' must be executable by authenticated'
        );
        perform pg_temp.assert_true(
            not has_function_privilege('anon', proc_oid, 'EXECUTE'),
            signature || ' must not be executable by anon/public'
        );
    end loop;
end;
$$;

-- Future functions must not inherit PUBLIC execute.
create function public.__wasted_default_acl_probe()
returns integer language sql as 'select 1';

do $$
begin
    perform pg_temp.assert_true(
        not has_function_privilege('anon', 'public.__wasted_default_acl_probe()', 'EXECUTE'),
        'future functions must not be executable by anon'
    );
    perform pg_temp.assert_true(
        not has_function_privilege('authenticated', 'public.__wasted_default_acl_probe()', 'EXECUTE'),
        'future functions must not be executable by authenticated by default'
    );
end;
$$;

drop function public.__wasted_default_acl_probe();

-- ─── Owner A ─────────────────────────────────────────────────────────────────

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);

do $$
begin
    perform pg_temp.assert_true((select count(*) from public.accounts) = 1, 'owner A must see only own account');
    perform pg_temp.assert_true((select count(*) from public.devices) = 2, 'owner A must see both own devices (incl. revoked)');
    perform pg_temp.assert_true((select count(*) from public.account_settings) = 1, 'owner A settings visibility');
    perform pg_temp.assert_true((select count(*) from public.device_status) = 1, 'owner A status visibility');
    perform pg_temp.assert_true((select count(*) from public.account_actions) = 1, 'owner A actions visibility');
    perform pg_temp.assert_true((select count(*) from public.device_commands) = 1, 'owner A commands visibility');
    perform pg_temp.assert_true((select count(*) from public.command_events) = 1, 'owner A events visibility');
    perform pg_temp.assert_true(
        not exists (select 1 from public.devices where account_id = '0b0b0b0b-0000-4000-8000-0000000000b1'),
        'owner A must not see owner B devices'
    );
end;
$$;

-- no direct writes for owners
select pg_temp.expect_error(
    $$insert into public.devices (account_id, auth_user_id, installation_id, name)
      values ('0a0a0a0a-0000-4000-8000-0000000000a1', '0a1a1a1a-0000-4000-8000-0000000000a1',
              '3a000000-0000-4000-8000-0000000000a1', 'Sneaky')$$,
    '42501'
);
select pg_temp.expect_error(
    $$update public.account_settings set lock_timer_minutes = 5 where account_id = '0a0a0a0a-0000-4000-8000-0000000000a1'$$,
    '42501'
);
select pg_temp.expect_error(
    $$update public.device_status set timer_running = false where device_id = 'da000000-0000-4000-8000-0000000000a1'$$,
    '42501'
);
select pg_temp.expect_error(
    $$delete from public.device_commands where id = 'ee000000-0000-4000-8000-0000000000a1'$$,
    '42501'
);

-- ─── Owner B ─────────────────────────────────────────────────────────────────

select set_config('request.jwt.claims',
    '{"sub":"0b0b0b0b-0000-4000-8000-0000000000b1","role":"authenticated","is_anonymous":false}', true);

do $$
begin
    perform pg_temp.assert_true((select count(*) from public.accounts) = 1, 'owner B must see only own account');
    perform pg_temp.assert_true((select count(*) from public.devices) = 1, 'owner B must see only own device');
    perform pg_temp.assert_true(
        not exists (select 1 from public.device_commands),
        'owner B must not see owner A commands'
    );
    perform pg_temp.assert_true(
        not exists (select 1 from public.account_actions),
        'owner B must not see owner A actions'
    );
end;
$$;

-- ─── Device A1 (active anonymous device) ─────────────────────────────────────

select set_config('request.jwt.claims',
    '{"sub":"0a1a1a1a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":true}', true);

do $$
begin
    perform pg_temp.assert_true(
        (select count(*) from public.devices) = 1
            and exists (select 1 from public.devices where id = 'da000000-0000-4000-8000-0000000000a1'),
        'device A1 must see exactly its own active row'
    );
    perform pg_temp.assert_true((select count(*) from public.account_settings) = 1, 'device A1 settings visibility');
    perform pg_temp.assert_true((select count(*) from public.device_commands) = 1, 'device A1 commands visibility');
    perform pg_temp.assert_true(
        not exists (select 1 from public.device_status),
        'devices must not read device_status'
    );
    perform pg_temp.assert_true(
        not exists (select 1 from public.account_actions),
        'devices must not read account_actions'
    );
    perform pg_temp.assert_true(
        not exists (select 1 from public.command_events),
        'devices use RPC command state and do not read events'
    );
end;
$$;

select pg_temp.expect_error(
    $$update public.devices set name = 'renamed' where id = 'da000000-0000-4000-8000-0000000000a1'$$,
    '42501'
);

-- ─── Revoked device A2 ───────────────────────────────────────────────────────

select set_config('request.jwt.claims',
    '{"sub":"0a2a2a2a-0000-4000-8000-0000000000a2","role":"authenticated","is_anonymous":true}', true);

do $$
begin
    perform pg_temp.assert_true(
        not exists (select 1 from public.devices),
        'revoked device must see no device rows'
    );
    perform pg_temp.assert_true(
        not exists (select 1 from public.account_settings),
        'revoked device must see no settings'
    );
    perform pg_temp.assert_true(
        not exists (select 1 from public.device_commands),
        'revoked device must see no commands'
    );
end;
$$;

-- ─── Unclaimed anonymous identity ────────────────────────────────────────────

select set_config('request.jwt.claims',
    '{"sub":"0c0c0c0c-0000-4000-8000-0000000000c1","role":"authenticated","is_anonymous":true}', true);

do $$
begin
    perform pg_temp.assert_true(not exists (select 1 from public.accounts), 'unclaimed identity accounts');
    perform pg_temp.assert_true(not exists (select 1 from public.devices), 'unclaimed identity devices');
    perform pg_temp.assert_true(not exists (select 1 from public.account_settings), 'unclaimed identity settings');
    perform pg_temp.assert_true(not exists (select 1 from public.device_status), 'unclaimed identity status');
    perform pg_temp.assert_true(not exists (select 1 from public.account_actions), 'unclaimed identity actions');
    perform pg_temp.assert_true(not exists (select 1 from public.device_commands), 'unclaimed identity commands');
    perform pg_temp.assert_true(not exists (select 1 from public.command_events), 'unclaimed identity events');
end;
$$;

-- ─── anon role has no access at all ──────────────────────────────────────────

set local role anon;

select pg_temp.expect_error($$select count(*) from public.devices$$, '42501');
select pg_temp.expect_error($$select count(*) from public.accounts$$, '42501');
select pg_temp.expect_error(
    $$select public.initialize_owner_account('Nope')$$,
    '42501'
);

rollback;
