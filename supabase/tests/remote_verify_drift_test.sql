-- Negative verifier drift tests. Run in a rolled-back transaction after migrations.
-- Proves the exact policy check and effective function-ACL probe detect drift.

begin;

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

-- Extra permissive SELECT policy must create drift.
create policy verify_drift_probe
    on public.accounts for select to authenticated using (true);

with expected_policies(tablename, policyname, cmd, roles, permissive, qual, with_check) as (
    values
        ('accounts','accounts_select_owner','SELECT',array['authenticated']::name[],'PERMISSIVE','(id = ( SELECT auth.uid() AS uid))',null::text),
        ('devices','devices_select','SELECT',array['authenticated']::name[],'PERMISSIVE','((account_id = ( SELECT auth.uid() AS uid)) OR ((auth_user_id = ( SELECT auth.uid() AS uid)) AND (revoked_at IS NULL)))',null::text),
        ('account_settings','account_settings_select','SELECT',array['authenticated']::name[],'PERMISSIVE','((account_id = ( SELECT auth.uid() AS uid)) OR (EXISTS ( SELECT 1 FROM devices d WHERE ((d.auth_user_id = ( SELECT auth.uid() AS uid)) AND (d.account_id = account_settings.account_id) AND (d.revoked_at IS NULL)))))',null::text),
        ('device_status','device_status_select_owner','SELECT',array['authenticated']::name[],'PERMISSIVE','(account_id = ( SELECT auth.uid() AS uid))',null::text),
        ('account_actions','account_actions_select_owner','SELECT',array['authenticated']::name[],'PERMISSIVE','(account_id = ( SELECT auth.uid() AS uid))',null::text),
        ('device_commands','device_commands_select','SELECT',array['authenticated']::name[],'PERMISSIVE','((account_id = ( SELECT auth.uid() AS uid)) OR (EXISTS ( SELECT 1 FROM devices d WHERE ((d.id = device_commands.device_id) AND (d.auth_user_id = ( SELECT auth.uid() AS uid)) AND (d.revoked_at IS NULL)))))',null::text),
        ('command_events','command_events_select_owner','SELECT',array['authenticated']::name[],'PERMISSIVE','(account_id = ( SELECT auth.uid() AS uid))',null::text)
), actual_policies as (
    select tablename, policyname, cmd, roles, permissive,
           regexp_replace(coalesce(qual, ''), '\s+', ' ', 'g') as qual_norm,
           regexp_replace(coalesce(with_check, ''), '\s+', ' ', 'g') as with_check_norm
      from pg_policies
     where schemaname = 'public'
)
select pg_temp.assert_true(
    (select count(*) from (
        select 1
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
        select 1
          from actual_policies a
          left join expected_policies e
            on a.tablename = e.tablename and a.policyname = e.policyname
         where e.policyname is null
    ) drift) > 0,
    'extra policy must create verifier drift'
);

drop policy verify_drift_probe on public.accounts;

-- A default ACL that grants PUBLIC must be visible to the effective probe.
alter default privileges for role postgres grant execute on functions to public;
drop function if exists pg_temp.verify_acl_probe();
create function pg_temp.verify_acl_probe() returns integer language sql as $$ select 1 $$;
select pg_temp.assert_true(
    has_function_privilege('public', 'pg_temp.verify_acl_probe()'::regprocedure, 'EXECUTE'),
    'effective ACL probe must see a PUBLIC default grant'
);
drop function if exists pg_temp.verify_acl_probe();

rollback;
