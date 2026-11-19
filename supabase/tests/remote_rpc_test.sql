-- RPC tests: init, enrollment, claim, reconcile, settings revision, actions/idempotency,
-- transitions/events, expiry, revocation, and clean re-enrollment. Rolled-back transaction;
-- run via Supabase MCP after migrations 001-004.

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

create function pg_temp.expect_error(p_sql text, p_state text default null, p_message_like text default null)
returns void
language plpgsql
as $$
begin
    begin
        execute p_sql;
    exception when others then
        if p_state is not null and sqlstate <> p_state then
            raise exception 'expected sqlstate %, got % (%) for: %', p_state, sqlstate, sqlerrm, p_sql;
        end if;
        if p_message_like is not null and position(p_message_like in sqlerrm) = 0 then
            raise exception 'expected message containing %, got % for: %', p_message_like, sqlerrm, p_sql;
        end if;
        return;
    end;
    raise exception 'expected an error but the statement succeeded: %', p_sql;
end;
$$;

-- Default function ACL is locked down globally; grant helpers to the roles tests switch to.
grant execute on function pg_temp.assert_true(boolean, text) to authenticated;
grant execute on function pg_temp.expect_error(text, text, text) to authenticated;

-- ─── Fixtures ────────────────────────────────────────────────────────────────

insert into auth.users (instance_id, id, aud, role, created_at, updated_at, is_anonymous) values
    ('00000000-0000-0000-0000-000000000000', '0a0a0a0a-0000-4000-8000-0000000000a1', 'authenticated', 'authenticated', now(), now(), false),
    ('00000000-0000-0000-0000-000000000000', '0a2a2a2a-0000-4000-8000-0000000000a2', 'authenticated', 'authenticated', now(), now(), false),
    ('00000000-0000-0000-0000-000000000000', '0a1a1a1a-0000-4000-8000-0000000000a1', 'authenticated', 'authenticated', now(), now(), true),
    ('00000000-0000-0000-0000-000000000000', '0c0c0c0c-0000-4000-8000-0000000000c1', 'authenticated', 'authenticated', now(), now(), true),
    ('00000000-0000-0000-0000-000000000000', '0c0c0c0c-0000-4000-8000-0000000000c2', 'authenticated', 'authenticated', now(), now(), true);

-- ─── Owner fixtures and handle updates ──────────────────────────────────────

-- The linked project already has its single owner. Use an isolated owner fixture
-- inside this rolled-back transaction instead of trying to bootstrap a second
-- owner; the new settings/action behavior is what this suite covers.
insert into public.accounts (id, handle)
values ('0a0a0a0a-0000-4000-8000-0000000000a1', 'RpcOwnerA');
insert into public.account_settings (account_id) values ('0a0a0a0a-0000-4000-8000-0000000000a1');

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);

do $$
declare
    account public.accounts;
begin
    account := public.update_owner_handle('RpcOwnerB');
    perform pg_temp.assert_true(account.handle = 'RpcOwnerB', 'owner handle update must persist');

    account := public.update_owner_handle('John  Doe');
    perform pg_temp.assert_true(account.handle = 'John  Doe', 'display names must preserve spaces');

    account := public.update_owner_handle('  RpcOwnerA  ');
    perform pg_temp.assert_true(account.handle = 'RpcOwnerA', 'owner handle update must trim');
end;
$$;

select pg_temp.expect_error(
    $$select public.update_owner_handle('')$$,
    'P0001', 'account name must be 1-32 characters'
);
select pg_temp.expect_error(
    $$select public.update_owner_handle(E'Bad\nName')$$,
    'P0001', 'account name must be 1-32 characters'
);

-- anonymous callers are rejected
select set_config('request.jwt.claims',
    '{"sub":"0c0c0c0c-0000-4000-8000-0000000000c2","role":"authenticated","is_anonymous":true}', true);
select pg_temp.expect_error(
    $$select public.initialize_owner_account('AnonAttempt')$$,
    'P0001', 'owner account required'
);
select pg_temp.expect_error(
    $$select public.update_owner_handle('AnonAttempt')$$,
    'P0001', 'owner account required'
);

-- a second authenticated user cannot bootstrap another dashboard account
select set_config('request.jwt.claims',
    '{"sub":"0a2a2a2a-0000-4000-8000-0000000000a2","role":"authenticated","is_anonymous":false}', true);
select pg_temp.expect_error(
    $$select public.initialize_owner_account('SecondOwner')$$,
    'P0001', 'owner account already initialized'
);
select pg_temp.expect_error(
    $$select public.update_owner_handle('SecondOwner')$$,
    'P0001', 'owner account missing'
);

-- ─── Owner: enrollments ──────────────────────────────────────────────────────

select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);

create temp table pg_temp.t_enroll_first as
    select now() as at, e.* from public.create_device_enrollment() e;
create temp table pg_temp.t_enroll_second as
    select now() as at, e.* from public.create_device_enrollment() e;

