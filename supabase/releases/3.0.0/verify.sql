-- Read-only verification for a Wasted Remote database.
-- Run in the Supabase SQL Editor after setup.sql. Every row must say PASS; any FAIL raises.
-- This checks catalog invariants (RLS force, policies, grants, RPC properties, publication,
-- schema marker), not just table names.

drop table if exists pg_temp.verify_results;
drop function if exists pg_temp.verify_function_acl_probe();

-- Effective check: a newly-created function must not inherit PUBLIC/anon/authenticated EXECUTE.
create function pg_temp.verify_function_acl_probe()
returns integer
language sql
as $$ select 1 $$;

create temp table pg_temp.verify_results as
with expected_tables(name) as (
    values ('accounts'), ('devices'), ('account_settings'), ('device_status'),
           ('account_actions'), ('account_action_targets'), ('device_settings'),
           ('device_commands'), ('command_events'), ('device_notes')
),
expected_policies(tablename, policyname, cmd, roles, permissive, qual, with_check) as (
    values
        ('accounts', 'accounts_select_owner', 'SELECT',
         array['authenticated']::name[], 'PERMISSIVE',
         '(id = ( SELECT auth.uid() AS uid))', null::text),
        ('devices', 'devices_select', 'SELECT',
         array['authenticated']::name[], 'PERMISSIVE',
         '((account_id = ( SELECT auth.uid() AS uid)) OR ((auth_user_id = ( SELECT auth.uid() AS uid)) AND (revoked_at IS NULL)))',
         null::text),
        ('account_settings', 'account_settings_select', 'SELECT',
         array['authenticated']::name[], 'PERMISSIVE',
         '((account_id = ( SELECT auth.uid() AS uid)) OR (EXISTS ( SELECT 1 FROM devices d WHERE ((d.auth_user_id = ( SELECT auth.uid() AS uid)) AND (d.account_id = account_settings.account_id) AND (d.revoked_at IS NULL)))))',
         null::text),
        ('device_status', 'device_status_select_owner', 'SELECT',
         array['authenticated']::name[], 'PERMISSIVE',
         '(account_id = ( SELECT auth.uid() AS uid))', null::text),
        ('account_actions', 'account_actions_select_owner', 'SELECT',
         array['authenticated']::name[], 'PERMISSIVE',
         '(account_id = ( SELECT auth.uid() AS uid))', null::text),
        ('device_commands', 'device_commands_select', 'SELECT',
         array['authenticated']::name[], 'PERMISSIVE',
         '((account_id = ( SELECT auth.uid() AS uid)) OR (EXISTS ( SELECT 1 FROM devices d WHERE ((d.id = device_commands.device_id) AND (d.auth_user_id = ( SELECT auth.uid() AS uid)) AND (d.revoked_at IS NULL)))))',
         null::text),
        ('command_events', 'command_events_select_owner', 'SELECT',
         array['authenticated']::name[], 'PERMISSIVE',
         '(account_id = ( SELECT auth.uid() AS uid))', null::text),
        ('device_settings', 'device_settings_select', 'SELECT',
         array['authenticated']::name[], 'PERMISSIVE',
         '((account_id = ( SELECT auth.uid() AS uid)) OR (EXISTS ( SELECT 1 FROM devices d WHERE ((d.id = device_settings.device_id) AND (d.auth_user_id = ( SELECT auth.uid() AS uid)) AND (d.revoked_at IS NULL)))))',
         null::text),
        ('account_action_targets', 'account_action_targets_select_owner', 'SELECT',
         array['authenticated']::name[], 'PERMISSIVE',
         '(account_id = ( SELECT auth.uid() AS uid))', null::text),
        ('device_notes', 'device_notes_select_owner', 'SELECT',
         array['authenticated']::name[], 'PERMISSIVE',
         '(account_id = ( SELECT auth.uid() AS uid))', null::text)
),
actual_policies as (
    select tablename, policyname, cmd, roles, permissive,
           regexp_replace(coalesce(qual, ''), '\s+', ' ', 'g') as qual_norm,
           regexp_replace(coalesce(with_check, ''), '\s+', ' ', 'g') as with_check_norm
      from pg_policies
     where schemaname = 'public'
),
expected_rpcs(signature) as (
    values ('public.initialize_owner_account(text)'),
           ('public.update_owner_handle(text)'),
           ('public.create_device_enrollment()'),
           ('public.rename_device(uuid,text)'),
           ('public.revoke_device(uuid)'),
           ('public.update_account_settings(bigint,integer,boolean,boolean)'),
           ('public.update_account_limits(bigint,integer,integer,integer,integer,integer,integer)'),
           ('public.update_device_notes(uuid,text)'),
           ('public.create_account_action(text,uuid,text,uuid[])'),
           ('public.expire_account_commands()'),
           ('public.claim_device_enrollment(text,uuid,text,integer,boolean,boolean)'),
           ('public.device_reconcile(uuid,bigint,bigint,integer,boolean,boolean,bigint,boolean,boolean,boolean,boolean,smallint,boolean,text,smallint,text,text,text)'),
           ('public.device_transition_command(uuid,uuid,text,text,text,text)'),
           ('public.set_device_debug_mode(uuid,boolean,uuid)'),
           ('public.list_device_effective_settings()'),
           ('public.update_device_settings(uuid,bigint,jsonb)')
),
checks as (
    select 'public tables' as check_name, count(*) as found, 10 as expected
      from expected_tables
     where to_regclass('public.' || name) is not null
    union all
    select 'RLS enabled', count(*), 10
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relname in (select name from expected_tables)
       and c.relrowsecurity
    union all
    select 'RLS forced', count(*), 10
      from pg_class c
      join pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relname in (select name from expected_tables)
       and c.relrowsecurity and c.relforcerowsecurity
    union all
    select 'SELECT-only policies', count(*), 10
      from expected_policies
    union all
    select 'policy set exact', count(*), 0
      from (
          select e.tablename, e.policyname
            from expected_policies e
            left join actual_policies a
              on a.tablename = e.tablename and a.policyname = e.policyname
           where a.policyname is null
              or a.cmd is distinct from e.cmd
              or a.roles is distinct from e.roles
              or a.permissive is distinct from e.permissive
              or a.qual_norm is distinct from regexp_replace(coalesce(e.qual, ''), '\s+', ' ', 'g')
              or a.with_check_norm is distinct from regexp_replace(coalesce(e.with_check, ''), '\s+', ' ', 'g')
          union all
          select a.tablename, a.policyname
            from actual_policies a
            left join expected_policies e
              on a.tablename = e.tablename and a.policyname = e.policyname
           where e.policyname is null
      ) drift
    union all
    select 'no write policies', count(*), 0
      from pg_policies
     where schemaname = 'public' and tablename in (select name from expected_tables)
       and cmd <> 'SELECT'
    union all
    select 'authenticated select-only grants', count(*), 10
      from expected_tables
     where has_table_privilege('authenticated', format('public.%I', name), 'SELECT')
       and not has_table_privilege('authenticated', format('public.%I', name), 'INSERT')
       and not has_table_privilege('authenticated', format('public.%I', name), 'UPDATE')
       and not has_table_privilege('authenticated', format('public.%I', name), 'DELETE')
    union all
    select 'anon table access', count(*), 0
      from expected_tables
     where has_table_privilege('anon', format('public.%I', name), 'SELECT')
        or has_table_privilege('anon', format('public.%I', name), 'INSERT')
        or has_table_privilege('anon', format('public.%I', name), 'UPDATE')
        or has_table_privilege('anon', format('public.%I', name), 'DELETE')
    union all
    select 'private schema isolation', count(*), 0
      from (values ('anon'), ('authenticated')) roles(role_name)
     where has_schema_privilege(role_name, 'private', 'USAGE')
        or has_table_privilege(role_name, 'private.device_enrollments', 'SELECT')
    union all
    select 'public RPCs present', count(*), 16
      from expected_rpcs
     where to_regprocedure(signature) is not null
    union all
    select 'RPC security definer', count(*), 16
      from expected_rpcs e
      join pg_proc p on p.oid = to_regprocedure(e.signature)
     where p.prosecdef
    union all
    select 'RPC owned by postgres', count(*), 16
      from expected_rpcs e
      join pg_proc p on p.oid = to_regprocedure(e.signature)
     where pg_get_userbyid(p.proowner) = 'postgres'
    union all
    select 'RPC empty search_path', count(*), 16
      from expected_rpcs e
      join pg_proc p on p.oid = to_regprocedure(e.signature)
     where 'search_path=""' = any(p.proconfig)
    union all
    select 'RPC authenticated-only execute', count(*), 16
      from expected_rpcs e
      join pg_proc p on p.oid = to_regprocedure(e.signature)
     where has_function_privilege('authenticated', p.oid, 'EXECUTE')
       and not has_function_privilege('anon', p.oid, 'EXECUTE')
    union all
    select 'Realtime exact tables', count(*), 5
      from pg_publication_tables
     where pubname = 'supabase_realtime' and schemaname = 'public'
       and tablename in ('account_settings', 'devices', 'device_status', 'device_settings', 'device_commands')
    union all
    select 'Realtime extra tables', count(*), 0
      from pg_publication_tables
     where pubname = 'supabase_realtime' and schemaname = 'public'
       and tablename not in ('account_settings', 'devices', 'device_status', 'device_settings', 'device_commands')
    union all
    select 'anonymous cleanup job', count(*), 1
      from cron.job
     where jobname = 'wasted-cleanup-anonymous-identities'
    union all
    select 'remote history cleanup job', count(*), 1
      from cron.job
     where jobname = 'wasted-cleanup-remote-history'
    union all
    select 'remote cleanup function hardened', count(*), 1
      from pg_proc
     where oid = to_regprocedure('private.cleanup_remote_history()')
       and prosecdef
       and 'search_path=""' = any(proconfig)
       and pg_get_userbyid(proowner) = 'postgres'
       and not has_function_privilege('authenticated', oid, 'EXECUTE')
    union all
    select 'latest command RPC is invoker', count(*), 1
      from pg_proc
     where oid = to_regprocedure('public.list_latest_device_commands()')
       and not prosecdef
       and 'search_path=""' = any(proconfig)
    union all
    select 'latest command RPC authenticated-only', count(*), 1
      from pg_proc
     where oid = to_regprocedure('public.list_latest_device_commands()')
       and has_function_privilege('authenticated', oid, 'EXECUTE')
       and not has_function_privilege('anon', oid, 'EXECUTE')
    union all
    select 'schema version marker', count(*), 1
      from pg_namespace
     where nspname = 'public'
       and obj_description(oid, 'pg_namespace') = 'wasted-remote-schema:1'
    union all
    select 'device session recovery rpc', count(*), 1
      from pg_proc
     where oid = to_regprocedure('public.recover_device_session(uuid, uuid)')
       and has_function_privilege('authenticated', oid, 'EXECUTE')
       and not has_function_privilege('anon', oid, 'EXECUTE')
    union all
    select 'single-owner hook present', count(*), 1
      from pg_proc
     where oid = to_regprocedure('public.hook_single_owner_signup(jsonb)')
    union all
    select 'single-owner hook auth-admin execute', count(*), 1
      from pg_proc
     where oid = to_regprocedure('public.hook_single_owner_signup(jsonb)')
       and has_function_privilege('supabase_auth_admin', oid, 'EXECUTE')
    union all
    select 'future functions deny PUBLIC', count(*), 0
      from pg_default_acl d
      cross join lateral aclexplode(d.defaclacl) a
     where d.defaclrole = 'postgres'::regrole
       and d.defaclnamespace in (0, 'public'::regnamespace)
       and d.defaclobjtype = 'f'
       and a.grantee = 0
       and a.privilege_type = 'EXECUTE'
    union all
    select 'global function default ACL present', count(*), 1
      from pg_default_acl d
     where d.defaclrole = 'postgres'::regrole
       and d.defaclnamespace = 0
       and d.defaclobjtype = 'f'
    union all
    select 'future functions deny app roles', count(*), 0
      from pg_default_acl d
      cross join lateral aclexplode(d.defaclacl) a
     where d.defaclrole = 'postgres'::regrole
       and d.defaclnamespace in (0, 'public'::regnamespace)
       and d.defaclobjtype = 'f'
       and a.privilege_type = 'EXECUTE'
       and a.grantee in ('anon'::regrole::oid, 'authenticated'::regrole::oid)
    union all
    select 'future functions deny app roles (effective)', count(*), 0
      from (values ('public'), ('anon'), ('authenticated')) roles(role_name)
     where has_function_privilege(
         role_name,
         'pg_temp.verify_function_acl_probe()'::regprocedure,
         'EXECUTE'
     )
)
select check_name, found, expected,
       case when found = expected then 'PASS' else 'FAIL' end as result
  from checks;

drop function if exists pg_temp.verify_function_acl_probe();

do $$
declare
    failures integer;
begin
    select count(*) into failures
      from pg_temp.verify_results
     where result = 'FAIL';
    if failures > 0 then
        raise exception 'verification failed: % check(s) did not pass', failures;
    end if;
end;
$$;
