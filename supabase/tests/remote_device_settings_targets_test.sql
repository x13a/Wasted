-- Per-device override precedence, device isolation, targeted commands, and battery reporting.
-- Rolled back; run against the linked project.

begin;

create function pg_temp.assert_true(p_condition boolean, p_message text)
returns void language plpgsql as $$
begin
    if not coalesce(p_condition, false) then
        raise exception 'assertion failed: %', p_message;
    end if;
end;
$$;

create function pg_temp.expect_error(p_sql text, p_state text, p_message_like text)
returns void language plpgsql as $$
begin
    begin
        execute p_sql;
    exception when others then
        if sqlstate <> p_state then
            raise exception 'expected sqlstate %, got % (%) for: %', p_state, sqlstate, sqlerrm, p_sql;
        end if;
        if position(p_message_like in sqlerrm) = 0 then
            raise exception 'expected message containing %, got % for: %', p_message_like, sqlerrm, p_sql;
        end if;
        return;
    end;
    raise exception 'expected an error but the statement succeeded: %', p_sql;
end;
$$;

grant execute on function pg_temp.assert_true(boolean, text) to authenticated;
grant execute on function pg_temp.expect_error(text, text, text) to authenticated;

insert into auth.users (instance_id, id, aud, role, created_at, updated_at, is_anonymous) values
    ('00000000-0000-0000-0000-000000000000', '0e0e0e0e-0000-4000-8000-000000000001', 'authenticated', 'authenticated', now(), now(), false),
    ('00000000-0000-0000-0000-000000000000', '0e1e1e1e-0000-4000-8000-00000000000a', 'authenticated', 'authenticated', now(), now(), true),
    ('00000000-0000-0000-0000-000000000000', '0e2e2e2e-0000-4000-8000-00000000000b', 'authenticated', 'authenticated', now(), now(), true);

insert into public.accounts (id, handle)
values ('0e0e0e0e-0000-4000-8000-000000000001', 'SettingsTargetsOwner');
insert into public.account_settings (account_id, revision, lock_trigger_enabled, lock_timer_minutes, usb_trigger_enabled, initialized_at)
values ('0e0e0e0e-0000-4000-8000-000000000001', 1, true, 1440, true, now());

insert into public.devices (id, account_id, auth_user_id, installation_id, name) values
    ('ea000000-0000-4000-8000-000000000001', '0e0e0e0e-0000-4000-8000-000000000001',
     '0e1e1e1e-0000-4000-8000-00000000000a', '1e000000-0000-4000-8000-00000000000a', 'Device A'),
    ('ea000000-0000-4000-8000-000000000002', '0e0e0e0e-0000-4000-8000-000000000001',
     '0e2e2e2e-0000-4000-8000-00000000000b', '1e000000-0000-4000-8000-00000000000b', 'Device B');

insert into public.device_settings (device_id, account_id, revision, lock_timer_minutes)
values ('ea000000-0000-4000-8000-000000000001', '0e0e0e0e-0000-4000-8000-000000000001', 1, 60);

set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0e0e0e0e-0000-4000-8000-000000000001","role":"authenticated","is_anonymous":false}', true);

create temp table pg_temp.t_effective as
    select * from public.list_device_effective_settings();

do $$
begin
    perform pg_temp.assert_true((select count(*) from pg_temp.t_effective) = 2,
        'owner must see both active devices');
    perform pg_temp.assert_true(
        (select lock_timer_minutes = 60 and lock_timer_overridden
           from pg_temp.t_effective where device_id = 'ea000000-0000-4000-8000-000000000001'),
        'device A must use its 60-minute override');
    perform pg_temp.assert_true(
        (select lock_timer_minutes = 1440 and not lock_timer_overridden
           from pg_temp.t_effective where device_id = 'ea000000-0000-4000-8000-000000000002'),
        'device B must inherit the 1440-minute global value');
end;
$$;

select public.update_device_settings(
    'ea000000-0000-4000-8000-000000000001',
    1,
    '{"lock_timer_minutes":null}'::jsonb
);