do $$
begin
    perform pg_temp.assert_true(
        (select enrollment_token ~ '^[0-9a-f]{64}$' from pg_temp.t_enroll_second),
        'enrollment token must be 64 lowercase hex characters'
    );
    perform pg_temp.assert_true(
        (select enrollment_token <> (select enrollment_token from pg_temp.t_enroll_first)
           from pg_temp.t_enroll_second),
        'each enrollment must generate a fresh token'
    );
    perform pg_temp.assert_true(
        (select expires_at between at + interval '9 minutes' and at + interval '11 minutes'
           from pg_temp.t_enroll_second),
        'enrollment must expire after ~10 minutes'
    );
end;
$$;

reset role;

do $$
begin
    perform pg_temp.assert_true(
        (select revoked_at is null and consumed_at is null from private.device_enrollments
          where id = (select enrollment_id from pg_temp.t_enroll_first)),
        'older unused enrollment must stay open (independent per-device tokens)'
    );
    perform pg_temp.assert_true(
        (select revoked_at is null and consumed_at is null from private.device_enrollments
          where id = (select enrollment_id from pg_temp.t_enroll_second)),
        'newest enrollment must stay open'
    );
end;
$$;

-- ─── Device: claim ───────────────────────────────────────────────────────────

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a1a1a1a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":true}', true);

create temp table pg_temp.t_claim as
    select d.* from public.claim_device_enrollment(
        (select enrollment_token from pg_temp.t_enroll_second),
        '1a000000-0000-4000-8000-0000000000f1',
        'Claimed Device',
        1440,
        true,
        false
    ) d;

do $$
declare
    replay public.devices;
begin
    perform pg_temp.assert_true(
        (select account_id = '0a0a0a0a-0000-4000-8000-0000000000a1' from pg_temp.t_claim),
        'device must bind to the enrolling account'
    );
    perform pg_temp.assert_true(
        (select name = 'Claimed Device' from pg_temp.t_claim),
        'device name must be stored'
    );

    -- lost-response replay: same identity + installation returns the same device
    replay := public.claim_device_enrollment(
        (select enrollment_token from pg_temp.t_enroll_second),
        '1a000000-0000-4000-8000-0000000000f1',
        'Claimed Device',
        1440,
        true,
        false
    );
    perform pg_temp.assert_true(
        replay.id = (select id from pg_temp.t_claim),
        'replay must return the same device, not create a duplicate'
    );
end;
$$;

-- wrong token shape is rejected before any lookup
select pg_temp.expect_error(
    $$select public.claim_device_enrollment('NOTHEX', '1a000000-0000-4000-8000-0000000000f2', 'X', 60, false, false)$$,
    'P0001', '64 lowercase hex'
);

reset role;

do $$
begin
    perform pg_temp.assert_true(
        (select consumed_at is not null and consumed_by_device_id = (select id from pg_temp.t_claim)
           from private.device_enrollments
          where id = (select enrollment_id from pg_temp.t_enroll_second)),
        'token must be consumed by the created device'
    );
    perform pg_temp.assert_true(
        (select count(*) from public.devices where installation_id = '1a000000-0000-4000-8000-0000000000f1') = 1,
        'exactly one device row per installation id'
    );
    perform pg_temp.assert_true(
        (select revision = 1 and lock_timer_minutes = 1440 and usb_trigger_enabled = true
                and initialized_at is not null
           from public.account_settings where account_id = '0a0a0a0a-0000-4000-8000-0000000000a1'),
        'first claim must seed settings from the validated local values with revision 1'
    );
end;
$$;

-- owner callers cannot claim; consumed tokens cannot be reused by another identity
set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);
select pg_temp.expect_error(
    $$select public.claim_device_enrollment(
        (select enrollment_token from pg_temp.t_enroll_second),
        '1a000000-0000-4000-8000-0000000000f9', 'X', 60, false, false)$$,
    'P0001', 'anonymous device identity'
);

select set_config('request.jwt.claims',
    '{"sub":"0c0c0c0c-0000-4000-8000-0000000000c1","role":"authenticated","is_anonymous":true}', true);
select pg_temp.expect_error(
    $$select public.claim_device_enrollment(
        (select enrollment_token from pg_temp.t_enroll_second),
        '1a000000-0000-4000-8000-0000000000f3', 'X', 60, false, false)$$,
    'P0001', 'already used'
);

-- expired token is rejected
reset role;
insert into private.device_enrollments (account_id, token_hash, expires_at, created_at)
values ('0a0a0a0a-0000-4000-8000-0000000000a1',
        extensions.digest(repeat('e', 64), 'sha256'),
        now() - interval '30 minutes',
        now() - interval '1 hour');

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0c0c0c0c-0000-4000-8000-0000000000c2","role":"authenticated","is_anonymous":true}', true);
select pg_temp.expect_error(
    $$select public.claim_device_enrollment(repeat('e', 64), '1a000000-0000-4000-8000-0000000000f4', 'X', 60, false, false)$$,
    'P0001', 'expired'
);

-- ─── Device: reconcile ───────────────────────────────────────────────────────

select set_config('request.jwt.claims',
    '{"sub":"0a1a1a1a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":true}', true);

