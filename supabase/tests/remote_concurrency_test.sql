-- Concurrency-outcome tests: deterministic proofs of the race outcomes that the RPC's row locks
-- and unique constraints guarantee (single winner for first-enrollment seeding and revision
-- updates, idempotent actions/transitions, single enrollment consumption, clean re-enrollment).
-- True parallel interleavings run in the Phase 4 two-device soak; every outcome locked in here
-- is exactly what those parallel runs must produce. Rolled-back transaction; run via MCP.

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
    ('00000000-0000-0000-0000-000000000000', '0a1a1a1a-0000-4000-8000-0000000000a1', 'authenticated', 'authenticated', now(), now(), true),
    ('00000000-0000-0000-0000-000000000000', '0a2a2a2a-0000-4000-8000-0000000000a2', 'authenticated', 'authenticated', now(), now(), true),
    ('00000000-0000-0000-0000-000000000000', '0c0c0c0c-0000-4000-8000-0000000000c1', 'authenticated', 'authenticated', now(), now(), true);

insert into public.accounts (id, handle)
values ('0a0a0a0a-0000-4000-8000-0000000000a1', 'ConcurrencyOwner');
insert into public.account_settings (account_id) values ('0a0a0a0a-0000-4000-8000-0000000000a1');

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);

-- ─── First-enrollment seeding: exactly one winner ────────────────────────────

create temp table pg_temp.t_enroll_1 as
    select e.* from public.create_device_enrollment() e;

select set_config('request.jwt.claims',
    '{"sub":"0a1a1a1a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":true}', true);

create temp table pg_temp.t_claim_1 as
    select d.* from public.claim_device_enrollment(
        (select enrollment_token from pg_temp.t_enroll_1),
        '2a000000-0000-4000-8000-0000000000f1', 'Device One', 1440, true, false) d;

select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);

create temp table pg_temp.t_enroll_2 as
    select e.* from public.create_device_enrollment() e;

select set_config('request.jwt.claims',
    '{"sub":"0a2a2a2a-0000-4000-8000-0000000000a2","role":"authenticated","is_anonymous":true}', true);

create temp table pg_temp.t_claim_2 as
    select d.* from public.claim_device_enrollment(
        (select enrollment_token from pg_temp.t_enroll_2),
        '2a000000-0000-4000-8000-0000000000f2', 'Device Two', 60, false, false) d;

reset role;

do $$
begin
    perform pg_temp.assert_true(
        (select revision = 1 and lock_timer_minutes = 1440 and usb_trigger_enabled = true
           from public.account_settings where account_id = '0a0a0a0a-0000-4000-8000-0000000000a1'),
        'the first claim wins settings seeding; the second claim must not overwrite it'
    );
    perform pg_temp.assert_true(
        (select count(*) from public.devices where account_id = '0a0a0a0a-0000-4000-8000-0000000000a1') = 2,
        'both devices enroll even though only the first seeds settings'
    );
end;
$$;

-- ─── Revision updates: the stale writer loses ────────────────────────────────

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);

do $$
begin
    perform (select public.update_account_settings(1, 2000, true, false)); -- winner
end;
$$;

select pg_temp.expect_error(
    $$select public.update_account_settings(1, 9999, false, false)$$,
    'P0001', 'revision conflict'
);

reset role;

do $$
begin
    perform pg_temp.assert_true(
        (select revision = 2 and lock_timer_minutes = 2000
           from public.account_settings where account_id = '0a0a0a0a-0000-4000-8000-0000000000a1'),
        'exactly one update must win; the stale writer must leave no trace'
    );
end;
$$;

-- ─── Action idempotency: retry storm creates one action ──────────────────────

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);

do $$
declare
    first jsonb;
    retry jsonb;
begin
    first := public.create_account_action('reset_timer', 'dd000000-0000-4000-8000-0000000000d1', 'all', '{}'::uuid[]);
    retry := public.create_account_action('reset_timer', 'dd000000-0000-4000-8000-0000000000d1', 'all', '{}'::uuid[]);
    perform pg_temp.assert_true(
        (first->>'action_id') = (retry->>'action_id')
            and (first->>'device_count') = (retry->>'device_count'),
        'duplicate idempotency key must return the identical action/device count'
    );
    perform pg_temp.assert_true(
        (first->>'device_count')::integer = 2,
        'action must target both active devices exactly once'
    );
end;
$$;

reset role;

do $$
begin
    perform pg_temp.assert_true(
        (select count(*) from public.account_actions
          where account_id = '0a0a0a0a-0000-4000-8000-0000000000a1') = 1,
        'retries must not create a second action row'
    );
    perform pg_temp.assert_true(
        (select count(*) from public.device_commands c
          join public.account_actions a on a.id = c.action_id
         where a.account_id = '0a0a0a0a-0000-4000-8000-0000000000a1') = 2,
        'exactly one command per active device'
    );
end;
$$;