do $$
begin
    perform pg_temp.assert_true(
        (select lock_timer_minutes = 1440 and not lock_timer_overridden
           from public.list_device_effective_settings()
          where device_id = 'ea000000-0000-4000-8000-000000000001'),
        'clearing the override must restore the global value');
end;
$$;

select pg_temp.expect_error(
    $$select public.update_device_settings('ea000000-0000-4000-8000-000000000001', 1, '{"lock_timer_minutes":90}'::jsonb)$$,
    'P0001',
    'device settings revision conflict'
);

create temp table pg_temp.t_action as
    select public.create_account_action(
        'lock_now',
        'ed000000-0000-4000-8000-000000000001',
        'selected',
        array['ea000000-0000-4000-8000-000000000001']::uuid[]
    ) as result;

do $$
begin
    perform pg_temp.assert_true(
        (select (result->>'device_count')::integer = 1 from pg_temp.t_action),
        'selected action must target exactly one device');
    perform pg_temp.assert_true(
        (select count(*) from public.account_action_targets
          where action_id = (select (result->>'action_id')::uuid from pg_temp.t_action)
            and device_id = 'ea000000-0000-4000-8000-000000000001') = 1,
        'selected action must persist the explicit target');
    perform pg_temp.assert_true(
        (select count(*) from public.device_commands
          where action_id = (select (result->>'action_id')::uuid from pg_temp.t_action)
            and device_id = 'ea000000-0000-4000-8000-000000000002') = 0,
        'selected action must not fan out to device B');
end;
$$;

select pg_temp.expect_error(
    $$select public.create_account_action('lock_now', 'ed000000-0000-4000-8000-000000000002', 'selected', '{}'::uuid[])$$,
    'P0001',
    'selected targets must not be empty'
);

-- Different command families coexist; only a newer command in the same family supersedes.
create temp table pg_temp.t_reset as
select public.create_account_action(
    'reset_timer',
    'ed000000-0000-4000-8000-000000000003',
    'selected',
    array['ea000000-0000-4000-8000-000000000001']::uuid[]
) as result;

do $$
begin
    perform pg_temp.assert_true(
        (select state = 'queued'
           from public.device_commands
          where action_id = (select (result->>'action_id')::uuid from pg_temp.t_action)),
        'a reset must not supersede an independent lock command');
end;
$$;

select public.create_account_action(
    'lock_now',
    'ed000000-0000-4000-8000-000000000004',
    'selected',
    array['ea000000-0000-4000-8000-000000000001']::uuid[]
);

do $$
begin
    perform pg_temp.assert_true(
        (select state = 'expired' and result_code = 'superseded_by_new_action'
           from public.device_commands
          where action_id = (select (result->>'action_id')::uuid from pg_temp.t_action)),
        'the older command in the same family must be superseded');
    perform pg_temp.assert_true(
        (select state = 'queued'
           from public.device_commands
          where action_id = (select (result->>'action_id')::uuid from pg_temp.t_reset)),
        'the newer reset command must remain pending');
end;
$$;

-- Device A reports battery 0; unknown is a separate null state.
select set_config('request.jwt.claims',
    '{"sub":"0e1e1e1e-0000-4000-8000-00000000000a","role":"authenticated","is_anonymous":true}', true);
select public.device_reconcile(
    'ea000000-0000-4000-8000-000000000001',
    1, 0, 1440, true, true, 0, false, true, true, true,
    0::smallint, false, '3.0.0', 35::smallint, '15', null, null
);

reset role;

do $$
begin
    perform pg_temp.assert_true(
        (select battery_percent = 0 and battery_charging = false
           from public.device_status
          where device_id = 'ea000000-0000-4000-8000-000000000001'),
        'device battery 0 must be stored as a real value');
    perform pg_temp.assert_true(
        (select applied_global_settings_revision = 1
                  and applied_device_settings_revision = 0
           from public.device_status
          where device_id = 'ea000000-0000-4000-8000-000000000001'),
        'reconcile must store both applied revisions');
end;
$$;

rollback;