create temp table pg_temp.t_reconcile as
    select public.device_reconcile(
        (select id from pg_temp.t_claim),
        1, 0, 1440, true, false, 0, false, true, true, true,
        null::smallint, null::boolean, '2.0.0', 34::smallint, '14', null, null
    ) as result;

do $$
begin
    perform pg_temp.assert_true(
        (select (result->>'server_time') is not null from pg_temp.t_reconcile),
        'reconcile must return server time'
    );
    perform pg_temp.assert_true(
        (select (result->'settings'->>'global_revision')::bigint = 1 from pg_temp.t_reconcile),
        'reconcile must return desired settings revision'
    );
    perform pg_temp.assert_true(
        (select (result->'settings'->>'lock_timer_minutes')::integer = 1440 from pg_temp.t_reconcile),
        'reconcile must return desired lock timer'
    );
    perform pg_temp.assert_true(
        (select result->'commands' = '[]'::jsonb from pg_temp.t_reconcile),
        'no commands expected before any action'
    );
end;
$$;

do $$
begin
    perform public.device_reconcile(
        (select id from pg_temp.t_claim),
        1, 0, 1440, true, false, 120, true, true, true, true,
        73::smallint, false, '2.0.0', 34::smallint, '14', null, null
    );
end;
$$;

reset role;

do $$
begin
    perform pg_temp.assert_true(
        (select count(*) from public.device_status where device_id = (select id from pg_temp.t_claim)) = 1,
        'status must be a single upserted row'
    );
    perform pg_temp.assert_true(
        (select timer_running and timer_remaining_seconds = 120
           from public.device_status where device_id = (select id from pg_temp.t_claim)),
        'status must reflect the latest reconcile values'
    );
end;
$$;

-- ─── Owner: settings revision ────────────────────────────────────────────────

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);

select pg_temp.expect_error(
    $$select public.update_account_settings(99, 2000, true, false)$$,
    'P0001', 'revision conflict'
);

do $$
declare
    settings public.account_settings;
begin
    settings := public.update_account_settings(1, 2000, true, false);
    perform pg_temp.assert_true(settings.revision = 2, 'revision must increment exactly once');
    perform pg_temp.assert_true(settings.lock_timer_minutes = 2000, 'lock timer must update');
end;
$$;

select pg_temp.expect_error(
    $$select public.update_account_settings(1, 3000, false, false)$$,
    'P0001', 'revision conflict'
);

-- ─── Owner: actions and idempotency ──────────────────────────────────────────

create temp table pg_temp.t_act_reset as
    select public.create_account_action('reset_timer', 'dd000000-0000-4000-8000-0000000000e1', 'all', '{}'::uuid[]) as result;

do $$
declare
    duplicate jsonb;
begin
    perform pg_temp.assert_true(
        (select (result->>'device_count')::integer = 1 from pg_temp.t_act_reset),
        'action must target the single active device'
    );
    perform pg_temp.assert_true(
        (select (result->>'expires_at')::timestamptz - (result->>'created_at')::timestamptz
                between interval '23 hours 59 minutes' and interval '24 hours 1 minute'
           from pg_temp.t_act_reset),
        'reset_timer TTL must be 24 hours'
    );
    duplicate := public.create_account_action('reset_timer', 'dd000000-0000-4000-8000-0000000000e1', 'all', '{}'::uuid[]);
    perform pg_temp.assert_true(
        (duplicate->>'action_id') = (select result->>'action_id' from pg_temp.t_act_reset),
        'duplicate key must return the original action'
    );
    perform pg_temp.assert_true(
        (duplicate->>'device_count')::integer = 1,
        'duplicate key must not create additional commands'
    );

    perform pg_temp.expect_error(
        $sql$select public.create_account_action('force_reset', 'dd000000-0000-4000-8000-0000000000e1', 'all', '{}'::uuid[])$sql$,
        'P0001', 'idempotency key reused'
    );
end;
$$;

select pg_temp.expect_error(
    $$select public.create_account_action('unsupported_type', 'dd000000-0000-4000-8000-0000000000ef', 'all', '{}'::uuid[])$$,
    'P0001', 'unsupported action type'
);

-- ─── Device: command transitions ─────────────────────────────────────────────

select set_config('request.jwt.claims',
    '{"sub":"0a1a1a1a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":true}', true);

do $$
declare
    device_id uuid := (select id from pg_temp.t_claim);
    reset_cmd uuid;
    command public.device_commands;