-- ─── Delivery idempotency: re-reconcile and duplicate transitions ────────────

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a1a1a1a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":true}', true);

create temp table pg_temp.t_rec_1 as
    select public.device_reconcile(
        (select id from pg_temp.t_claim_1), 2, 0, 2000, true, false, 0, false, true, true, true,
        null::smallint, null::boolean, '2.0.0', 34::smallint, '14', null, null) as result;
create temp table pg_temp.t_rec_2 as
    select public.device_reconcile(
        (select id from pg_temp.t_claim_1), 2, 0, 2000, true, false, 0, false, true, true, true,
        null::smallint, null::boolean, '2.0.0', 34::smallint, '14', null, null) as result;

do $$
declare
    command_id uuid;
    command public.device_commands;
begin
    perform pg_temp.assert_true(
        (select jsonb_array_length(result->'commands') = 1 from pg_temp.t_rec_1),
        'one queued command must be delivered'
    );
    perform pg_temp.assert_true(
        (select result->'commands'->0->>'id' from pg_temp.t_rec_1)
            = (select result->'commands'->0->>'id' from pg_temp.t_rec_2),
        're-reconcile must return the same command id, never a duplicate'
    );
    perform pg_temp.assert_true(
        (select jsonb_array_length(result->'commands') = 1 from pg_temp.t_rec_2),
        're-reconcile must return exactly one command'
    );

    command_id := (select result->'commands'->0->>'id' from pg_temp.t_rec_1)::uuid;
    command := public.device_transition_command(
        (select id from pg_temp.t_claim_1), command_id, 'queued', 'received');
    perform pg_temp.assert_true(command.state = 'received', 'first receive must win');

    perform pg_temp.expect_error(
        format($sql$select public.device_transition_command(%L, %L, 'queued', 'received')$sql$,
               (select id from pg_temp.t_claim_1), command_id),
        'P0001', 'state conflict');
end;
$$;

reset role;

-- events for the delivered command must stay at queued + received
do $$
declare
    target_command_id uuid := (select c.id from public.device_commands c
                         join public.account_actions a on a.id = c.action_id
                        where a.account_id = '0a0a0a0a-0000-4000-8000-0000000000a1'
                          and c.device_id = (select id from pg_temp.t_claim_1));
begin
    perform pg_temp.assert_true(
        (select count(*) from public.command_events e where e.command_id = target_command_id) = 2,
        'duplicate transition must not append a second received event'
    );
end;
$$;

-- ─── Enrollment consumption: a consumed token binds one identity ─────────────

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0c0c0c0c-0000-4000-8000-0000000000c1","role":"authenticated","is_anonymous":true}', true);

select pg_temp.expect_error(
    format($$select public.claim_device_enrollment(%L, '2c000000-0000-4000-8000-0000000000f1', 'Late', 60, false, false)$$,
           (select enrollment_token from pg_temp.t_enroll_2)),
    'P0001', 'already used'
);

reset role;

do $$
begin
    perform pg_temp.assert_true(
        (select count(*) from public.devices where account_id = '0a0a0a0a-0000-4000-8000-0000000000a1') = 2,
        'consumed token reuse must not create another device'
    );
end;
$$;

-- ─── Revocation + clean re-enrollment ────────────────────────────────────────

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0a0a0a0a-0000-4000-8000-0000000000a1","role":"authenticated","is_anonymous":false}', true);

do $$
begin
    perform public.revoke_device((select id from pg_temp.t_claim_2));
end;
$$;

create temp table pg_temp.t_enroll_3 as
    select e.* from public.create_device_enrollment() e;

select set_config('request.jwt.claims',
    '{"sub":"0c0c0c0c-0000-4000-8000-0000000000c1","role":"authenticated","is_anonymous":true}', true);

select pg_temp.expect_error(
    format($$select public.claim_device_enrollment(%L, '2a000000-0000-4000-8000-0000000000f2', 'Revived', 60, false, false)$$,
           (select enrollment_token from pg_temp.t_enroll_3)),
    'P0001', 'device was revoked'
);

do $$
declare
    replacement public.devices;
begin
    replacement := public.claim_device_enrollment(
        (select enrollment_token from pg_temp.t_enroll_3),
        '2c000000-0000-4000-8000-0000000000f2', 'Replacement', 2000, true, false);
    perform pg_temp.assert_true(replacement.revoked_at is null, 'replacement device must be active');
end;
$$;

reset role;

do $$
begin
    perform pg_temp.assert_true(
        (select count(*) from public.devices
          where account_id = '0a0a0a0a-0000-4000-8000-0000000000a1' and revoked_at is null) = 2,
        'revoked device frees the identity for a clean re-enrollment'
    );
    perform pg_temp.assert_true(
        (select count(*) from public.devices
          where account_id = '0a0a0a0a-0000-4000-8000-0000000000a1' and revoked_at is not null) = 1,
        'revoked row must be preserved for audit'
    );
end;
$$;

rollback;
