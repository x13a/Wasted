-- Free-plan maintenance tests: latest-command RPC scope and bounded retention.
-- Wrapped in a rolled-back transaction; run via Supabase MCP after all migrations.

begin;

-- Legacy lifecycle suites exercise individual commands; the latest-command behavior is
-- covered end-to-end by remote_device_settings_targets_test.sql.
alter table public.device_commands disable trigger device_commands_supersede_pending;

insert into auth.users (instance_id, id, aud, role, created_at, updated_at, is_anonymous)
values ('00000000-0000-0000-0000-000000000000',
        '0f0f0f0f-0000-4000-8000-0000000000f1', 'authenticated', 'authenticated',
        now(), now(), false);

insert into public.accounts (id, handle)
values ('0f0f0f0f-0000-4000-8000-0000000000f1', 'MaintenanceOwner');

insert into public.devices (id, account_id, auth_user_id, installation_id, name, revoked_at)
values ('f1000000-0000-4000-8000-000000000001',
        '0f0f0f0f-0000-4000-8000-0000000000f1', null,
        'f1000000-0000-4000-8000-000000000002', 'Maintenance device', now());

insert into public.account_actions (id, account_id, action_type, idempotency_key, target_scope, created_at, expires_at)
values
    ('f1000000-0000-4000-8000-000000000011', '0f0f0f0f-0000-4000-8000-0000000000f1',
     'reset_timer', 'f1000000-0000-4000-8000-000000000012', 'selected', now() - interval '2 hours', now() + interval '22 hours'),
    ('f1000000-0000-4000-8000-000000000013', '0f0f0f0f-0000-4000-8000-0000000000f1',
     'reset_timer', 'f1000000-0000-4000-8000-000000000014', 'selected', now() - interval '1 hour', now() + interval '23 hours');

insert into public.account_action_targets (action_id, account_id, device_id)
values
    ('f1000000-0000-4000-8000-000000000011', '0f0f0f0f-0000-4000-8000-0000000000f1',
     'f1000000-0000-4000-8000-000000000001'),
    ('f1000000-0000-4000-8000-000000000013', '0f0f0f0f-0000-4000-8000-0000000000f1',
     'f1000000-0000-4000-8000-000000000001');

insert into public.device_commands (id, action_id, device_id, account_id, command_type, created_at, expires_at)
values
    ('f1000000-0000-4000-8000-000000000021', 'f1000000-0000-4000-8000-000000000011',
     'f1000000-0000-4000-8000-000000000001', '0f0f0f0f-0000-4000-8000-0000000000f1',
     'reset_timer', now() - interval '2 hours', now() + interval '22 hours'),
    ('f1000000-0000-4000-8000-000000000022', 'f1000000-0000-4000-8000-000000000013',
     'f1000000-0000-4000-8000-000000000001', '0f0f0f0f-0000-4000-8000-0000000000f1',
     'reset_timer', now() - interval '1 hour', now() + interval '23 hours');

set local role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"0f0f0f0f-0000-4000-8000-0000000000f1","role":"authenticated","is_anonymous":false}',
    true
);

do $$
declare
    rows_returned integer;
    latest_id uuid;
begin
    select count(*), (array_agg(id))[1] into rows_returned, latest_id
      from public.list_latest_device_commands();
    if rows_returned <> 1 then
        raise exception 'latest command RPC returned %, expected 1', rows_returned;
    end if;
    if latest_id <> 'f1000000-0000-4000-8000-000000000022' then
        raise exception 'latest command RPC returned the wrong command';
    end if;
end;
$$;
reset role;

insert into public.account_actions (id, account_id, action_type, idempotency_key, target_scope, created_at, expires_at)
values ('f1000000-0000-4000-8000-000000000031', '0f0f0f0f-0000-4000-8000-0000000000f1',
        'reset_timer', 'f1000000-0000-4000-8000-000000000032', 'selected',
        now() - interval '200 days', now() - interval '199 days');

insert into private.device_enrollments (account_id, token_hash, created_at, expires_at)
values ('0f0f0f0f-0000-4000-8000-0000000000f1',
        extensions.digest('maintenance-retention-token', 'sha256'),
        now() - interval '40 days', now() - interval '39 days');

select private.cleanup_remote_history();

do $$
begin
    if exists (select 1 from public.account_actions
                where id = 'f1000000-0000-4000-8000-000000000031') then
        raise exception 'old account action was not removed';
    end if;
    if exists (select 1 from private.device_enrollments
                where account_id = '0f0f0f0f-0000-4000-8000-0000000000f1'
                  and created_at < now() - interval '30 days') then
        raise exception 'old enrollment was not removed';
    end if;
    if not exists (select 1 from public.account_actions
                    where id = 'f1000000-0000-4000-8000-000000000013') then
        raise exception 'recent action was removed';
    end if;
    if has_function_privilege('anon', 'public.list_latest_device_commands()', 'EXECUTE') then
        raise exception 'anon can execute latest command RPC';
    end if;
end;
$$;

rollback;