begin
    select c.id into reset_cmd
      from public.device_commands c
     where c.command_type = 'reset_timer'
     order by c.created_at limit 1;

    perform pg_temp.assert_true(reset_cmd is not null,
        'reset command must be visible to the device');

    command := public.device_transition_command(device_id, reset_cmd, 'queued', 'received');
    perform pg_temp.assert_true(command.state = 'received' and command.received_at is not null,
        'receive must stamp received_at');

    perform pg_temp.expect_error(
        format($sql$select public.device_transition_command(%L, %L, 'queued', 'received')$sql$, device_id, reset_cmd),
        'P0001', 'state conflict');

    command := public.device_transition_command(device_id, reset_cmd, 'received', 'executing');
    perform pg_temp.assert_true(command.executing_at is not null, 'executing must stamp executing_at');

    command := public.device_transition_command(device_id, reset_cmd, 'executing', 'applied');
    perform pg_temp.assert_true(command.state = 'applied' and command.finished_at is not null,
        'applied must be terminal with finished_at');

    perform pg_temp.expect_error(
        format($sql$select public.device_transition_command(%L, %L, 'applied', 'received')$sql$, device_id, reset_cmd),
        'P0001', 'illegal transition');

end;
$$;

reset role;

do $$
declare
    reset_cmd uuid := (select id from public.device_commands where command_type = 'reset_timer' order by created_at limit 1);
begin
    perform pg_temp.assert_true(
        (select count(*) from public.command_events where command_id = reset_cmd) = 4,
        'reset_timer must have exactly queued/received/executing/applied events'
    );
end;
$$;

-- ─── Owner: remote debug mode toggle ─────────────────────────────────────────

-- second active device in the same account to prove the toggle never fans out
insert into auth.users (instance_id, id, aud, role, created_at, updated_at, is_anonymous)
values ('00000000-0000-0000-0000-000000000000', '0d0d0d0d-0000-4000-8000-0000000000d8',
        'authenticated', 'authenticated', now(), now(), true);

insert into public.devices (id, account_id, auth_user_id, installation_id, name)
values ('dd000000-0000-4000-8000-0000000000dd', '0a0a0a0a-0000-4000-8000-0000000000a1',
        '0d0d0d0d-0000-4000-8000-0000000000d8', '2d000000-0000-4000-8000-0000000000dd', 'Bystander');

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);

do $$
declare
    target_device uuid := (select id from pg_temp.t_claim);
    on_result jsonb;
    off_result jsonb;
    replay jsonb;
begin
    on_result := public.set_device_debug_mode(target_device, true, 'dd000000-0000-4000-8000-0000000000f1');
    perform pg_temp.assert_true(
        (on_result->>'device_count')::integer = 1,
        'debug enable must target exactly one device');
    perform pg_temp.assert_true(
        (on_result->>'expires_at')::timestamptz - (on_result->>'created_at')::timestamptz
            between interval '23 hours 59 minutes' and interval '24 hours 1 minute',
        'debug_mode_on TTL must be 24 hours');

    replay := public.set_device_debug_mode(target_device, true, 'dd000000-0000-4000-8000-0000000000f1');
    perform pg_temp.assert_true(
        (replay->>'action_id') = (on_result->>'action_id'),
        'replaying the same key must return the original action');
    perform pg_temp.assert_true(
        (replay->>'device_count')::integer = 1,
        'replay must not create additional commands');

    off_result := public.set_device_debug_mode(target_device, false, 'dd000000-0000-4000-8000-0000000000f2');
    perform pg_temp.assert_true(
        (off_result->>'expires_at')::timestamptz - (off_result->>'created_at')::timestamptz
            between interval '9 minutes 59 seconds' and interval '10 minutes 1 second',
        'debug_mode_off TTL must be 10 minutes');

    perform pg_temp.expect_error(
        format($sql$select public.set_device_debug_mode(%L, false, 'dd000000-0000-4000-8000-0000000000f1')$sql$, target_device),
        'P0001', 'idempotency key reused');
    perform pg_temp.expect_error(
        format($sql$select public.set_device_debug_mode(%L, true, 'dd000000-0000-4000-8000-0000000000e1')$sql$, target_device),
        'P0001', 'idempotency key reused');
    perform pg_temp.expect_error(
        $sql$select public.set_device_debug_mode('ffffffff-0000-4000-8000-0000000000ff', true, 'dd000000-0000-4000-8000-0000000000f3')$sql$,
        'P0001', 'device not found');

    -- account-wide actions cannot create debug commands
    perform pg_temp.expect_error(
        $sql$select public.create_account_action('debug_mode_on', 'dd000000-0000-4000-8000-0000000000f4', 'all', '{}'::uuid[])$sql$,
        'P0001', 'unsupported action type');
end;
$$;

reset role;

do $$
declare
    target_device uuid := (select id from pg_temp.t_claim);
    on_action uuid := (select id from public.account_actions
                        where idempotency_key = 'dd000000-0000-4000-8000-0000000000f1');
    off_action uuid := (select id from public.account_actions
                         where idempotency_key = 'dd000000-0000-4000-8000-0000000000f2');
begin
    perform pg_temp.assert_true(
        (select count(*) from public.device_commands where action_id = on_action) = 1,
        'debug enable must create exactly one command');
    perform pg_temp.assert_true(
        (select count(*) from public.device_commands where action_id = off_action) = 1,
        'debug disable must create exactly one command');
    perform pg_temp.assert_true(
        (select device_id = target_device from public.device_commands where action_id = on_action),
        'debug command must target the requested device');
    perform pg_temp.assert_true(
        (select count(*) from public.command_events e
           join public.device_commands c on c.id = e.command_id
          where c.action_id = on_action and e.event_type = 'queued') = 1,
        'debug command must record exactly one queued event');
    perform pg_temp.assert_true(
        (select t.device_id = target_device
           from public.account_action_targets t
          where t.action_id = on_action),
        'debug action must record the targeted device');
    perform pg_temp.assert_true(
        not exists (
            select 1 from public.device_commands c
             where c.device_id = 'dd000000-0000-4000-8000-0000000000dd'
               and c.command_type in ('debug_mode_on', 'debug_mode_off')),
        'debug commands must not fan out to other devices');

    delete from public.devices where id = 'dd000000-0000-4000-8000-0000000000dd';
end;
$$;

-- a queued force reset must be superseded before the guard is removed
set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);

do $$
begin
    perform public.create_account_action('force_reset', 'dd000000-0000-4000-8000-0000000000f5', 'all', '{}'::uuid[]);
end;
$$;

reset role;

do $$
declare
    target_device uuid := (select id from pg_temp.t_claim);
    pending_force uuid;
begin
    select c.id into pending_force
      from public.device_commands c
     where c.device_id = target_device
       and c.command_type = 'force_reset'
       and c.state in ('queued', 'received')
     order by c.created_at desc
     limit 1;
    perform pg_temp.assert_true(pending_force is not null,
        'a fresh force reset must be queued before the supersede test');
end;
$$;

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);

do $$
begin
    perform public.set_device_debug_mode(
        (select id from pg_temp.t_claim), false, 'dd000000-0000-4000-8000-0000000000f6');
end;
$$;

reset role;

do $$
declare
    target_device uuid := (select id from pg_temp.t_claim);
    force_cmd uuid := (select c.id from public.device_commands c
                         join public.account_actions a on a.id = c.action_id
                        where a.idempotency_key = 'dd000000-0000-4000-8000-0000000000f5');
    off_action uuid := (select id from public.account_actions
                         where idempotency_key = 'dd000000-0000-4000-8000-0000000000f6');
begin
    perform pg_temp.assert_true(
        (select state = 'expired' and result_code = 'superseded_by_debug_off'
           from public.device_commands
          where id = force_cmd),
        'pending force reset must be superseded by debug_mode_off');
    perform pg_temp.assert_true(
        (select count(*) from public.command_events e
          where e.command_id = force_cmd
            and e.event_type = 'expired'
            and e.result_code = 'superseded_by_debug_off') = 1,
        'supersede must record exactly one expired event');
    perform pg_temp.assert_true(
        (select exists (
            select 1 from public.device_commands c
             where c.action_id = off_action and c.state = 'queued')),
        'the OFF command itself must stay queued');
end;
$$;

-- device execution path for debug commands

-- captured as transaction-local settings: the device role cannot read owner-only tables
select set_config('wasted.debug_on_cmd',
                  (select c.id::text from public.device_commands c
                     join public.account_actions a on a.id = c.action_id
                    where a.idempotency_key = 'dd000000-0000-4000-8000-0000000000f1'), true),
       set_config('wasted.debug_off_cmd',
                  (select c.id::text from public.device_commands c
                     join public.account_actions a on a.id = c.action_id
                    where a.idempotency_key = 'dd000000-0000-4000-8000-0000000000f6'), true);

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a1a1a1a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":true}', true);

do $$
declare
    target_device uuid := (select id from pg_temp.t_claim);
    on_cmd uuid := current_setting('wasted.debug_on_cmd')::uuid;
    off_cmd uuid := current_setting('wasted.debug_off_cmd')::uuid;
    command public.device_commands;
begin
    command := public.device_transition_command(target_device, on_cmd, 'queued', 'received');

    -- legal matrix but illegal terminal state for debug commands
    perform pg_temp.expect_error(
        format($sql$select public.device_transition_command(%L, %L, 'received', 'debug_blocked')$sql$, target_device, on_cmd),
        'P0001', 'illegal terminal state');

    command := public.device_transition_command(target_device, on_cmd, 'received', 'executing');
    perform pg_temp.assert_true(command.executing_at is not null,
        'debug enable must stamp executing_at');

    perform pg_temp.expect_error(
        format($sql$select public.device_transition_command(%L, %L, 'executing', 'reset_initiated')$sql$, target_device, on_cmd),
        'P0001', 'illegal terminal state');

    command := public.device_transition_command(target_device, on_cmd, 'executing', 'applied', 'enabled');
    perform pg_temp.assert_true(command.state = 'applied' and command.result_code = 'enabled',
        'debug enable must apply with a result code');

    command := public.device_transition_command(target_device, off_cmd, 'queued', 'received');
    command := public.device_transition_command(target_device, off_cmd, 'received', 'executing');
    command := public.device_transition_command(target_device, off_cmd, 'executing', 'applied', 'disabled');
    perform pg_temp.assert_true(command.state = 'applied' and command.result_code = 'disabled',
        'debug disable must apply once');
end;
$$;

reset role;

do $$
declare
    on_cmd uuid := (select c.id from public.device_commands c
                      join public.account_actions a on a.id = c.action_id
                     where a.idempotency_key = 'dd000000-0000-4000-8000-0000000000f1');
    off_cmd uuid := (select c.id from public.device_commands c
                       join public.account_actions a on a.id = c.action_id
                      where a.idempotency_key = 'dd000000-0000-4000-8000-0000000000f6');
begin
    perform pg_temp.assert_true(
        (select count(*) from public.command_events where command_id = on_cmd) = 4,
        'debug enable must record exactly queued/received/executing/applied events');
    perform pg_temp.assert_true(
        (select count(*) from public.command_events where command_id = off_cmd) = 4,
        'debug disable must record exactly queued/received/executing/applied events');
end;
$$;

-- ─── Expiry: device-lazy and owner-triggered ─────────────────────────────────

reset role;

-- overdue fixtures are inserted directly: updating expires_at would violate the
-- command/action consistency trigger, and now() is transaction-fixed
insert into public.account_actions (id, account_id, action_type, idempotency_key, target_scope, created_at, expires_at)
values ('ba000000-0000-4000-8000-0000000000e3', '0a0a0a0a-0000-4000-8000-0000000000a1',
        'reset_timer', 'dd000000-0000-4000-8000-0000000000e3', 'selected',
        now() - interval '26 hours', now() - interval '2 hours');

insert into public.account_action_targets (action_id, account_id, device_id)
values ('ba000000-0000-4000-8000-0000000000e3', '0a0a0a0a-0000-4000-8000-0000000000a1',
        (select id from pg_temp.t_claim));

insert into public.device_commands (id, action_id, device_id, account_id, command_type, created_at, expires_at)
values ('ee000000-0000-4000-8000-0000000000e3', 'ba000000-0000-4000-8000-0000000000e3',
        (select id from pg_temp.t_claim), '0a0a0a0a-0000-4000-8000-0000000000a1',
        'reset_timer', now() - interval '26 hours', now() - interval '2 hours');

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a1a1a1a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":true}', true);

do $$
declare
    device_id uuid := (select id from pg_temp.t_claim);
    overdue_cmd uuid;
begin
    select c.id into overdue_cmd
      from public.device_commands c
     where c.state = 'queued' and c.expires_at <= now()
     order by c.created_at limit 1;

    perform pg_temp.expect_error(
        format($sql$select public.device_transition_command(%L, %L, 'queued', 'received')$sql$, device_id, overdue_cmd),
        'P0001', 'expired');

    -- reconcile lazily expires the overdue command and stops returning it
    perform public.device_reconcile(
        device_id, 2, 0, 2000, true, false, 0, false, true, true, true,
        null::smallint, null::boolean, '2.0.0', 34::smallint, '14', null, null);
end;
$$;

reset role;

do $$
begin
    perform pg_temp.assert_true(
        (select state = 'expired' and result_code = 'expired'
           from public.device_commands
          where action_id = (select id from public.account_actions
                              where idempotency_key = 'dd000000-0000-4000-8000-0000000000e3')),
        'reconcile must lazily expire overdue commands'
    );
    perform pg_temp.assert_true(
        (select count(*) from public.command_events
          where command_id = (select id from public.device_commands
                               where action_id = (select id from public.account_actions
                                                   where idempotency_key = 'dd000000-0000-4000-8000-0000000000e3'))
            and event_type = 'expired') = 1,
        'lazy expiry must append exactly one event'
    );
end;
$$;

insert into public.account_actions (id, account_id, action_type, idempotency_key, target_scope, created_at, expires_at)
values ('ba000000-0000-4000-8000-0000000000e5', '0a0a0a0a-0000-4000-8000-0000000000a1',
        'reset_timer', 'dd000000-0000-4000-8000-0000000000e5', 'selected',
        now() - interval '26 hours', now() - interval '2 hours');

insert into public.account_action_targets (action_id, account_id, device_id)
values ('ba000000-0000-4000-8000-0000000000e5', '0a0a0a0a-0000-4000-8000-0000000000a1',
        (select id from pg_temp.t_claim));

insert into public.device_commands (id, action_id, device_id, account_id, command_type, created_at, expires_at)
values ('ee000000-0000-4000-8000-0000000000e5', 'ba000000-0000-4000-8000-0000000000e5',
        (select id from pg_temp.t_claim), '0a0a0a0a-0000-4000-8000-0000000000a1',
        'reset_timer', now() - interval '26 hours', now() - interval '2 hours');

-- owner-triggered expiry for the remaining overdue command
set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);

do $$
declare
    expired integer;
begin
    expired := public.expire_account_commands();
    perform pg_temp.assert_true(expired >= 1, 'owner expiry must transition the remaining overdue command');
end;
$$;

reset role;

do $$
begin
    perform pg_temp.assert_true(
        (select state = 'expired'
           from public.device_commands
          where action_id = (select id from public.account_actions
                              where idempotency_key = 'dd000000-0000-4000-8000-0000000000e5')),
        'owner-triggered expiry must mark the command expired'
    );
end;
$$;

-- ─── Revocation and clean re-enrollment ──────────────────────────────────────

-- executing command fixture: revocation must terminalize it with an unknown outcome
insert into public.account_actions (id, account_id, action_type, idempotency_key, target_scope, expires_at)
values ('ba000000-0000-4000-8000-0000000000e6', '0a0a0a0a-0000-4000-8000-0000000000a1',
        'reset_timer', 'dd000000-0000-4000-8000-0000000000e6', 'selected', now() + interval '24 hours');

insert into public.account_action_targets (action_id, account_id, device_id)
values ('ba000000-0000-4000-8000-0000000000e6', '0a0a0a0a-0000-4000-8000-0000000000a1',
        (select id from pg_temp.t_claim));

insert into public.device_commands
    (id, action_id, device_id, account_id, command_type, state, expires_at, received_at, executing_at)
values ('ee000000-0000-4000-8000-0000000000e6', 'ba000000-0000-4000-8000-0000000000e6',
        (select id from pg_temp.t_claim), '0a0a0a0a-0000-4000-8000-0000000000a1',
        'reset_timer', 'executing', now() + interval '24 hours', now(), now());

insert into public.command_events (command_id, device_id, account_id, event_type) values
    ('ee000000-0000-4000-8000-0000000000e6', (select id from pg_temp.t_claim),
     '0a0a0a0a-0000-4000-8000-0000000000a1', 'queued'),
    ('ee000000-0000-4000-8000-0000000000e6', (select id from pg_temp.t_claim),
     '0a0a0a0a-0000-4000-8000-0000000000a1', 'received'),
    ('ee000000-0000-4000-8000-0000000000e6', (select id from pg_temp.t_claim),
     '0a0a0a0a-0000-4000-8000-0000000000a1', 'executing');

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);

-- a fresh queued command must be rejected by revocation
do $$
begin
    perform (select public.create_account_action('reset_timer', 'dd000000-0000-4000-8000-0000000000e4', 'all', '{}'::uuid[]));
end;
$$;

do $$
declare
    device_id uuid := (select id from pg_temp.t_claim);
begin
    perform public.revoke_device(device_id);
    -- idempotent: revoking again is a no-op
    perform public.revoke_device(device_id);
end;
$$;

reset role;

do $$
declare
    device_id uuid := (select id from pg_temp.t_claim);
begin
    perform pg_temp.assert_true(
        (select revoked_at is not null from public.devices where id = device_id),
        'revocation must be server-stamped'
    );
    perform pg_temp.assert_true(
        (select state = 'rejected' and result_code = 'device_revoked'
           from public.device_commands
          where action_id = (select id from public.account_actions
                              where idempotency_key = 'dd000000-0000-4000-8000-0000000000e4')),
        'queued commands must be rejected on revocation'
    );
    perform pg_temp.assert_true(
        (select state = 'expired'
           from public.device_commands
          where action_id = (select id from public.account_actions
                              where idempotency_key = 'dd000000-0000-4000-8000-0000000000e3')),
        'terminal commands must stay untouched by revocation'
    );
    perform pg_temp.assert_true(
        (select state = 'failed' and result_code = 'device_revoked_during_execution'
                and finished_at is not null
           from public.device_commands
          where id = 'ee000000-0000-4000-8000-0000000000e6'),
        'executing commands must be terminalized on revocation'
    );
    perform pg_temp.assert_true(
        (select count(*) from public.command_events
          where command_id = 'ee000000-0000-4000-8000-0000000000e6'
            and event_type = 'failed'
            and result_code = 'device_revoked_during_execution') = 1,
        'revocation must record exactly one unknown-outcome event'
    );
end;
$$;

-- revoked device can no longer reconcile or see anything
set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a1a1a1a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":true}', true);

select pg_temp.expect_error(
    format($sql$select public.device_reconcile(%L, 2, 0, 2000, true, false, 0, false, true, true, true, null::smallint, null::boolean, '2.0.0', 34::smallint, '14', null, null)$sql$,
           (select id from pg_temp.t_claim)),
    'P0001', 'active device not found'
);

do $$
begin
    perform pg_temp.assert_true(
        not exists (select 1 from public.devices),
        'revoked device must see no device rows'
    );
end;
$$;

-- clean re-enrollment after revocation with a fresh identity + token
reset role;
insert into auth.users (instance_id, id, aud, role, created_at, updated_at, is_anonymous)
values ('00000000-0000-0000-0000-000000000000', '0d0d0d0d-0000-4000-8000-0000000000d9',
        'authenticated', 'authenticated', now(), now(), true);

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);

create temp table pg_temp.t_enroll_third as
    select now() as at, e.* from public.create_device_enrollment() e;

select set_config('request.jwt.claims',
    '{"sub":"0d0d0d0d-0000-4000-8000-0000000000d9","role":"authenticated","is_anonymous":true}', true);

do $$
declare
    new_device public.devices;
begin
    new_device := public.claim_device_enrollment(
        (select enrollment_token from pg_temp.t_enroll_third),
        '1d000000-0000-4000-8000-0000000000f9',
        'Replacement Device',
        2000,
        true,
        false
    );
    perform pg_temp.assert_true(new_device.revoked_at is null, 'replacement device must be active');
end;
$$;

reset role;

do $$
begin
    perform pg_temp.assert_true(
        (select count(*) from public.devices where account_id = '0a0a0a0a-0000-4000-8000-0000000000a1') = 2,
        'account must hold the revoked device and the replacement device'
    );
    perform pg_temp.assert_true(
        (select revision = 2 and lock_timer_minutes = 2000
           from public.account_settings where account_id = '0a0a0a0a-0000-4000-8000-0000000000a1'),
        'initialized settings must not be overwritten by later claims'
    );
end;
$$;

-- ─── Device: session recovery (no new enrollment token) ─────────────────────

-- Capture the target id as superuser: the replacement identity cannot read the row
-- before recovery, which is exactly what recovery is meant to fix.
reset role;
select set_config(
    'test.recovery_device_id',
    (select id::text from public.devices
      where installation_id = '1d000000-0000-4000-8000-0000000000f9'),
    false
);

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0c0c0c0c-0000-4000-8000-0000000000c1","role":"authenticated","is_anonymous":true}', true);

create temp table pg_temp.t_recovery as
    select d.* from public.recover_device_session(
        current_setting('test.recovery_device_id')::uuid,
        '1d000000-0000-4000-8000-0000000000f9'
    ) d;

do $$
begin
    perform pg_temp.assert_true(
        (select auth_user_id = '0c0c0c0c-0000-4000-8000-0000000000c1' from pg_temp.t_recovery),
        'recovery must rebind the same device to the new anonymous identity'
    );
    perform pg_temp.assert_true(
        (select id::text = current_setting('test.recovery_device_id') from pg_temp.t_recovery),
        'recovery must keep the original device row'
    );
end;
$$;

select pg_temp.expect_error(
    $$select public.recover_device_session(
        (select id from pg_temp.t_recovery),
        '1d000000-0000-4000-8000-0000000000ff')$$,
    'P0001', 'device not found'
);

select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);
select pg_temp.expect_error(
    $$select public.recover_device_session(
        (select id from pg_temp.t_recovery),
        '1d000000-0000-4000-8000-0000000000f9')$$,
    'P0001', 'anonymous device identity'
);

select set_config('request.jwt.claims',
    '{"sub":"0a1a1a1a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":true}', true);
select pg_temp.expect_error(
    $$select public.recover_device_session(
        (select id from pg_temp.t_claim),
        '1a000000-0000-4000-8000-0000000000f1')$$,
    'P0001', 'device revoked'
);

-- legacy repair terminalizes executing commands on already-revoked devices, idempotently
reset role;
insert into public.devices (id, account_id, installation_id, name, revoked_at)
values ('ef000000-0000-4000-8000-0000000000ef', '0a0a0a0a-0000-4000-8000-0000000000a1',
        '8f000000-0000-4000-8000-0000000000ef', 'Legacy Revoked', now());
insert into public.account_actions (id, account_id, action_type, idempotency_key, target_scope, expires_at)
values ('ea000000-0000-4000-8000-0000000000ef', '0a0a0a0a-0000-4000-8000-0000000000a1',
        'force_reset', 'ed000000-0000-4000-8000-0000000000ef', 'selected', now() + interval '1 hour');

insert into public.account_action_targets (action_id, account_id, device_id)
values ('ea000000-0000-4000-8000-0000000000ef', '0a0a0a0a-0000-4000-8000-0000000000a1',
        'ef000000-0000-4000-8000-0000000000ef');

insert into public.device_commands
    (id, action_id, device_id, account_id, command_type, state, expires_at, executing_at)
values ('ec000000-0000-4000-8000-0000000000ef', 'ea000000-0000-4000-8000-0000000000ef',
        'ef000000-0000-4000-8000-0000000000ef', '0a0a0a0a-0000-4000-8000-0000000000a1',
        'force_reset', 'executing', now() + interval '1 hour', now());

do $$
declare
    first_repair integer;
    second_repair integer;
begin
    first_repair := private.repair_legacy_revoked_commands();
    second_repair := private.repair_legacy_revoked_commands();
    perform pg_temp.assert_true(first_repair = 1, 'legacy repair must terminalize one command');
    perform pg_temp.assert_true(second_repair = 0, 'legacy repair must be idempotent');
    perform pg_temp.assert_true(
        (select state = 'failed' and result_code = 'device_revoked_during_execution'
           from public.device_commands where id = 'ec000000-0000-4000-8000-0000000000ef'),
        'legacy command must be failed with an unknown-outcome result'
    );
    perform pg_temp.assert_true(
        (select count(*) from public.command_events
          where command_id = 'ec000000-0000-4000-8000-0000000000ef' and event_type = 'failed') = 1,
        'legacy repair must insert exactly one failed audit event'
    );
end;
$$;

rollback;
