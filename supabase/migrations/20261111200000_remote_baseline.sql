-- Wasted Remote — database baseline (schema version 1).
--
-- This is the complete, self-contained schema for a FRESH Supabase project.
-- Generated from the live project with the official Supabase CLI:
--   supabase db dump --linked --keep-comments
-- then extended with the pg_cron jobs and the PostgREST schema reload that
-- pg_dump does not capture (cron jobs are rows, not schema objects).
--
-- It creates: private + public schemas, tables, constraints, indexes, RLS
-- policies, SQL functions/RPCs, triggers, grants, Realtime publication
-- membership, extension configuration, and scheduled maintenance.
--
-- It does NOT configure Supabase Auth. After running it, in
-- Dashboard → Authentication:
--   1) enable "Allow anonymous sign-ins"
--   2) keep the Email provider ON; turn "Confirm email" OFF
--   3) Hooks → Before User Created: hook ON, Hook type Postgres,
--      schema public, function hook_single_owner_signup, Save
--   4) rate limits: anonymous 10/hour/IP, sign-in/sign-up 10 per 5 minutes/IP
--
-- Run once, in SQL Editor, on an empty project. Never run it twice.

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
-- SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: pg_cron; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "pg_cron" WITH SCHEMA "pg_catalog";


--
-- Name: EXTENSION "pg_cron"; Type: COMMENT; Schema: -; Owner:
--

-- COMMENT ON EXTENSION "pg_cron" IS 'Job scheduler for PostgreSQL';


--
-- Name: private; Type: SCHEMA; Schema: -; Owner: postgres
--

CREATE SCHEMA IF NOT EXISTS "private";


ALTER SCHEMA "private" OWNER TO "postgres";

--
-- Name: SCHEMA "public"; Type: COMMENT; Schema: -; Owner: pg_database_owner
--

COMMENT ON SCHEMA "public" IS 'wasted-remote-schema:1';


--
-- Name: pg_stat_statements; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "pg_stat_statements" WITH SCHEMA "extensions";


--
-- Name: EXTENSION "pg_stat_statements"; Type: COMMENT; Schema: -; Owner:
--

-- COMMENT ON EXTENSION "pg_stat_statements" IS 'track planning and execution statistics of all SQL statements executed';


--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "pgcrypto" WITH SCHEMA "extensions";


--
-- Name: EXTENSION "pgcrypto"; Type: COMMENT; Schema: -; Owner:
--

-- COMMENT ON EXTENSION "pgcrypto" IS 'cryptographic functions';


--
-- Name: supabase_vault; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "supabase_vault" WITH SCHEMA "vault";


--
-- Name: EXTENSION "supabase_vault"; Type: COMMENT; Schema: -; Owner:
--

-- COMMENT ON EXTENSION "supabase_vault" IS 'Supabase Vault Extension';


--
-- Name: uuid-ossp; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA "extensions";


--
-- Name: EXTENSION "uuid-ossp"; Type: COMMENT; Schema: -; Owner:
--

-- COMMENT ON EXTENSION "uuid-ossp" IS 'generate universally unique identifiers (UUIDs)';


SET default_tablespace = '';

SET default_table_access_method = "heap";

--
-- Name: account_actions; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE IF NOT EXISTS "public"."account_actions" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "account_id" "uuid" NOT NULL,
    "action_type" "text" NOT NULL,
    "idempotency_key" "uuid" NOT NULL,
    "created_by" "uuid",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "expires_at" timestamp with time zone NOT NULL,
    "target_scope" "text" NOT NULL,
    CONSTRAINT "account_actions_expiry_after_created" CHECK (("expires_at" > "created_at")),
    CONSTRAINT "account_actions_target_scope_valid" CHECK (("target_scope" = ANY (ARRAY['all'::"text", 'selected'::"text"]))),
    CONSTRAINT "account_actions_type_valid" CHECK (("action_type" = ANY (ARRAY['reset_timer'::"text", 'force_reset'::"text", 'debug_mode_on'::"text", 'debug_mode_off'::"text", 'lock_now'::"text"])))
);

ALTER TABLE ONLY "public"."account_actions" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."account_actions" OWNER TO "postgres";

--
-- Name: action_result_json("public"."account_actions"); Type: FUNCTION; Schema: private; Owner: postgres
--

CREATE OR REPLACE FUNCTION "private"."action_result_json"("p_action" "public"."account_actions") RETURNS "jsonb"
    LANGUAGE "sql" STABLE
    SET "search_path" TO ''
    AS $$
    select jsonb_build_object(
        'action_id', p_action.id,
        'target_scope', p_action.target_scope,
        'created_at', p_action.created_at,
        'expires_at', p_action.expires_at,
        'device_count', (
            select count(*) from public.device_commands c where c.action_id = p_action.id
        )
    )
$$;


ALTER FUNCTION "private"."action_result_json"("p_action" "public"."account_actions") OWNER TO "postgres";

--
-- Name: action_targets_match("uuid", "uuid"[]); Type: FUNCTION; Schema: private; Owner: postgres
--

CREATE OR REPLACE FUNCTION "private"."action_targets_match"("p_action_id" "uuid", "p_device_ids" "uuid"[]) RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    with requested as (
        select distinct x as device_id from unnest(p_device_ids) x
    ), actual as (
        select t.device_id
          from public.account_action_targets t
         where t.action_id = p_action_id
    )
    select (select count(*) from requested) = (select count(*) from actual)
       and not exists (
           select 1
             from requested r
            where not exists (
                select 1 from actual a where a.device_id = r.device_id
            )
       );
$$;


ALTER FUNCTION "private"."action_targets_match"("p_action_id" "uuid", "p_device_ids" "uuid"[]) OWNER TO "postgres";

--
-- Name: cleanup_remote_history(); Type: FUNCTION; Schema: private; Owner: postgres
--

CREATE OR REPLACE FUNCTION "private"."cleanup_remote_history"() RETURNS "void"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    removed_actions bigint := 0;
    batch_count bigint;
    removed_enrollments bigint := 0;
begin
    for _ in 1..20 loop
        delete from public.account_actions a
         where a.id in (
             select old.id
               from public.account_actions old
              where old.expires_at < now() - interval '180 days'
              order by old.expires_at
              limit 5000
         );
        get diagnostics batch_count = row_count;
        removed_actions := removed_actions + batch_count;
        exit when batch_count = 0;
    end loop;

    delete from private.device_enrollments e
     where e.created_at < now() - interval '30 days'
       and (e.expires_at < now() or e.consumed_at is not null or e.revoked_at is not null);
    get diagnostics removed_enrollments = row_count;

    raise log 'remote cleanup removed % actions and % enrollments',
        removed_actions, removed_enrollments;
end;
$$;


ALTER FUNCTION "private"."cleanup_remote_history"() OWNER TO "postgres";

--
-- Name: enforce_command_action_consistency(); Type: FUNCTION; Schema: private; Owner: postgres
--

CREATE OR REPLACE FUNCTION "private"."enforce_command_action_consistency"() RETURNS "trigger"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    action public.account_actions;
begin
    select * into action from public.account_actions a where a.id = new.action_id;

    if action.id is null then
        raise exception 'device_commands.action_id % does not reference an existing action', new.action_id;
    end if;

    if new.account_id <> action.account_id
        or new.command_type <> action.action_type
        or new.expires_at <> action.expires_at then
        raise exception 'device_commands row must match its action account_id, command_type, and expires_at';
    end if;

    if not exists (
        select 1
          from public.account_action_targets t
         where t.action_id = new.action_id
           and t.account_id = new.account_id
           and t.device_id = new.device_id
    ) then
        raise exception 'device_commands row must match an action target';
    end if;

    return new;
end;
$$;


ALTER FUNCTION "private"."enforce_command_action_consistency"() OWNER TO "postgres";

--
-- Name: is_anonymous_user(); Type: FUNCTION; Schema: private; Owner: postgres
--

CREATE OR REPLACE FUNCTION "private"."is_anonymous_user"() RETURNS boolean
    LANGUAGE "sql" STABLE
    SET "search_path" TO ''
    AS $$
    select coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false)
$$;


ALTER FUNCTION "private"."is_anonymous_user"() OWNER TO "postgres";

--
-- Name: repair_legacy_revoked_commands(); Type: FUNCTION; Schema: private; Owner: postgres
--

CREATE OR REPLACE FUNCTION "private"."repair_legacy_revoked_commands"() RETURNS integer
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    repaired integer;
begin
    with stranded as (
        update public.device_commands c
           set state = 'failed',
               finished_at = now(),
               result_code = 'device_revoked_during_execution'
          from public.devices d
         where c.device_id = d.id
           and d.revoked_at is not null
           and c.state = 'executing'
        returning c.id, c.device_id, c.account_id, c.result_code
    ), recorded as (
        insert into public.command_events (command_id, device_id, account_id, event_type, result_code)
        select id, device_id, account_id, 'failed', result_code from stranded
        on conflict (command_id, event_type) do nothing
        returning 1
    )
    select count(*) into repaired from stranded;

    return repaired;
end;
$$;


ALTER FUNCTION "private"."repair_legacy_revoked_commands"() OWNER TO "postgres";

--
-- Name: devices; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE IF NOT EXISTS "public"."devices" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "account_id" "uuid" NOT NULL,
    "auth_user_id" "uuid",
    "installation_id" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "enrolled_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "revoked_at" timestamp with time zone,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "devices_active_requires_auth_user" CHECK ((("revoked_at" IS NOT NULL) OR ("auth_user_id" IS NOT NULL))),
    CONSTRAINT "devices_name_length" CHECK ((("char_length"("btrim"("name")) >= 1) AND ("char_length"("btrim"("name")) <= 80))),
    CONSTRAINT "devices_revoked_after_enrolled" CHECK ((("revoked_at" IS NULL) OR ("revoked_at" >= "enrolled_at")))
);

ALTER TABLE ONLY "public"."devices" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."devices" OWNER TO "postgres";

--
-- Name: require_active_device("uuid"); Type: FUNCTION; Schema: private; Owner: postgres
--

CREATE OR REPLACE FUNCTION "private"."require_active_device"("p_device_id" "uuid") RETURNS "public"."devices"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    uid uuid := (select auth.uid());
    device public.devices;
begin
    if uid is null then
        raise exception 'authentication required';
    end if;
    select * into device
      from public.devices d
     where d.id = p_device_id
       and d.auth_user_id = uid
       and d.revoked_at is null;
    if device.id is null then
        raise exception 'active device not found for this identity';
    end if;
    return device;
end;
$$;


ALTER FUNCTION "private"."require_active_device"("p_device_id" "uuid") OWNER TO "postgres";

--
-- Name: require_owner(); Type: FUNCTION; Schema: private; Owner: postgres
--

CREATE OR REPLACE FUNCTION "private"."require_owner"() RETURNS "uuid"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    uid uuid := (select auth.uid());
begin
    if uid is null then
        raise exception 'authentication required';
    end if;
    if private.is_anonymous_user() then
        raise exception 'owner account required';
    end if;
    if not exists (select 1 from public.accounts a where a.id = uid) then
        raise exception 'owner account not initialized';
    end if;
    return uid;
end;
$$;


ALTER FUNCTION "private"."require_owner"() OWNER TO "postgres";

--
-- Name: resolve_device_settings("uuid"); Type: FUNCTION; Schema: private; Owner: postgres
--

CREATE OR REPLACE FUNCTION "private"."resolve_device_settings"("p_device_id" "uuid") RETURNS TABLE("global_revision" bigint, "device_revision" bigint, "initialized" boolean, "lock_trigger_enabled" boolean, "lock_timer_minutes" integer, "usb_trigger_enabled" boolean, "lock_trigger_overridden" boolean, "lock_timer_overridden" boolean, "usb_trigger_overridden" boolean)
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    select
        gs.revision,
        coalesce(ds.revision, 0),
        gs.initialized_at is not null,
        coalesce(ds.lock_trigger_enabled, gs.lock_trigger_enabled),
        coalesce(ds.lock_timer_minutes, gs.lock_timer_minutes),
        coalesce(ds.usb_trigger_enabled, gs.usb_trigger_enabled),
        ds.lock_trigger_enabled is not null,
        ds.lock_timer_minutes is not null,
        ds.usb_trigger_enabled is not null
      from public.devices d
      join public.account_settings gs on gs.account_id = d.account_id
      left join public.device_settings ds on ds.device_id = d.id
     where d.id = p_device_id;
$$;


ALTER FUNCTION "private"."resolve_device_settings"("p_device_id" "uuid") OWNER TO "postgres";

--
-- Name: supersede_pending_device_commands(); Type: FUNCTION; Schema: private; Owner: postgres
--

CREATE OR REPLACE FUNCTION "private"."supersede_pending_device_commands"() RETURNS "trigger"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
begin
    with superseded as (
        update public.device_commands c
           set state = 'expired',
               finished_at = now(),
               result_code = 'superseded_by_new_action'
         where c.device_id = new.device_id
           and c.state in ('queued', 'received')
           and c.id <> new.id
           and (
               c.command_type = new.command_type
               or (
                   c.command_type in ('debug_mode_on', 'debug_mode_off')
                   and new.command_type in ('debug_mode_on', 'debug_mode_off')
               )
           )
        returning c.id, c.device_id, c.account_id, c.result_code
    )
    insert into public.command_events (command_id, device_id, account_id, event_type, result_code)
    select id, device_id, account_id, 'expired', result_code from superseded;

    return new;
end;
$$;


ALTER FUNCTION "private"."supersede_pending_device_commands"() OWNER TO "postgres";

--
-- Name: claim_device_enrollment("text", "uuid", "text", integer, boolean, boolean); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."claim_device_enrollment"("p_token" "text", "p_installation_id" "uuid", "p_device_name" "text", "p_local_lock_timer_minutes" integer, "p_local_usb_trigger_enabled" boolean, "p_local_lock_trigger_enabled" boolean) RETURNS "public"."devices"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $_$
declare
    uid uuid := (select auth.uid());
    trimmed_name text := btrim(p_device_name);
    enrollment private.device_enrollments;
    existing_device public.devices;
    settings_row public.account_settings;
    device public.devices;
begin
    if uid is null then
        raise exception 'authentication required';
    end if;
    if not private.is_anonymous_user() then
        raise exception 'device enrollment requires an anonymous device identity';
    end if;
    if p_token is null or p_token !~ '^[0-9a-f]{64}$' then
        raise exception 'enrollment token must be 64 lowercase hex characters';
    end if;
    if p_installation_id is null then
        raise exception 'installation id is required';
    end if;
    if trimmed_name is null or char_length(trimmed_name) < 1 or char_length(trimmed_name) > 80 then
        raise exception 'device name must be 1-80 characters';
    end if;
    if p_local_lock_timer_minutes is null
        or p_local_lock_timer_minutes < 1
        or p_local_lock_timer_minutes > 2147483647 then
        raise exception 'lock timer minutes must be >= 1';
    end if;
    if p_local_usb_trigger_enabled is null then
        raise exception 'usb trigger flag is required';
    end if;
    if p_local_lock_trigger_enabled is null then
        raise exception 'lock trigger flag is required';
    end if;

    select * into existing_device
      from public.devices d
     where d.installation_id = p_installation_id;
    if existing_device.id is not null then
        if existing_device.revoked_at is not null then
            raise exception 'device was revoked; reset enrollment on the device first';
        end if;
        if existing_device.auth_user_id = uid then
            return existing_device;
        end if;
        raise exception 'installation id is already bound to another identity';
    end if;

    select * into enrollment
      from private.device_enrollments e
     where e.token_hash = extensions.digest(p_token, 'sha256')
     for update;
    if enrollment.id is null then
        raise exception 'enrollment token not found';
    end if;
    if enrollment.revoked_at is not null then
        raise exception 'enrollment token was revoked';
    end if;
    if enrollment.consumed_at is not null then
        raise exception 'enrollment token was already used';
    end if;
    if enrollment.expires_at <= now() then
        raise exception 'enrollment token has expired';
    end if;

    select * into settings_row
      from public.account_settings s
     where s.account_id = enrollment.account_id
     for update;

    insert into public.devices (account_id, auth_user_id, installation_id, name)
    values (enrollment.account_id, uid, p_installation_id, trimmed_name)
    returning * into device;

    update private.device_enrollments e
       set consumed_at = now(), consumed_by_device_id = device.id
     where e.id = enrollment.id;

    if settings_row.initialized_at is null then
        update public.account_settings s
           set lock_timer_minutes = p_local_lock_timer_minutes,
               usb_trigger_enabled = p_local_usb_trigger_enabled,
               lock_trigger_enabled = p_local_lock_trigger_enabled,
               revision = 1,
               initialized_at = now(),
               updated_by = uid,
               updated_at = now()
         where s.account_id = enrollment.account_id
           and s.initialized_at is null;
    end if;

    return device;
exception
    when unique_violation then
        select * into existing_device
          from public.devices d
         where d.installation_id = p_installation_id;
        if existing_device.id is not null then
            if existing_device.revoked_at is not null then
                raise exception 'device was revoked; reset enrollment on the device first';
            end if;
            if existing_device.auth_user_id = uid then
                return existing_device;
            end if;
        end if;
        raise;
end;
$_$;


ALTER FUNCTION "public"."claim_device_enrollment"("p_token" "text", "p_installation_id" "uuid", "p_device_name" "text", "p_local_lock_timer_minutes" integer, "p_local_usb_trigger_enabled" boolean, "p_local_lock_trigger_enabled" boolean) OWNER TO "postgres";

--
-- Name: create_account_action("text", "uuid", "text", "uuid"[]); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."create_account_action"("p_action_type" "text", "p_idempotency_key" "uuid", "p_target_scope" "text", "p_device_ids" "uuid"[]) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    uid uuid := private.require_owner();
    ttl interval;
    ttl_minutes integer;
    existing public.account_actions;
    action public.account_actions;
    target_ids uuid[];
    device_count integer;
begin
    if p_action_type is null or p_action_type not in ('reset_timer', 'force_reset', 'lock_now') then
        raise exception 'unsupported action type';
    end if;
    if p_idempotency_key is null then
        raise exception 'idempotency key is required';
    end if;
    if p_target_scope is null or p_target_scope not in ('all', 'selected') then
        raise exception 'target scope must be all or selected';
    end if;
    if p_device_ids is null then
        raise exception 'device ids are required';
    end if;
    if p_target_scope = 'all' and cardinality(p_device_ids) <> 0 then
        raise exception 'device ids must be empty when targeting all devices';
    end if;
    if p_target_scope = 'selected' and cardinality(p_device_ids) = 0 then
        raise exception 'selected targets must not be empty';
    end if;
    if exists (select 1 from unnest(p_device_ids) x where x is null) then
        raise exception 'device ids must not contain null';
    end if;
    if p_target_scope = 'selected'
        and cardinality(p_device_ids) <>
            (select count(*) from (select distinct x from unnest(p_device_ids) x) d) then
        raise exception 'device ids must be unique';
    end if;

    select case p_action_type
               when 'reset_timer' then s.reset_timer_ttl_minutes
               when 'lock_now' then s.lock_command_ttl_minutes
               else s.force_reset_ttl_minutes
           end
      into ttl_minutes
      from public.account_settings s
     where s.account_id = uid;
    ttl := make_interval(mins => ttl_minutes);

    select * into existing
      from public.account_actions a
     where a.account_id = uid
       and a.idempotency_key = p_idempotency_key;
    if existing.id is not null then
        if existing.action_type <> p_action_type
            or existing.target_scope <> p_target_scope then
            raise exception 'idempotency key reused for a different action';
        end if;
        if p_target_scope = 'selected'
            and not private.action_targets_match(existing.id, p_device_ids) then
            raise exception 'idempotency key reused for a different target set';
        end if;
        return private.action_result_json(existing);
    end if;

    if p_target_scope = 'selected' then
        with locked as (
            select d.id
              from public.devices d
             where d.id = any(p_device_ids)
               and d.account_id = uid
               and d.revoked_at is null
             order by d.id
             for update
        )
        select array_agg(l.id order by l.id) into target_ids from locked l;

        if target_ids is null or cardinality(target_ids) <> cardinality(p_device_ids) then
            raise exception 'target device not found or revoked';
        end if;
    else
        with locked as (
            select d.id
              from public.devices d
             where d.account_id = uid
               and d.revoked_at is null
             order by d.id
             for update
        )
        select array_agg(l.id order by l.id) into target_ids from locked l;

        if target_ids is null then
            raise exception 'no active target devices';
        end if;
    end if;

    insert into public.account_actions (
        account_id, action_type, idempotency_key, target_scope, created_by, expires_at
    ) values (
        uid, p_action_type, p_idempotency_key, p_target_scope, uid, now() + ttl
    )
    returning * into action;

    insert into public.account_action_targets (action_id, account_id, device_id)
    select action.id, uid, t.id
      from unnest(target_ids) t(id);

    with created_commands as (
        insert into public.device_commands (
            action_id, device_id, account_id, command_type, expires_at
        )
        select action.id, t.id, uid, p_action_type, action.expires_at
          from unnest(target_ids) t(id)
        returning id, device_id, account_id
    )
    insert into public.command_events (command_id, device_id, account_id, event_type)
    select id, device_id, account_id, 'queued' from created_commands;

    select count(*) into device_count
      from public.device_commands c
     where c.action_id = action.id;

    return jsonb_build_object(
        'action_id', action.id,
        'target_scope', action.target_scope,
        'created_at', action.created_at,
        'expires_at', action.expires_at,
        'device_count', device_count
    );
exception
    when unique_violation then
        select * into existing
          from public.account_actions a
         where a.account_id = uid
           and a.idempotency_key = p_idempotency_key;
        if existing.id is not null then
            if existing.action_type <> p_action_type
                or existing.target_scope <> p_target_scope then
                raise exception 'idempotency key reused for a different action';
            end if;
            if p_target_scope = 'selected'
                and not private.action_targets_match(existing.id, p_device_ids) then
                raise exception 'idempotency key reused for a different target set';
            end if;
            return private.action_result_json(existing);
        end if;
        raise;
end;
$$;


ALTER FUNCTION "public"."create_account_action"("p_action_type" "text", "p_idempotency_key" "uuid", "p_target_scope" "text", "p_device_ids" "uuid"[]) OWNER TO "postgres";

--
-- Name: create_device_enrollment(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."create_device_enrollment"() RETURNS TABLE("enrollment_id" "uuid", "enrollment_token" "text", "expires_at" timestamp with time zone)
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    uid uuid := private.require_owner();
    token text;
    enrollment private.device_enrollments;
    ttl_minutes integer;
begin
    select s.enrollment_token_ttl_minutes
      into ttl_minutes
      from public.account_settings s
     where s.account_id = uid;

    token := encode(extensions.gen_random_bytes(32), 'hex');

    insert into private.device_enrollments (account_id, token_hash, expires_at)
    values (uid, extensions.digest(token, 'sha256'), now() + make_interval(mins => ttl_minutes))
    returning * into enrollment;

    return query select enrollment.id, token, enrollment.expires_at;
end;
$$;


ALTER FUNCTION "public"."create_device_enrollment"() OWNER TO "postgres";

--
-- Name: device_reconcile("uuid", bigint, bigint, integer, boolean, boolean, bigint, boolean, boolean, boolean, boolean, smallint, boolean, "text", smallint, "text", "text", "text"); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."device_reconcile"("p_device_id" "uuid", "p_applied_global_settings_revision" bigint, "p_applied_device_settings_revision" bigint, "p_actual_lock_timer_minutes" integer, "p_actual_usb_trigger_enabled" boolean, "p_actual_lock_trigger_enabled" boolean, "p_timer_remaining_seconds" bigint, "p_timer_running" boolean, "p_debug_mode" boolean, "p_reset_supported" boolean, "p_remote_enabled" boolean, "p_battery_percent" smallint, "p_battery_charging" boolean, "p_app_version" "text", "p_android_sdk" smallint, "p_android_release" "text", "p_last_error_code" "text", "p_last_error_message" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    device public.devices;
    settings_row record;
    old_status record;
    commands jsonb;
    server_now timestamptz := now();
    next_settings_applied_at timestamptz;
    next_battery_sampled_at timestamptz;
begin
    device := private.require_active_device(p_device_id);

    if p_applied_global_settings_revision is null or p_applied_global_settings_revision < 0 then
        raise exception 'applied global settings revision must be >= 0';
    end if;
    if p_applied_device_settings_revision is null or p_applied_device_settings_revision < 0 then
        raise exception 'applied device settings revision must be >= 0';
    end if;
    if p_actual_lock_timer_minutes is null or p_actual_lock_timer_minutes < 1 then
        raise exception 'actual lock timer minutes must be >= 1';
    end if;
    if p_timer_remaining_seconds is null or p_timer_remaining_seconds < 0 then
        raise exception 'timer remaining seconds must be >= 0';
    end if;
    if p_battery_percent is not null and (p_battery_percent < 0 or p_battery_percent > 100) then
        raise exception 'battery percent must be between 0 and 100';
    end if;
    if p_app_version is null or char_length(p_app_version) < 1 or char_length(p_app_version) > 64 then
        raise exception 'app version must be 1-64 characters';
    end if;
    if p_android_sdk is null or p_android_sdk < 24 or p_android_sdk > 1000 then
        raise exception 'android sdk must be between 24 and 1000';
    end if;
    if p_android_release is null
        or char_length(p_android_release) < 1
        or char_length(p_android_release) > 32 then
        raise exception 'android release must be 1-32 characters';
    end if;
    if p_last_error_code is not null and char_length(p_last_error_code) > 64 then
        raise exception 'last error code is too long';
    end if;
    if p_last_error_message is not null and char_length(p_last_error_message) > 256 then
        raise exception 'last error message is too long';
    end if;

    select device_id,
           applied_global_settings_revision,
           applied_device_settings_revision,
           settings_applied_at
      into old_status
      from public.device_status s
     where s.device_id = device.id;

    next_settings_applied_at := case
        when old_status.device_id is null then server_now
        when old_status.applied_global_settings_revision
             is distinct from p_applied_global_settings_revision
          or old_status.applied_device_settings_revision
             is distinct from p_applied_device_settings_revision
            then server_now
        else coalesce(old_status.settings_applied_at, server_now)
    end;
    next_battery_sampled_at := case
        when p_battery_percent is null and p_battery_charging is null then null
        else server_now
    end;

    insert into public.device_status (
        device_id, account_id,
        applied_global_settings_revision, applied_device_settings_revision,
        actual_lock_timer_minutes, actual_usb_trigger_enabled,
        actual_lock_trigger_enabled,
        timer_remaining_seconds, timer_running,
        debug_mode, reset_supported, remote_enabled,
        battery_percent, battery_charging, battery_sampled_at,
        app_version, android_sdk, android_release, last_error_code, last_error_message,
        timer_sampled_at, last_seen_at, updated_at, settings_applied_at
    ) values (
        device.id, device.account_id,
        p_applied_global_settings_revision, p_applied_device_settings_revision,
        p_actual_lock_timer_minutes, p_actual_usb_trigger_enabled,
        p_actual_lock_trigger_enabled,
        p_timer_remaining_seconds, p_timer_running,
        p_debug_mode, p_reset_supported, p_remote_enabled,
        p_battery_percent, p_battery_charging, next_battery_sampled_at,
        p_app_version, p_android_sdk, p_android_release, p_last_error_code, p_last_error_message,
        server_now, server_now, server_now, next_settings_applied_at
    )
    on conflict (device_id) do update set
        applied_global_settings_revision = excluded.applied_global_settings_revision,
        applied_device_settings_revision = excluded.applied_device_settings_revision,
        actual_lock_timer_minutes = excluded.actual_lock_timer_minutes,
        actual_usb_trigger_enabled = excluded.actual_usb_trigger_enabled,
        actual_lock_trigger_enabled = excluded.actual_lock_trigger_enabled,
        timer_remaining_seconds = excluded.timer_remaining_seconds,
        timer_running = excluded.timer_running,
        debug_mode = excluded.debug_mode,
        reset_supported = excluded.reset_supported,
        remote_enabled = excluded.remote_enabled,
        battery_percent = excluded.battery_percent,
        battery_charging = excluded.battery_charging,
        battery_sampled_at = excluded.battery_sampled_at,
        app_version = excluded.app_version,
        android_sdk = excluded.android_sdk,
        android_release = excluded.android_release,
        last_error_code = excluded.last_error_code,
        last_error_message = excluded.last_error_message,
        timer_sampled_at = excluded.timer_sampled_at,
        last_seen_at = excluded.last_seen_at,
        updated_at = excluded.updated_at,
        settings_applied_at = excluded.settings_applied_at;

    with expired as (
        update public.device_commands c
           set state = 'expired',
               finished_at = server_now,
               result_code = 'expired'
         where c.device_id = device.id
           and c.state in ('queued', 'received')
           and c.expires_at <= server_now
        returning c.id, c.device_id, c.account_id, c.result_code
    )
    insert into public.command_events (command_id, device_id, account_id, event_type, result_code)
    select id, device_id, account_id, 'expired', result_code from expired;

    select * into settings_row
      from private.resolve_device_settings(device.id);

    select coalesce(
               jsonb_agg(
                   jsonb_build_object(
                       'id', c.id,
                       'action_id', c.action_id,
                       'command_type', c.command_type,
                       'state', c.state,
                       'created_at', c.created_at,
                       'expires_at', c.expires_at
                   ) order by c.created_at
               ),
               '[]'::jsonb
           )
      into commands
      from public.device_commands c
     where c.device_id = device.id
       and c.state in ('queued', 'received')
       and c.expires_at > server_now;

    return jsonb_build_object(
        'server_time', server_now,
        'device', jsonb_build_object(
            'id', device.id,
            'account_id', device.account_id,
            'name', device.name,
            'installation_id', device.installation_id,
            'enrolled_at', device.enrolled_at
        ),
        'settings', jsonb_build_object(
            'global_revision', settings_row.global_revision,
            'device_revision', settings_row.device_revision,
            'initialized', settings_row.initialized,
            'lock_timer_minutes', settings_row.lock_timer_minutes,
            'usb_trigger_enabled', settings_row.usb_trigger_enabled,
            'lock_trigger_enabled', settings_row.lock_trigger_enabled
        ),
        'commands', commands
    );
end;
$$;


ALTER FUNCTION "public"."device_reconcile"("p_device_id" "uuid", "p_applied_global_settings_revision" bigint, "p_applied_device_settings_revision" bigint, "p_actual_lock_timer_minutes" integer, "p_actual_usb_trigger_enabled" boolean, "p_actual_lock_trigger_enabled" boolean, "p_timer_remaining_seconds" bigint, "p_timer_running" boolean, "p_debug_mode" boolean, "p_reset_supported" boolean, "p_remote_enabled" boolean, "p_battery_percent" smallint, "p_battery_charging" boolean, "p_app_version" "text", "p_android_sdk" smallint, "p_android_release" "text", "p_last_error_code" "text", "p_last_error_message" "text") OWNER TO "postgres";

--
-- Name: device_commands; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE IF NOT EXISTS "public"."device_commands" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "action_id" "uuid" NOT NULL,
    "device_id" "uuid" NOT NULL,
    "account_id" "uuid" NOT NULL,
    "command_type" "text" NOT NULL,
    "state" "text" DEFAULT 'queued'::"text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "expires_at" timestamp with time zone NOT NULL,
    "received_at" timestamp with time zone,
    "executing_at" timestamp with time zone,
    "finished_at" timestamp with time zone,
    "result_code" "text",
    CONSTRAINT "device_commands_expiry_after_created" CHECK (("expires_at" > "created_at")),
    CONSTRAINT "device_commands_result_code_length" CHECK ((("result_code" IS NULL) OR ("char_length"("result_code") <= 64))),
    CONSTRAINT "device_commands_state_valid" CHECK (("state" = ANY (ARRAY['queued'::"text", 'received'::"text", 'executing'::"text", 'applied'::"text", 'reset_initiated'::"text", 'debug_blocked'::"text", 'rejected'::"text", 'failed'::"text", 'expired'::"text"]))),
    CONSTRAINT "device_commands_type_valid" CHECK (("command_type" = ANY (ARRAY['reset_timer'::"text", 'force_reset'::"text", 'debug_mode_on'::"text", 'debug_mode_off'::"text", 'lock_now'::"text"])))
);

ALTER TABLE ONLY "public"."device_commands" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."device_commands" OWNER TO "postgres";

--
-- Name: device_transition_command("uuid", "uuid", "text", "text", "text", "text"); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."device_transition_command"("p_device_id" "uuid", "p_command_id" "uuid", "p_expected_state" "text", "p_new_state" "text", "p_result_code" "text" DEFAULT NULL::"text", "p_detail" "text" DEFAULT NULL::"text") RETURNS "public"."device_commands"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    device public.devices;
    command public.device_commands;
    legal boolean;
begin
    device := private.require_active_device(p_device_id);

    if p_result_code is not null and char_length(p_result_code) > 64 then
        raise exception 'result code is too long';
    end if;
    if p_detail is not null and char_length(p_detail) > 256 then
        raise exception 'detail is too long';
    end if;

    select * into command
      from public.device_commands c
     where c.id = p_command_id
       and c.device_id = device.id
     for update;
    if command.id is null then
        raise exception 'command not found for this device';
    end if;

    if command.state <> p_expected_state then
        raise exception 'command state conflict: expected %, found %',
            p_expected_state, command.state;
    end if;

    legal := (command.state, p_new_state) in (
        ('queued', 'received'), ('queued', 'expired'),
        ('received', 'executing'), ('received', 'debug_blocked'),
        ('received', 'rejected'), ('received', 'failed'), ('received', 'expired'),
        ('executing', 'applied'), ('executing', 'reset_initiated'),
        ('executing', 'debug_blocked'), ('executing', 'failed')
    );
    if not legal then
        raise exception 'illegal transition % -> %', command.state, p_new_state;
    end if;

    if p_new_state in ('applied', 'reset_initiated', 'debug_blocked', 'rejected', 'failed', 'expired') then
        if command.command_type = 'reset_timer'
            and p_new_state not in ('applied', 'rejected', 'failed', 'expired') then
            raise exception 'illegal terminal state % for reset_timer', p_new_state;
        end if;
        if command.command_type = 'force_reset'
            and p_new_state not in ('reset_initiated', 'debug_blocked', 'rejected', 'failed', 'expired') then
            raise exception 'illegal terminal state % for force_reset', p_new_state;
        end if;
        if command.command_type = 'lock_now'
            and p_new_state not in ('applied', 'rejected', 'failed', 'expired') then
            raise exception 'illegal terminal state % for lock_now', p_new_state;
        end if;
        if command.command_type in ('debug_mode_on', 'debug_mode_off')
            and p_new_state not in ('applied', 'rejected', 'failed', 'expired') then
            raise exception 'illegal terminal state % for %', p_new_state, command.command_type;
        end if;
    end if;

    if p_new_state in ('received', 'executing') and command.expires_at <= now() then
        raise exception 'command has expired';
    end if;

    update public.device_commands c
       set state = p_new_state,
           received_at = case when p_new_state = 'received' then now() else c.received_at end,
           executing_at = case when p_new_state = 'executing' then now() else c.executing_at end,
           finished_at = case
               when p_new_state in ('applied', 'reset_initiated', 'debug_blocked',
                                    'rejected', 'failed', 'expired')
               then now()
               else c.finished_at
           end,
           result_code = coalesce(p_result_code, c.result_code)
     where c.id = command.id
    returning * into command;

    insert into public.command_events (command_id, device_id, account_id, event_type, result_code, detail)
    values (command.id, command.device_id, command.account_id, p_new_state, p_result_code, p_detail);

    return command;
end;
$$;


ALTER FUNCTION "public"."device_transition_command"("p_device_id" "uuid", "p_command_id" "uuid", "p_expected_state" "text", "p_new_state" "text", "p_result_code" "text", "p_detail" "text") OWNER TO "postgres";

--
-- Name: expire_account_commands(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."expire_account_commands"() RETURNS integer
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    uid uuid := private.require_owner();
    expired_count integer;
begin
    with expired as (
        update public.device_commands c
           set state = 'expired',
               finished_at = now(),
               result_code = 'expired'
         where c.account_id = uid
           and c.state in ('queued', 'received')
           and c.expires_at <= now()
        returning c.id, c.device_id, c.account_id, c.result_code
    )
    insert into public.command_events (command_id, device_id, account_id, event_type, result_code)
    select id, device_id, account_id, 'expired', result_code from expired;

    get diagnostics expired_count = row_count;
    return expired_count;
end;
$$;


ALTER FUNCTION "public"."expire_account_commands"() OWNER TO "postgres";

--
-- Name: hook_single_owner_signup("jsonb"); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."hook_single_owner_signup"("event" "jsonb") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    is_anonymous boolean := coalesce((event->'user'->>'is_anonymous')::boolean, false);
begin
    -- Device identities are anonymous and must always be allowed.
    if is_anonymous then
        return '{}'::jsonb;
    end if;

    -- Serialize concurrent signup attempts so only one owner can pass.
    perform pg_catalog.pg_advisory_xact_lock(741852963);

    if exists (select 1 from public.accounts)
        or exists (
            select 1
              from auth.users u
             where u.is_anonymous = false
               and u.email is not null
        ) then
        return jsonb_build_object(
            'error', jsonb_build_object(
                'message', 'This dashboard already has an owner. Sign in instead.',
                'http_code', 403
            )
        );
    end if;

    return '{}'::jsonb;
end;
$$;


ALTER FUNCTION "public"."hook_single_owner_signup"("event" "jsonb") OWNER TO "postgres";

--
-- Name: accounts; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE IF NOT EXISTS "public"."accounts" (
    "id" "uuid" NOT NULL,
    "handle" "text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "accounts_handle_charset" CHECK (("handle" !~ '[[:cntrl:]]'::"text")),
    CONSTRAINT "accounts_handle_length" CHECK ((("char_length"("handle") >= 1) AND ("char_length"("handle") <= 32))),
    CONSTRAINT "accounts_handle_trimmed" CHECK (("handle" = "btrim"("handle")))
);

ALTER TABLE ONLY "public"."accounts" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."accounts" OWNER TO "postgres";

--
-- Name: initialize_owner_account("text"); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."initialize_owner_account"("p_handle" "text") RETURNS "public"."accounts"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    uid uuid := (select auth.uid());
    trimmed text := btrim(p_handle);
    account public.accounts;
begin
    if uid is null then
        raise exception 'authentication required';
    end if;
    if private.is_anonymous_user() then
        raise exception 'owner account required';
    end if;

    select * into account from public.accounts a where a.id = uid;
    if account.id is not null then
        return account;
    end if;

    perform pg_catalog.pg_advisory_xact_lock(741852963);
    if exists (select 1 from public.accounts) then
        raise exception 'owner account already initialized';
    end if;

    if trimmed is null
        or char_length(trimmed) < 1
        or char_length(trimmed) > 32
        or trimmed ~ '[[:cntrl:]]' then
        raise exception 'account name must be 1-32 characters and cannot contain control characters';
    end if;

    insert into public.accounts (id, handle) values (uid, trimmed)
    returning * into account;
    insert into public.account_settings (account_id) values (uid);

    return account;
exception
    when unique_violation then
        select * into account from public.accounts a where a.id = uid;
        if account.id is not null then
            return account;
        end if;
        raise;
end;
$$;


ALTER FUNCTION "public"."initialize_owner_account"("p_handle" "text") OWNER TO "postgres";

--
-- Name: list_device_effective_settings(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."list_device_effective_settings"() RETURNS TABLE("device_id" "uuid", "account_id" "uuid", "device_name" "text", "global_revision" bigint, "device_revision" bigint, "initialized" boolean, "lock_trigger_enabled" boolean, "lock_timer_minutes" integer, "usb_trigger_enabled" boolean, "lock_trigger_overridden" boolean, "lock_timer_overridden" boolean, "usb_trigger_overridden" boolean, "settings_applied_at" timestamp with time zone, "applied_global_settings_revision" bigint, "applied_device_settings_revision" bigint, "last_seen_at" timestamp with time zone)
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    select d.id,
           d.account_id,
           d.name,
           r.global_revision,
           r.device_revision,
           r.initialized,
           r.lock_trigger_enabled,
           r.lock_timer_minutes,
           r.usb_trigger_enabled,
           r.lock_trigger_overridden,
           r.lock_timer_overridden,
           r.usb_trigger_overridden,
           s.settings_applied_at,
           s.applied_global_settings_revision,
           s.applied_device_settings_revision,
           s.last_seen_at
      from public.devices d
      cross join lateral private.resolve_device_settings(d.id) r
      left join public.device_status s on s.device_id = d.id
     where d.account_id = private.require_owner()
       and d.revoked_at is null
     order by d.created_at;
$$;


ALTER FUNCTION "public"."list_device_effective_settings"() OWNER TO "postgres";

--
-- Name: list_latest_device_commands(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."list_latest_device_commands"() RETURNS TABLE("id" "uuid", "device_id" "uuid", "command_type" "text", "state" "text", "result_code" "text", "created_at" timestamp with time zone)
    LANGUAGE "sql"
    SET "search_path" TO ''
    AS $$
    select distinct on (c.device_id)
           c.id, c.device_id, c.command_type, c.state, c.result_code, c.created_at
      from public.device_commands c
     order by c.device_id, c.created_at desc, c.id desc;
$$;


ALTER FUNCTION "public"."list_latest_device_commands"() OWNER TO "postgres";

--
-- Name: recover_device_session("uuid", "uuid"); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."recover_device_session"("p_device_id" "uuid", "p_installation_id" "uuid") RETURNS "public"."devices"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    uid uuid := (select auth.uid());
    device public.devices;
begin
    if uid is null then
        raise exception 'authentication required';
    end if;
    if not private.is_anonymous_user() then
        raise exception 'device recovery requires an anonymous device identity';
    end if;
    if p_device_id is null or p_installation_id is null then
        raise exception 'device identity is required';
    end if;

    select * into device
      from public.devices d
     where d.id = p_device_id
       and d.installation_id = p_installation_id
     for update;
    if device.id is null then
        raise exception 'device not found';
    end if;
    if device.revoked_at is not null then
        raise exception 'device revoked';
    end if;
    if device.auth_user_id = uid then
        return device;
    end if;

    update public.devices d
       set auth_user_id = uid,
           updated_at = now()
     where d.id = device.id
    returning * into device;
    return device;
end;
$$;


ALTER FUNCTION "public"."recover_device_session"("p_device_id" "uuid", "p_installation_id" "uuid") OWNER TO "postgres";

--
-- Name: rename_device("uuid", "text"); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."rename_device"("p_device_id" "uuid", "p_name" "text") RETURNS "public"."devices"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    uid uuid := private.require_owner();
    trimmed text := btrim(p_name);
    device public.devices;
begin
    if trimmed is null or char_length(trimmed) < 1 or char_length(trimmed) > 80 then
        raise exception 'device name must be 1-80 characters';
    end if;

    update public.devices d
       set name = trimmed, updated_at = now()
     where d.id = p_device_id
       and d.account_id = uid
       and d.revoked_at is null
    returning * into device;

    if device.id is null then
        raise exception 'device not found';
    end if;
    return device;
end;
$$;


ALTER FUNCTION "public"."rename_device"("p_device_id" "uuid", "p_name" "text") OWNER TO "postgres";

--
-- Name: revoke_device("uuid"); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."revoke_device"("p_device_id" "uuid") RETURNS "void"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    uid uuid := private.require_owner();
    device public.devices;
begin
    select * into device
      from public.devices d
     where d.id = p_device_id
       and d.account_id = uid
     for update;
    if device.id is null then
        raise exception 'device not found';
    end if;
    if device.revoked_at is not null then
        return;
    end if;

    update public.devices d
       set revoked_at = now(), updated_at = now()
     where d.id = p_device_id;

    with rejected as (
        update public.device_commands c
           set state = 'rejected',
               finished_at = now(),
               result_code = 'device_revoked'
         where c.device_id = p_device_id
           and c.state in ('queued', 'received')
        returning c.id, c.device_id, c.account_id, c.result_code
    )
    insert into public.command_events (command_id, device_id, account_id, event_type, result_code)
    select id, device_id, account_id, 'rejected', result_code from rejected;

    with interrupted as (
        update public.device_commands c
           set state = 'failed',
               finished_at = now(),
               result_code = 'device_revoked_during_execution'
         where c.device_id = p_device_id
           and c.state = 'executing'
        returning c.id, c.device_id, c.account_id, c.result_code
    )
    insert into public.command_events (command_id, device_id, account_id, event_type, result_code)
    select id, device_id, account_id, 'failed', result_code from interrupted;
end;
$$;


ALTER FUNCTION "public"."revoke_device"("p_device_id" "uuid") OWNER TO "postgres";

--
-- Name: set_device_debug_mode("uuid", boolean, "uuid"); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."set_device_debug_mode"("p_device_id" "uuid", "p_debug_mode" boolean, "p_idempotency_key" "uuid") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    uid uuid := private.require_owner();
    action_type text;
    ttl interval;
    ttl_minutes integer;
    existing public.account_actions;
    action public.account_actions;
    device public.devices;
begin
    if p_device_id is null then
        raise exception 'device id is required';
    end if;
    if p_debug_mode is null then
        raise exception 'debug mode flag is required';
    end if;
    if p_idempotency_key is null then
        raise exception 'idempotency key is required';
    end if;

    action_type := case when p_debug_mode then 'debug_mode_on' else 'debug_mode_off' end;
    select case when p_debug_mode
                    then s.debug_mode_on_ttl_minutes
                    else s.debug_mode_off_ttl_minutes
                end
      into ttl_minutes
      from public.account_settings s
     where s.account_id = uid;
    ttl := make_interval(mins => ttl_minutes);

    select * into device
      from public.devices d
     where d.id = p_device_id
       and d.account_id = uid
       and d.revoked_at is null
     for update;
    if device.id is null then
        raise exception 'device not found';
    end if;

    select * into existing
      from public.account_actions a
     where a.account_id = uid
       and a.idempotency_key = p_idempotency_key;
    if existing.id is not null then
        if existing.action_type <> action_type
            or existing.target_scope <> 'selected'
            or not private.action_targets_match(existing.id, array[device.id]) then
            raise exception 'idempotency key reused for a different action';
        end if;
        return private.action_result_json(existing);
    end if;

    if action_type = 'debug_mode_off' then
        with superseded as (
            update public.device_commands c
               set state = 'expired',
                   finished_at = now(),
                   result_code = 'superseded_by_debug_off'
             where c.device_id = device.id
               and c.command_type = 'force_reset'
               and c.state in ('queued', 'received')
            returning c.id, c.device_id, c.account_id, c.result_code
        )
        insert into public.command_events (command_id, device_id, account_id, event_type, result_code)
        select id, device_id, account_id, 'expired', result_code from superseded;
    end if;

    insert into public.account_actions (
        account_id, action_type, idempotency_key, target_scope, created_by, expires_at
    ) values (
        uid, action_type, p_idempotency_key, 'selected', uid, now() + ttl
    )
    returning * into action;

    insert into public.account_action_targets (action_id, account_id, device_id)
    values (action.id, uid, device.id);

    with created_commands as (
        insert into public.device_commands (
            action_id, device_id, account_id, command_type, expires_at
        ) values (
            action.id, device.id, uid, action_type, action.expires_at
        )
        returning id, device_id, account_id
    )
    insert into public.command_events (command_id, device_id, account_id, event_type)
    select id, device_id, account_id, 'queued' from created_commands;

    return private.action_result_json(action);
exception
    when unique_violation then
        select * into existing
          from public.account_actions a
         where a.account_id = uid
           and a.idempotency_key = p_idempotency_key;
        if existing.id is not null then
            if existing.action_type <> action_type
                or existing.target_scope <> 'selected'
                or not private.action_targets_match(existing.id, array[device.id]) then
                raise exception 'idempotency key reused for a different action';
            end if;
            return private.action_result_json(existing);
        end if;
        raise;
end;
$$;


ALTER FUNCTION "public"."set_device_debug_mode"("p_device_id" "uuid", "p_debug_mode" boolean, "p_idempotency_key" "uuid") OWNER TO "postgres";

--
-- Name: account_settings; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE IF NOT EXISTS "public"."account_settings" (
    "account_id" "uuid" NOT NULL,
    "revision" bigint DEFAULT 0 NOT NULL,
    "lock_timer_minutes" integer DEFAULT 10080 NOT NULL,
    "usb_trigger_enabled" boolean DEFAULT false NOT NULL,
    "initialized_at" timestamp with time zone,
    "updated_by" "uuid",
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "lock_trigger_enabled" boolean DEFAULT false NOT NULL,
    "limits_revision" bigint DEFAULT 0 NOT NULL,
    "enrollment_token_ttl_minutes" integer DEFAULT 10 NOT NULL,
    "lock_command_ttl_minutes" integer DEFAULT 5 NOT NULL,
    "force_reset_ttl_minutes" integer DEFAULT 10 NOT NULL,
    "reset_timer_ttl_minutes" integer DEFAULT 1440 NOT NULL,
    "debug_mode_on_ttl_minutes" integer DEFAULT 1440 NOT NULL,
    "debug_mode_off_ttl_minutes" integer DEFAULT 10 NOT NULL,
    CONSTRAINT "account_settings_debug_mode_off_ttl_range" CHECK ((("debug_mode_off_ttl_minutes" >= 1) AND ("debug_mode_off_ttl_minutes" <= 10080))),
    CONSTRAINT "account_settings_debug_mode_on_ttl_range" CHECK ((("debug_mode_on_ttl_minutes" >= 1) AND ("debug_mode_on_ttl_minutes" <= 10080))),
    CONSTRAINT "account_settings_enrollment_ttl_range" CHECK ((("enrollment_token_ttl_minutes" >= 1) AND ("enrollment_token_ttl_minutes" <= 60))),
    CONSTRAINT "account_settings_force_reset_ttl_range" CHECK ((("force_reset_ttl_minutes" >= 1) AND ("force_reset_ttl_minutes" <= 10080))),
    CONSTRAINT "account_settings_limits_revision_nonnegative" CHECK (("limits_revision" >= 0)),
    CONSTRAINT "account_settings_lock_command_ttl_range" CHECK ((("lock_command_ttl_minutes" >= 1) AND ("lock_command_ttl_minutes" <= 10080))),
    CONSTRAINT "account_settings_lock_timer_range" CHECK ((("lock_timer_minutes" >= 1) AND ("lock_timer_minutes" <= 525600))),
    CONSTRAINT "account_settings_reset_timer_ttl_range" CHECK ((("reset_timer_ttl_minutes" >= 1) AND ("reset_timer_ttl_minutes" <= 10080))),
    CONSTRAINT "account_settings_revision_nonnegative" CHECK (("revision" >= 0))
);

ALTER TABLE ONLY "public"."account_settings" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."account_settings" OWNER TO "postgres";

--
-- Name: update_account_limits(bigint, integer, integer, integer, integer, integer, integer); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."update_account_limits"("p_expected_revision" bigint, "p_enrollment_token_ttl_minutes" integer, "p_lock_command_ttl_minutes" integer, "p_force_reset_ttl_minutes" integer, "p_reset_timer_ttl_minutes" integer, "p_debug_mode_on_ttl_minutes" integer, "p_debug_mode_off_ttl_minutes" integer) RETURNS "public"."account_settings"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    uid uuid := private.require_owner();
    settings public.account_settings;
begin
    if p_expected_revision is null or p_expected_revision < 0 then
        raise exception 'expected revision must be >= 0';
    end if;
    if p_enrollment_token_ttl_minutes is null
        or p_enrollment_token_ttl_minutes < 1
        or p_enrollment_token_ttl_minutes > 60 then
        raise exception 'enrollment token lifetime must be between 1 and 60 minutes';
    end if;
    if p_lock_command_ttl_minutes is null
        or p_lock_command_ttl_minutes < 1
        or p_lock_command_ttl_minutes > 10080 then
        raise exception 'lock command expiry must be between 1 and 10080 minutes';
    end if;
    if p_force_reset_ttl_minutes is null
        or p_force_reset_ttl_minutes < 1
        or p_force_reset_ttl_minutes > 10080 then
        raise exception 'force reset expiry must be between 1 and 10080 minutes';
    end if;
    if p_reset_timer_ttl_minutes is null
        or p_reset_timer_ttl_minutes < 1
        or p_reset_timer_ttl_minutes > 10080 then
        raise exception 'reset timer expiry must be between 1 and 10080 minutes';
    end if;
    if p_debug_mode_on_ttl_minutes is null
        or p_debug_mode_on_ttl_minutes < 1
        or p_debug_mode_on_ttl_minutes > 10080 then
        raise exception 'debug mode on expiry must be between 1 and 10080 minutes';
    end if;
    if p_debug_mode_off_ttl_minutes is null
        or p_debug_mode_off_ttl_minutes < 1
        or p_debug_mode_off_ttl_minutes > 10080 then
        raise exception 'debug mode off expiry must be between 1 and 10080 minutes';
    end if;
    update public.account_settings s
       set enrollment_token_ttl_minutes = p_enrollment_token_ttl_minutes,
           lock_command_ttl_minutes = p_lock_command_ttl_minutes,
           force_reset_ttl_minutes = p_force_reset_ttl_minutes,
           reset_timer_ttl_minutes = p_reset_timer_ttl_minutes,
           debug_mode_on_ttl_minutes = p_debug_mode_on_ttl_minutes,
           debug_mode_off_ttl_minutes = p_debug_mode_off_ttl_minutes,
           limits_revision = s.limits_revision + 1,
           updated_by = uid,
           updated_at = now()
     where s.account_id = uid
       and s.limits_revision = p_expected_revision
    returning * into settings;

    if settings.account_id is null then
        raise exception 'limits revision conflict';
    end if;
    return settings;
end;
$$;


ALTER FUNCTION "public"."update_account_limits"("p_expected_revision" bigint, "p_enrollment_token_ttl_minutes" integer, "p_lock_command_ttl_minutes" integer, "p_force_reset_ttl_minutes" integer, "p_reset_timer_ttl_minutes" integer, "p_debug_mode_on_ttl_minutes" integer, "p_debug_mode_off_ttl_minutes" integer) OWNER TO "postgres";

--
-- Name: update_account_settings(bigint, integer, boolean, boolean); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."update_account_settings"("p_expected_revision" bigint, "p_lock_timer_minutes" integer, "p_usb_trigger_enabled" boolean, "p_lock_trigger_enabled" boolean) RETURNS "public"."account_settings"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    uid uuid := private.require_owner();
    settings public.account_settings;
begin
    if p_expected_revision is null or p_expected_revision < 0 then
        raise exception 'expected revision must be >= 0';
    end if;
    if p_lock_timer_minutes is null
        or p_lock_timer_minutes < 1
        or p_lock_timer_minutes > 525600 then
        raise exception 'lock timer minutes must be between 1 and 525600';
    end if;
    if p_usb_trigger_enabled is null then
        raise exception 'usb trigger flag is required';
    end if;
    if p_lock_trigger_enabled is null then
        raise exception 'lock trigger flag is required';
    end if;

    update public.account_settings s
       set lock_timer_minutes = p_lock_timer_minutes,
           usb_trigger_enabled = p_usb_trigger_enabled,
           lock_trigger_enabled = p_lock_trigger_enabled,
           revision = s.revision + 1,
           initialized_at = coalesce(s.initialized_at, now()),
           updated_by = uid,
           updated_at = now()
     where s.account_id = uid
       and s.revision = p_expected_revision
    returning * into settings;

    if settings.account_id is null then
        raise exception 'settings revision conflict';
    end if;
    return settings;
end;
$$;


ALTER FUNCTION "public"."update_account_settings"("p_expected_revision" bigint, "p_lock_timer_minutes" integer, "p_usb_trigger_enabled" boolean, "p_lock_trigger_enabled" boolean) OWNER TO "postgres";

--
-- Name: device_notes; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE IF NOT EXISTS "public"."device_notes" (
    "device_id" "uuid" NOT NULL,
    "account_id" "uuid" NOT NULL,
    "notes" "text" DEFAULT ''::"text" NOT NULL,
    "updated_by" "uuid",
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "device_notes_length" CHECK (("char_length"("notes") <= 4000))
);

ALTER TABLE ONLY "public"."device_notes" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."device_notes" OWNER TO "postgres";

--
-- Name: update_device_notes("uuid", "text"); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."update_device_notes"("p_device_id" "uuid", "p_notes" "text") RETURNS "public"."device_notes"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    uid uuid := private.require_owner();
    device public.devices;
    note public.device_notes;
begin
    if p_device_id is null then
        raise exception 'device id is required';
    end if;
    if p_notes is null then
        raise exception 'notes are required';
    end if;
    if char_length(p_notes) > 4000 then
        raise exception 'notes must be 4000 characters or fewer';
    end if;

    select * into device
      from public.devices d
     where d.id = p_device_id
       and d.account_id = uid
       and d.revoked_at is null
     for update;
    if device.id is null then
        raise exception 'device not found';
    end if;

    insert into public.device_notes (device_id, account_id, notes, updated_by)
    values (device.id, uid, p_notes, uid)
    on conflict (device_id) do update set
        notes = excluded.notes,
        updated_by = excluded.updated_by,
        updated_at = now()
    returning * into note;

    return note;
end;
$$;


ALTER FUNCTION "public"."update_device_notes"("p_device_id" "uuid", "p_notes" "text") OWNER TO "postgres";

--
-- Name: update_device_settings("uuid", bigint, "jsonb"); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."update_device_settings"("p_device_id" "uuid", "p_expected_revision" bigint, "p_patch" "jsonb") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $_$
declare
    uid uuid := private.require_owner();
    device public.devices;
    existing public.device_settings;
    effective record;
    next_trigger boolean;
    next_timer integer;
    next_usb boolean;
begin
    if p_device_id is null then
        raise exception 'device id is required';
    end if;
    if p_expected_revision is null or p_expected_revision < 0 then
        raise exception 'expected revision must be >= 0';
    end if;
    if p_patch is null or jsonb_typeof(p_patch) <> 'object' then
        raise exception 'settings patch must be a JSON object';
    end if;
    if p_patch = '{}'::jsonb then
        raise exception 'settings patch must include at least one setting';
    end if;
    if exists (
        select 1
          from jsonb_object_keys(p_patch) key
         where key not in ('lock_trigger_enabled', 'lock_timer_minutes', 'usb_trigger_enabled')
    ) then
        raise exception 'unsupported device setting';
    end if;

    if p_patch ? 'lock_trigger_enabled'
        and jsonb_typeof(p_patch -> 'lock_trigger_enabled') not in ('boolean', 'null') then
        raise exception 'lock trigger override must be boolean or null';
    end if;
    if p_patch ? 'usb_trigger_enabled'
        and jsonb_typeof(p_patch -> 'usb_trigger_enabled') not in ('boolean', 'null') then
        raise exception 'usb trigger override must be boolean or null';
    end if;
    if p_patch ? 'lock_timer_minutes' then
        if jsonb_typeof(p_patch -> 'lock_timer_minutes') not in ('number', 'null') then
            raise exception 'lock timer override must be an integer or null';
        end if;
        if jsonb_typeof(p_patch -> 'lock_timer_minutes') = 'number'
            and (
                (p_patch ->> 'lock_timer_minutes') !~ '^[0-9]+$'
                or (p_patch ->> 'lock_timer_minutes')::integer < 1
                or (p_patch ->> 'lock_timer_minutes')::integer > 525600
            ) then
            raise exception 'lock timer override must be between 1 and 525600';
        end if;
    end if;

    select * into device
      from public.devices d
     where d.id = p_device_id
       and d.account_id = uid
       and d.revoked_at is null
     for update;
    if device.id is null then
        raise exception 'device not found';
    end if;

    select * into existing
      from public.device_settings ds
     where ds.device_id = device.id
     for update;

    if p_expected_revision <> coalesce(existing.revision, 0) then
        raise exception 'device settings revision conflict';
    end if;

    next_trigger := case
        when p_patch ? 'lock_trigger_enabled'
            then (p_patch ->> 'lock_trigger_enabled')::boolean
        else existing.lock_trigger_enabled
    end;
    next_timer := case
        when p_patch ? 'lock_timer_minutes'
            then (p_patch ->> 'lock_timer_minutes')::integer
        else existing.lock_timer_minutes
    end;
    next_usb := case
        when p_patch ? 'usb_trigger_enabled'
            then (p_patch ->> 'usb_trigger_enabled')::boolean
        else existing.usb_trigger_enabled
    end;

    if existing.device_id is null then
        insert into public.device_settings (
            device_id, account_id, revision,
            lock_trigger_enabled, lock_timer_minutes, usb_trigger_enabled,
            updated_by, updated_at
        ) values (
            device.id, uid, 1,
            next_trigger, next_timer, next_usb,
            uid, now()
        );
    else
        update public.device_settings ds
           set lock_trigger_enabled = next_trigger,
               lock_timer_minutes = next_timer,
               usb_trigger_enabled = next_usb,
               revision = ds.revision + 1,
               updated_by = uid,
               updated_at = now()
         where ds.device_id = device.id;
    end if;

    select * into effective
      from private.resolve_device_settings(device.id);

    return jsonb_build_object(
        'device_id', device.id,
        'global_revision', effective.global_revision,
        'device_revision', effective.device_revision,
        'initialized', effective.initialized,
        'lock_trigger_enabled', effective.lock_trigger_enabled,
        'lock_timer_minutes', effective.lock_timer_minutes,
        'usb_trigger_enabled', effective.usb_trigger_enabled,
        'lock_trigger_overridden', effective.lock_trigger_overridden,
        'lock_timer_overridden', effective.lock_timer_overridden,
        'usb_trigger_overridden', effective.usb_trigger_overridden
    );
end;
$_$;


ALTER FUNCTION "public"."update_device_settings"("p_device_id" "uuid", "p_expected_revision" bigint, "p_patch" "jsonb") OWNER TO "postgres";

--
-- Name: update_owner_handle("text"); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE OR REPLACE FUNCTION "public"."update_owner_handle"("p_handle" "text") RETURNS "public"."accounts"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
declare
    uid uuid := (select auth.uid());
    trimmed text := btrim(p_handle);
    account public.accounts;
begin
    if uid is null then
        raise exception 'authentication required';
    end if;
    if private.is_anonymous_user() then
        raise exception 'owner account required';
    end if;

    if trimmed is null
        or char_length(trimmed) < 1
        or char_length(trimmed) > 32
        or trimmed ~ '[[:cntrl:]]' then
        raise exception 'account name must be 1-32 characters and cannot contain control characters';
    end if;

    update public.accounts a
       set handle = trimmed
     where a.id = uid
     returning * into account;

    if account.id is null then
        raise exception 'owner account missing';
    end if;

    return account;
exception
    when unique_violation then
        raise exception 'handle is already taken';
end;
$$;


ALTER FUNCTION "public"."update_owner_handle"("p_handle" "text") OWNER TO "postgres";

--
-- Name: device_enrollments; Type: TABLE; Schema: private; Owner: postgres
--

CREATE TABLE IF NOT EXISTS "private"."device_enrollments" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "account_id" "uuid" NOT NULL,
    "token_hash" "bytea" NOT NULL,
    "expires_at" timestamp with time zone NOT NULL,
    "consumed_at" timestamp with time zone,
    "consumed_by_device_id" "uuid",
    "revoked_at" timestamp with time zone,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "device_enrollments_consumed_after_created" CHECK ((("consumed_at" IS NULL) OR ("consumed_at" >= "created_at"))),
    CONSTRAINT "device_enrollments_consumed_pair" CHECK ((("consumed_at" IS NULL) = ("consumed_by_device_id" IS NULL))),
    CONSTRAINT "device_enrollments_expiry_after_created" CHECK (("expires_at" > "created_at")),
    CONSTRAINT "device_enrollments_not_consumed_and_revoked" CHECK ((("consumed_at" IS NULL) OR ("revoked_at" IS NULL))),
    CONSTRAINT "device_enrollments_revoked_after_created" CHECK ((("revoked_at" IS NULL) OR ("revoked_at" >= "created_at")))
);

ALTER TABLE ONLY "private"."device_enrollments" FORCE ROW LEVEL SECURITY;


ALTER TABLE "private"."device_enrollments" OWNER TO "postgres";

--
-- Name: account_action_targets; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE IF NOT EXISTS "public"."account_action_targets" (
    "action_id" "uuid" NOT NULL,
    "account_id" "uuid" NOT NULL,
    "device_id" "uuid" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL
);

ALTER TABLE ONLY "public"."account_action_targets" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."account_action_targets" OWNER TO "postgres";

--
-- Name: command_events; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE IF NOT EXISTS "public"."command_events" (
    "id" bigint NOT NULL,
    "command_id" "uuid" NOT NULL,
    "device_id" "uuid" NOT NULL,
    "account_id" "uuid" NOT NULL,
    "event_type" "text" NOT NULL,
    "result_code" "text",
    "detail" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "command_events_detail_length" CHECK ((("detail" IS NULL) OR ("char_length"("detail") <= 256))),
    CONSTRAINT "command_events_result_code_length" CHECK ((("result_code" IS NULL) OR ("char_length"("result_code") <= 64))),
    CONSTRAINT "command_events_type_valid" CHECK (("event_type" = ANY (ARRAY['queued'::"text", 'received'::"text", 'executing'::"text", 'applied'::"text", 'reset_initiated'::"text", 'debug_blocked'::"text", 'rejected'::"text", 'failed'::"text", 'expired'::"text"])))
);

ALTER TABLE ONLY "public"."command_events" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."command_events" OWNER TO "postgres";

--
-- Name: command_events_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

ALTER TABLE "public"."command_events" ALTER COLUMN "id" ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME "public"."command_events_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: device_settings; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE IF NOT EXISTS "public"."device_settings" (
    "device_id" "uuid" NOT NULL,
    "account_id" "uuid" NOT NULL,
    "revision" bigint DEFAULT 0 NOT NULL,
    "lock_trigger_enabled" boolean,
    "lock_timer_minutes" integer,
    "usb_trigger_enabled" boolean,
    "updated_by" "uuid",
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "device_settings_lock_timer_range" CHECK ((("lock_timer_minutes" IS NULL) OR (("lock_timer_minutes" >= 1) AND ("lock_timer_minutes" <= 525600)))),
    CONSTRAINT "device_settings_revision_nonnegative" CHECK (("revision" >= 0))
);

ALTER TABLE ONLY "public"."device_settings" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."device_settings" OWNER TO "postgres";

--
-- Name: device_status; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE IF NOT EXISTS "public"."device_status" (
    "device_id" "uuid" NOT NULL,
    "account_id" "uuid" NOT NULL,
    "applied_global_settings_revision" bigint DEFAULT 0 NOT NULL,
    "actual_lock_timer_minutes" integer NOT NULL,
    "actual_usb_trigger_enabled" boolean NOT NULL,
    "timer_remaining_seconds" bigint NOT NULL,
    "timer_running" boolean NOT NULL,
    "debug_mode" boolean NOT NULL,
    "reset_supported" boolean NOT NULL,
    "remote_enabled" boolean NOT NULL,
    "app_version" "text" NOT NULL,
    "android_sdk" smallint NOT NULL,
    "last_error_code" "text",
    "last_error_message" "text",
    "timer_sampled_at" timestamp with time zone NOT NULL,
    "last_seen_at" timestamp with time zone NOT NULL,
    "updated_at" timestamp with time zone NOT NULL,
    "actual_lock_trigger_enabled" boolean,
    "applied_device_settings_revision" bigint DEFAULT 0 NOT NULL,
    "battery_percent" smallint,
    "battery_charging" boolean,
    "battery_sampled_at" timestamp with time zone,
    "settings_applied_at" timestamp with time zone,
    "android_release" "text" DEFAULT 'unknown'::"text" NOT NULL,
    CONSTRAINT "device_status_android_release_length" CHECK ((("char_length"("android_release") >= 1) AND ("char_length"("android_release") <= 32))),
    CONSTRAINT "device_status_app_version_length" CHECK ((("char_length"("app_version") >= 1) AND ("char_length"("app_version") <= 64))),
    CONSTRAINT "device_status_applied_device_revision_nonnegative" CHECK (("applied_device_settings_revision" >= 0)),
    CONSTRAINT "device_status_applied_revision_nonnegative" CHECK (("applied_global_settings_revision" >= 0)),
    CONSTRAINT "device_status_battery_percent_range" CHECK ((("battery_percent" IS NULL) OR (("battery_percent" >= 0) AND ("battery_percent" <= 100)))),
    CONSTRAINT "device_status_error_code_length" CHECK ((("last_error_code" IS NULL) OR ("char_length"("last_error_code") <= 64))),
    CONSTRAINT "device_status_error_message_length" CHECK ((("last_error_message" IS NULL) OR ("char_length"("last_error_message") <= 256))),
    CONSTRAINT "device_status_lock_timer_range" CHECK ((("actual_lock_timer_minutes" >= 1) AND ("actual_lock_timer_minutes" <= 2147483647))),
    CONSTRAINT "device_status_sdk_range" CHECK ((("android_sdk" >= 24) AND ("android_sdk" <= 1000))),
    CONSTRAINT "device_status_timer_nonnegative" CHECK (("timer_remaining_seconds" >= 0))
);

ALTER TABLE ONLY "public"."device_status" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."device_status" OWNER TO "postgres";

--
-- Name: device_enrollments device_enrollments_pkey; Type: CONSTRAINT; Schema: private; Owner: postgres
--

ALTER TABLE ONLY "private"."device_enrollments"
    ADD CONSTRAINT "device_enrollments_pkey" PRIMARY KEY ("id");


--
-- Name: device_enrollments device_enrollments_token_hash_key; Type: CONSTRAINT; Schema: private; Owner: postgres
--

ALTER TABLE ONLY "private"."device_enrollments"
    ADD CONSTRAINT "device_enrollments_token_hash_key" UNIQUE ("token_hash");


--
-- Name: account_action_targets account_action_targets_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."account_action_targets"
    ADD CONSTRAINT "account_action_targets_pkey" PRIMARY KEY ("action_id", "device_id");


--
-- Name: account_actions account_actions_id_account_unique; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."account_actions"
    ADD CONSTRAINT "account_actions_id_account_unique" UNIQUE ("id", "account_id");


--
-- Name: account_actions account_actions_idempotency_unique; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."account_actions"
    ADD CONSTRAINT "account_actions_idempotency_unique" UNIQUE ("account_id", "idempotency_key");


--
-- Name: account_actions account_actions_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."account_actions"
    ADD CONSTRAINT "account_actions_pkey" PRIMARY KEY ("id");


--
-- Name: account_settings account_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."account_settings"
    ADD CONSTRAINT "account_settings_pkey" PRIMARY KEY ("account_id");


--
-- Name: accounts accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."accounts"
    ADD CONSTRAINT "accounts_pkey" PRIMARY KEY ("id");


--
-- Name: command_events command_events_command_type_unique; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."command_events"
    ADD CONSTRAINT "command_events_command_type_unique" UNIQUE ("command_id", "event_type");


--
-- Name: command_events command_events_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."command_events"
    ADD CONSTRAINT "command_events_pkey" PRIMARY KEY ("id");


--
-- Name: device_commands device_commands_action_device_unique; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."device_commands"
    ADD CONSTRAINT "device_commands_action_device_unique" UNIQUE ("action_id", "device_id");


--
-- Name: device_commands device_commands_id_account_device_unique; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."device_commands"
    ADD CONSTRAINT "device_commands_id_account_device_unique" UNIQUE ("id", "account_id", "device_id");


--
-- Name: device_commands device_commands_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."device_commands"
    ADD CONSTRAINT "device_commands_pkey" PRIMARY KEY ("id");


--
-- Name: device_notes device_notes_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."device_notes"
    ADD CONSTRAINT "device_notes_pkey" PRIMARY KEY ("device_id");


--
-- Name: device_settings device_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."device_settings"
    ADD CONSTRAINT "device_settings_pkey" PRIMARY KEY ("device_id");


--
-- Name: device_status device_status_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."device_status"
    ADD CONSTRAINT "device_status_pkey" PRIMARY KEY ("device_id");


--
-- Name: devices devices_auth_user_id_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."devices"
    ADD CONSTRAINT "devices_auth_user_id_key" UNIQUE ("auth_user_id");


--
-- Name: devices devices_id_account_unique; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."devices"
    ADD CONSTRAINT "devices_id_account_unique" UNIQUE ("id", "account_id");


--
-- Name: devices devices_installation_id_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."devices"
    ADD CONSTRAINT "devices_installation_id_key" UNIQUE ("installation_id");


--
-- Name: devices devices_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."devices"
    ADD CONSTRAINT "devices_pkey" PRIMARY KEY ("id");


--
-- Name: device_enrollments_account_open_idx; Type: INDEX; Schema: private; Owner: postgres
--

CREATE INDEX "device_enrollments_account_open_idx" ON "private"."device_enrollments" USING "btree" ("account_id", "created_at" DESC) WHERE (("consumed_at" IS NULL) AND ("revoked_at" IS NULL));


--
-- Name: device_enrollments_consumed_by_device_idx; Type: INDEX; Schema: private; Owner: postgres
--

CREATE INDEX "device_enrollments_consumed_by_device_idx" ON "private"."device_enrollments" USING "btree" ("consumed_by_device_id");


--
-- Name: account_action_targets_device_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "account_action_targets_device_idx" ON "public"."account_action_targets" USING "btree" ("device_id", "account_id");


--
-- Name: account_actions_account_created_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "account_actions_account_created_idx" ON "public"."account_actions" USING "btree" ("account_id", "created_at" DESC);


--
-- Name: account_actions_created_by_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "account_actions_created_by_idx" ON "public"."account_actions" USING "btree" ("created_by");


--
-- Name: account_settings_updated_by_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "account_settings_updated_by_idx" ON "public"."account_settings" USING "btree" ("updated_by");


--
-- Name: accounts_handle_lower_uidx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE UNIQUE INDEX "accounts_handle_lower_uidx" ON "public"."accounts" USING "btree" ("lower"("handle"));


--
-- Name: command_events_account_created_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "command_events_account_created_idx" ON "public"."command_events" USING "btree" ("account_id", "created_at" DESC);


--
-- Name: command_events_command_created_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "command_events_command_created_idx" ON "public"."command_events" USING "btree" ("command_id", "created_at");


--
-- Name: command_events_command_fk_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "command_events_command_fk_idx" ON "public"."command_events" USING "btree" ("command_id", "account_id", "device_id");


--
-- Name: device_commands_account_created_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "device_commands_account_created_idx" ON "public"."device_commands" USING "btree" ("account_id", "created_at" DESC);


--
-- Name: device_commands_action_fk_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "device_commands_action_fk_idx" ON "public"."device_commands" USING "btree" ("action_id", "account_id");


--
-- Name: device_commands_device_fk_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "device_commands_device_fk_idx" ON "public"."device_commands" USING "btree" ("device_id", "account_id");


--
-- Name: device_commands_device_pending_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "device_commands_device_pending_idx" ON "public"."device_commands" USING "btree" ("device_id", "created_at") WHERE ("state" = ANY (ARRAY['queued'::"text", 'received'::"text"]));


--
-- Name: device_notes_account_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "device_notes_account_idx" ON "public"."device_notes" USING "btree" ("account_id");


--
-- Name: device_settings_account_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "device_settings_account_idx" ON "public"."device_settings" USING "btree" ("account_id");


--
-- Name: device_status_account_seen_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "device_status_account_seen_idx" ON "public"."device_status" USING "btree" ("account_id", "last_seen_at" DESC);


--
-- Name: device_status_device_account_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "device_status_device_account_idx" ON "public"."device_status" USING "btree" ("device_id", "account_id");


--
-- Name: devices_account_active_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX "devices_account_active_idx" ON "public"."devices" USING "btree" ("account_id", "created_at" DESC) WHERE ("revoked_at" IS NULL);


--
-- Name: device_commands device_commands_action_consistency; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE OR REPLACE TRIGGER "device_commands_action_consistency" BEFORE INSERT OR UPDATE ON "public"."device_commands" FOR EACH ROW EXECUTE FUNCTION "private"."enforce_command_action_consistency"();


--
-- Name: device_commands device_commands_supersede_pending; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE OR REPLACE TRIGGER "device_commands_supersede_pending" BEFORE INSERT ON "public"."device_commands" FOR EACH ROW EXECUTE FUNCTION "private"."supersede_pending_device_commands"();


--
-- Name: device_enrollments device_enrollments_account_id_fkey; Type: FK CONSTRAINT; Schema: private; Owner: postgres
--

ALTER TABLE ONLY "private"."device_enrollments"
    ADD CONSTRAINT "device_enrollments_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "public"."accounts"("id") ON DELETE CASCADE;


--
-- Name: device_enrollments device_enrollments_consumed_by_device_id_fkey; Type: FK CONSTRAINT; Schema: private; Owner: postgres
--

ALTER TABLE ONLY "private"."device_enrollments"
    ADD CONSTRAINT "device_enrollments_consumed_by_device_id_fkey" FOREIGN KEY ("consumed_by_device_id") REFERENCES "public"."devices"("id") ON DELETE CASCADE;


--
-- Name: account_action_targets account_action_targets_action_account_fk; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."account_action_targets"
    ADD CONSTRAINT "account_action_targets_action_account_fk" FOREIGN KEY ("action_id", "account_id") REFERENCES "public"."account_actions"("id", "account_id") ON DELETE CASCADE;


--
-- Name: account_action_targets account_action_targets_device_account_fk; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."account_action_targets"
    ADD CONSTRAINT "account_action_targets_device_account_fk" FOREIGN KEY ("device_id", "account_id") REFERENCES "public"."devices"("id", "account_id") ON DELETE CASCADE;


--
-- Name: account_actions account_actions_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."account_actions"
    ADD CONSTRAINT "account_actions_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "public"."accounts"("id") ON DELETE CASCADE;


--
-- Name: account_actions account_actions_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."account_actions"
    ADD CONSTRAINT "account_actions_created_by_fkey" FOREIGN KEY ("created_by") REFERENCES "auth"."users"("id") ON DELETE SET NULL;


--
-- Name: account_settings account_settings_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."account_settings"
    ADD CONSTRAINT "account_settings_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "public"."accounts"("id") ON DELETE CASCADE;


--
-- Name: account_settings account_settings_updated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."account_settings"
    ADD CONSTRAINT "account_settings_updated_by_fkey" FOREIGN KEY ("updated_by") REFERENCES "auth"."users"("id") ON DELETE SET NULL;


--
-- Name: accounts accounts_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."accounts"
    ADD CONSTRAINT "accounts_id_fkey" FOREIGN KEY ("id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;


--
-- Name: command_events command_events_command_fk; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."command_events"
    ADD CONSTRAINT "command_events_command_fk" FOREIGN KEY ("command_id", "account_id", "device_id") REFERENCES "public"."device_commands"("id", "account_id", "device_id") ON DELETE CASCADE;


--
-- Name: device_commands device_commands_action_fk; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."device_commands"
    ADD CONSTRAINT "device_commands_action_fk" FOREIGN KEY ("action_id", "account_id") REFERENCES "public"."account_actions"("id", "account_id") ON DELETE CASCADE;


--
-- Name: device_commands device_commands_device_fk; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."device_commands"
    ADD CONSTRAINT "device_commands_device_fk" FOREIGN KEY ("device_id", "account_id") REFERENCES "public"."devices"("id", "account_id") ON DELETE CASCADE;


--
-- Name: device_notes device_notes_device_account_fk; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."device_notes"
    ADD CONSTRAINT "device_notes_device_account_fk" FOREIGN KEY ("device_id", "account_id") REFERENCES "public"."devices"("id", "account_id") ON DELETE CASCADE;


--
-- Name: device_notes device_notes_updated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."device_notes"
    ADD CONSTRAINT "device_notes_updated_by_fkey" FOREIGN KEY ("updated_by") REFERENCES "auth"."users"("id") ON DELETE SET NULL;


--
-- Name: device_settings device_settings_device_account_fk; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."device_settings"
    ADD CONSTRAINT "device_settings_device_account_fk" FOREIGN KEY ("device_id", "account_id") REFERENCES "public"."devices"("id", "account_id") ON DELETE CASCADE;


--
-- Name: device_settings device_settings_updated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."device_settings"
    ADD CONSTRAINT "device_settings_updated_by_fkey" FOREIGN KEY ("updated_by") REFERENCES "auth"."users"("id") ON DELETE SET NULL;


--
-- Name: device_status device_status_device_account_fk; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."device_status"
    ADD CONSTRAINT "device_status_device_account_fk" FOREIGN KEY ("device_id", "account_id") REFERENCES "public"."devices"("id", "account_id") ON DELETE CASCADE;


--
-- Name: devices devices_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."devices"
    ADD CONSTRAINT "devices_account_id_fkey" FOREIGN KEY ("account_id") REFERENCES "public"."accounts"("id") ON DELETE CASCADE;


--
-- Name: devices devices_auth_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY "public"."devices"
    ADD CONSTRAINT "devices_auth_user_id_fkey" FOREIGN KEY ("auth_user_id") REFERENCES "auth"."users"("id") ON DELETE SET NULL;


--
-- Name: device_enrollments; Type: ROW SECURITY; Schema: private; Owner: postgres
--

ALTER TABLE "private"."device_enrollments" ENABLE ROW LEVEL SECURITY;

--
-- Name: account_action_targets; Type: ROW SECURITY; Schema: public; Owner: postgres
--

ALTER TABLE "public"."account_action_targets" ENABLE ROW LEVEL SECURITY;

--
-- Name: account_action_targets account_action_targets_select_owner; Type: POLICY; Schema: public; Owner: postgres
--

CREATE POLICY "account_action_targets_select_owner" ON "public"."account_action_targets" FOR SELECT TO "authenticated" USING (("account_id" = ( SELECT "auth"."uid"() AS "uid")));


--
-- Name: account_actions; Type: ROW SECURITY; Schema: public; Owner: postgres
--

ALTER TABLE "public"."account_actions" ENABLE ROW LEVEL SECURITY;

--
-- Name: account_actions account_actions_select_owner; Type: POLICY; Schema: public; Owner: postgres
--

CREATE POLICY "account_actions_select_owner" ON "public"."account_actions" FOR SELECT TO "authenticated" USING (("account_id" = ( SELECT "auth"."uid"() AS "uid")));


--
-- Name: account_settings; Type: ROW SECURITY; Schema: public; Owner: postgres
--

ALTER TABLE "public"."account_settings" ENABLE ROW LEVEL SECURITY;

--
-- Name: account_settings account_settings_select; Type: POLICY; Schema: public; Owner: postgres
--

CREATE POLICY "account_settings_select" ON "public"."account_settings" FOR SELECT TO "authenticated" USING ((("account_id" = ( SELECT "auth"."uid"() AS "uid")) OR (EXISTS ( SELECT 1
   FROM "public"."devices" "d"
  WHERE (("d"."auth_user_id" = ( SELECT "auth"."uid"() AS "uid")) AND ("d"."account_id" = "account_settings"."account_id") AND ("d"."revoked_at" IS NULL))))));


--
-- Name: accounts; Type: ROW SECURITY; Schema: public; Owner: postgres
--

ALTER TABLE "public"."accounts" ENABLE ROW LEVEL SECURITY;

--
-- Name: accounts accounts_select_owner; Type: POLICY; Schema: public; Owner: postgres
--

CREATE POLICY "accounts_select_owner" ON "public"."accounts" FOR SELECT TO "authenticated" USING (("id" = ( SELECT "auth"."uid"() AS "uid")));


--
-- Name: command_events; Type: ROW SECURITY; Schema: public; Owner: postgres
--

ALTER TABLE "public"."command_events" ENABLE ROW LEVEL SECURITY;

--
-- Name: command_events command_events_select_owner; Type: POLICY; Schema: public; Owner: postgres
--

CREATE POLICY "command_events_select_owner" ON "public"."command_events" FOR SELECT TO "authenticated" USING (("account_id" = ( SELECT "auth"."uid"() AS "uid")));


--
-- Name: device_commands; Type: ROW SECURITY; Schema: public; Owner: postgres
--

ALTER TABLE "public"."device_commands" ENABLE ROW LEVEL SECURITY;

--
-- Name: device_commands device_commands_select; Type: POLICY; Schema: public; Owner: postgres
--

CREATE POLICY "device_commands_select" ON "public"."device_commands" FOR SELECT TO "authenticated" USING ((("account_id" = ( SELECT "auth"."uid"() AS "uid")) OR (EXISTS ( SELECT 1
   FROM "public"."devices" "d"
  WHERE (("d"."id" = "device_commands"."device_id") AND ("d"."auth_user_id" = ( SELECT "auth"."uid"() AS "uid")) AND ("d"."revoked_at" IS NULL))))));


--
-- Name: device_notes; Type: ROW SECURITY; Schema: public; Owner: postgres
--

ALTER TABLE "public"."device_notes" ENABLE ROW LEVEL SECURITY;

--
-- Name: device_notes device_notes_select_owner; Type: POLICY; Schema: public; Owner: postgres
--

CREATE POLICY "device_notes_select_owner" ON "public"."device_notes" FOR SELECT TO "authenticated" USING (("account_id" = ( SELECT "auth"."uid"() AS "uid")));


--
-- Name: device_settings; Type: ROW SECURITY; Schema: public; Owner: postgres
--

ALTER TABLE "public"."device_settings" ENABLE ROW LEVEL SECURITY;

--
-- Name: device_settings device_settings_select; Type: POLICY; Schema: public; Owner: postgres
--

CREATE POLICY "device_settings_select" ON "public"."device_settings" FOR SELECT TO "authenticated" USING ((("account_id" = ( SELECT "auth"."uid"() AS "uid")) OR (EXISTS ( SELECT 1
   FROM "public"."devices" "d"
  WHERE (("d"."id" = "device_settings"."device_id") AND ("d"."auth_user_id" = ( SELECT "auth"."uid"() AS "uid")) AND ("d"."revoked_at" IS NULL))))));


--
-- Name: device_status; Type: ROW SECURITY; Schema: public; Owner: postgres
--

ALTER TABLE "public"."device_status" ENABLE ROW LEVEL SECURITY;

--
-- Name: device_status device_status_select_owner; Type: POLICY; Schema: public; Owner: postgres
--

CREATE POLICY "device_status_select_owner" ON "public"."device_status" FOR SELECT TO "authenticated" USING (("account_id" = ( SELECT "auth"."uid"() AS "uid")));


--
-- Name: devices; Type: ROW SECURITY; Schema: public; Owner: postgres
--

ALTER TABLE "public"."devices" ENABLE ROW LEVEL SECURITY;

--
-- Name: devices devices_select; Type: POLICY; Schema: public; Owner: postgres
--

CREATE POLICY "devices_select" ON "public"."devices" FOR SELECT TO "authenticated" USING ((("account_id" = ( SELECT "auth"."uid"() AS "uid")) OR (("auth_user_id" = ( SELECT "auth"."uid"() AS "uid")) AND ("revoked_at" IS NULL))));


--
-- Name: supabase_realtime; Type: PUBLICATION; Schema: -; Owner: postgres
--

-- CREATE PUBLICATION "supabase_realtime" WITH (publish = 'insert, update, delete, truncate');


ALTER PUBLICATION "supabase_realtime" OWNER TO "postgres";

--
-- Name: supabase_realtime_messages_publication; Type: PUBLICATION; Schema: -; Owner: supabase_admin
--

-- CREATE PUBLICATION "supabase_realtime_messages_publication" WITH (publish = 'insert, update, delete, truncate');


-- ALTER PUBLICATION "supabase_realtime_messages_publication" OWNER TO "supabase_admin";

--
-- Name: supabase_realtime account_settings; Type: PUBLICATION TABLE; Schema: public; Owner: postgres
--

ALTER PUBLICATION "supabase_realtime" ADD TABLE ONLY "public"."account_settings";


--
-- Name: supabase_realtime device_commands; Type: PUBLICATION TABLE; Schema: public; Owner: postgres
--

ALTER PUBLICATION "supabase_realtime" ADD TABLE ONLY "public"."device_commands";


--
-- Name: supabase_realtime device_settings; Type: PUBLICATION TABLE; Schema: public; Owner: postgres
--

ALTER PUBLICATION "supabase_realtime" ADD TABLE ONLY "public"."device_settings";


--
-- Name: supabase_realtime device_status; Type: PUBLICATION TABLE; Schema: public; Owner: postgres
--

ALTER PUBLICATION "supabase_realtime" ADD TABLE ONLY "public"."device_status";


--
-- Name: supabase_realtime devices; Type: PUBLICATION TABLE; Schema: public; Owner: postgres
--

ALTER PUBLICATION "supabase_realtime" ADD TABLE ONLY "public"."devices";


--
-- Name: SCHEMA "cron"; Type: ACL; Schema: -; Owner: supabase_admin
--

-- GRANT USAGE ON SCHEMA "cron" TO "postgres" WITH GRANT OPTION;


--
-- Name: SCHEMA "public"; Type: ACL; Schema: -; Owner: pg_database_owner
--

GRANT USAGE ON SCHEMA "public" TO "postgres";
GRANT USAGE ON SCHEMA "public" TO "anon";
GRANT USAGE ON SCHEMA "public" TO "authenticated";
GRANT USAGE ON SCHEMA "public" TO "service_role";
GRANT USAGE ON SCHEMA "public" TO "supabase_auth_admin";


--
-- Name: FUNCTION "alter_job"("job_id" bigint, "schedule" "text", "command" "text", "database" "text", "username" "text", "active" boolean); Type: ACL; Schema: cron; Owner: supabase_admin
--

-- GRANT ALL ON FUNCTION "cron"."alter_job"("job_id" bigint, "schedule" "text", "command" "text", "database" "text", "username" "text", "active" boolean) TO "postgres" WITH GRANT OPTION;


--
-- Name: FUNCTION "job_cache_invalidate"(); Type: ACL; Schema: cron; Owner: supabase_admin
--

-- GRANT ALL ON FUNCTION "cron"."job_cache_invalidate"() TO "postgres" WITH GRANT OPTION;


--
-- Name: FUNCTION "schedule"("schedule" "text", "command" "text"); Type: ACL; Schema: cron; Owner: supabase_admin
--

-- GRANT ALL ON FUNCTION "cron"."schedule"("schedule" "text", "command" "text") TO "postgres" WITH GRANT OPTION;


--
-- Name: FUNCTION "schedule"("job_name" "text", "schedule" "text", "command" "text"); Type: ACL; Schema: cron; Owner: supabase_admin
--

-- GRANT ALL ON FUNCTION "cron"."schedule"("job_name" "text", "schedule" "text", "command" "text") TO "postgres" WITH GRANT OPTION;


--
-- Name: FUNCTION "schedule_in_database"("job_name" "text", "schedule" "text", "command" "text", "database" "text", "username" "text", "active" boolean); Type: ACL; Schema: cron; Owner: supabase_admin
--

-- GRANT ALL ON FUNCTION "cron"."schedule_in_database"("job_name" "text", "schedule" "text", "command" "text", "database" "text", "username" "text", "active" boolean) TO "postgres" WITH GRANT OPTION;


--
-- Name: FUNCTION "unschedule"("job_id" bigint); Type: ACL; Schema: cron; Owner: supabase_admin
--

-- GRANT ALL ON FUNCTION "cron"."unschedule"("job_id" bigint) TO "postgres" WITH GRANT OPTION;


--
-- Name: FUNCTION "unschedule"("job_name" "text"); Type: ACL; Schema: cron; Owner: supabase_admin
--

-- GRANT ALL ON FUNCTION "cron"."unschedule"("job_name" "text") TO "postgres" WITH GRANT OPTION;


--
-- Name: FUNCTION "armor"("bytea"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."armor"("bytea") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."armor"("bytea") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."armor"("bytea") TO "dashboard_user";


--
-- Name: FUNCTION "armor"("bytea", "text"[], "text"[]); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."armor"("bytea", "text"[], "text"[]) FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."armor"("bytea", "text"[], "text"[]) TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."armor"("bytea", "text"[], "text"[]) TO "dashboard_user";


--
-- Name: FUNCTION "crypt"("text", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."crypt"("text", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."crypt"("text", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."crypt"("text", "text") TO "dashboard_user";


--
-- Name: FUNCTION "dearmor"("text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."dearmor"("text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."dearmor"("text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."dearmor"("text") TO "dashboard_user";


--
-- Name: FUNCTION "decrypt"("bytea", "bytea", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."decrypt"("bytea", "bytea", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."decrypt"("bytea", "bytea", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."decrypt"("bytea", "bytea", "text") TO "dashboard_user";


--
-- Name: FUNCTION "decrypt_iv"("bytea", "bytea", "bytea", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."decrypt_iv"("bytea", "bytea", "bytea", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."decrypt_iv"("bytea", "bytea", "bytea", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."decrypt_iv"("bytea", "bytea", "bytea", "text") TO "dashboard_user";


--
-- Name: FUNCTION "digest"("bytea", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."digest"("bytea", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."digest"("bytea", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."digest"("bytea", "text") TO "dashboard_user";


--
-- Name: FUNCTION "digest"("text", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."digest"("text", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."digest"("text", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."digest"("text", "text") TO "dashboard_user";


--
-- Name: FUNCTION "encrypt"("bytea", "bytea", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."encrypt"("bytea", "bytea", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."encrypt"("bytea", "bytea", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."encrypt"("bytea", "bytea", "text") TO "dashboard_user";


--
-- Name: FUNCTION "encrypt_iv"("bytea", "bytea", "bytea", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."encrypt_iv"("bytea", "bytea", "bytea", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."encrypt_iv"("bytea", "bytea", "bytea", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."encrypt_iv"("bytea", "bytea", "bytea", "text") TO "dashboard_user";


--
-- Name: FUNCTION "gen_random_bytes"(integer); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."gen_random_bytes"(integer) FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."gen_random_bytes"(integer) TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."gen_random_bytes"(integer) TO "dashboard_user";


--
-- Name: FUNCTION "gen_random_uuid"(); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."gen_random_uuid"() FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."gen_random_uuid"() TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."gen_random_uuid"() TO "dashboard_user";


--
-- Name: FUNCTION "gen_salt"("text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."gen_salt"("text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."gen_salt"("text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."gen_salt"("text") TO "dashboard_user";


--
-- Name: FUNCTION "gen_salt"("text", integer); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."gen_salt"("text", integer) FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."gen_salt"("text", integer) TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."gen_salt"("text", integer) TO "dashboard_user";


--
-- Name: FUNCTION "hmac"("bytea", "bytea", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."hmac"("bytea", "bytea", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."hmac"("bytea", "bytea", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."hmac"("bytea", "bytea", "text") TO "dashboard_user";


--
-- Name: FUNCTION "hmac"("text", "text", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."hmac"("text", "text", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."hmac"("text", "text", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."hmac"("text", "text", "text") TO "dashboard_user";


--
-- Name: FUNCTION "pg_stat_statements"("showtext" boolean, OUT "userid" "oid", OUT "dbid" "oid", OUT "toplevel" boolean, OUT "queryid" bigint, OUT "query" "text", OUT "plans" bigint, OUT "total_plan_time" double precision, OUT "min_plan_time" double precision, OUT "max_plan_time" double precision, OUT "mean_plan_time" double precision, OUT "stddev_plan_time" double precision, OUT "calls" bigint, OUT "total_exec_time" double precision, OUT "min_exec_time" double precision, OUT "max_exec_time" double precision, OUT "mean_exec_time" double precision, OUT "stddev_exec_time" double precision, OUT "rows" bigint, OUT "shared_blks_hit" bigint, OUT "shared_blks_read" bigint, OUT "shared_blks_dirtied" bigint, OUT "shared_blks_written" bigint, OUT "local_blks_hit" bigint, OUT "local_blks_read" bigint, OUT "local_blks_dirtied" bigint, OUT "local_blks_written" bigint, OUT "temp_blks_read" bigint, OUT "temp_blks_written" bigint, OUT "shared_blk_read_time" double precision, OUT "shared_blk_write_time" double precision, OUT "local_blk_read_time" double precision, OUT "local_blk_write_time" double precision, OUT "temp_blk_read_time" double precision, OUT "temp_blk_write_time" double precision, OUT "wal_records" bigint, OUT "wal_fpi" bigint, OUT "wal_bytes" numeric, OUT "jit_functions" bigint, OUT "jit_generation_time" double precision, OUT "jit_inlining_count" bigint, OUT "jit_inlining_time" double precision, OUT "jit_optimization_count" bigint, OUT "jit_optimization_time" double precision, OUT "jit_emission_count" bigint, OUT "jit_emission_time" double precision, OUT "jit_deform_count" bigint, OUT "jit_deform_time" double precision, OUT "stats_since" timestamp with time zone, OUT "minmax_stats_since" timestamp with time zone); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pg_stat_statements"("showtext" boolean, OUT "userid" "oid", OUT "dbid" "oid", OUT "toplevel" boolean, OUT "queryid" bigint, OUT "query" "text", OUT "plans" bigint, OUT "total_plan_time" double precision, OUT "min_plan_time" double precision, OUT "max_plan_time" double precision, OUT "mean_plan_time" double precision, OUT "stddev_plan_time" double precision, OUT "calls" bigint, OUT "total_exec_time" double precision, OUT "min_exec_time" double precision, OUT "max_exec_time" double precision, OUT "mean_exec_time" double precision, OUT "stddev_exec_time" double precision, OUT "rows" bigint, OUT "shared_blks_hit" bigint, OUT "shared_blks_read" bigint, OUT "shared_blks_dirtied" bigint, OUT "shared_blks_written" bigint, OUT "local_blks_hit" bigint, OUT "local_blks_read" bigint, OUT "local_blks_dirtied" bigint, OUT "local_blks_written" bigint, OUT "temp_blks_read" bigint, OUT "temp_blks_written" bigint, OUT "shared_blk_read_time" double precision, OUT "shared_blk_write_time" double precision, OUT "local_blk_read_time" double precision, OUT "local_blk_write_time" double precision, OUT "temp_blk_read_time" double precision, OUT "temp_blk_write_time" double precision, OUT "wal_records" bigint, OUT "wal_fpi" bigint, OUT "wal_bytes" numeric, OUT "jit_functions" bigint, OUT "jit_generation_time" double precision, OUT "jit_inlining_count" bigint, OUT "jit_inlining_time" double precision, OUT "jit_optimization_count" bigint, OUT "jit_optimization_time" double precision, OUT "jit_emission_count" bigint, OUT "jit_emission_time" double precision, OUT "jit_deform_count" bigint, OUT "jit_deform_time" double precision, OUT "stats_since" timestamp with time zone, OUT "minmax_stats_since" timestamp with time zone) FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pg_stat_statements"("showtext" boolean, OUT "userid" "oid", OUT "dbid" "oid", OUT "toplevel" boolean, OUT "queryid" bigint, OUT "query" "text", OUT "plans" bigint, OUT "total_plan_time" double precision, OUT "min_plan_time" double precision, OUT "max_plan_time" double precision, OUT "mean_plan_time" double precision, OUT "stddev_plan_time" double precision, OUT "calls" bigint, OUT "total_exec_time" double precision, OUT "min_exec_time" double precision, OUT "max_exec_time" double precision, OUT "mean_exec_time" double precision, OUT "stddev_exec_time" double precision, OUT "rows" bigint, OUT "shared_blks_hit" bigint, OUT "shared_blks_read" bigint, OUT "shared_blks_dirtied" bigint, OUT "shared_blks_written" bigint, OUT "local_blks_hit" bigint, OUT "local_blks_read" bigint, OUT "local_blks_dirtied" bigint, OUT "local_blks_written" bigint, OUT "temp_blks_read" bigint, OUT "temp_blks_written" bigint, OUT "shared_blk_read_time" double precision, OUT "shared_blk_write_time" double precision, OUT "local_blk_read_time" double precision, OUT "local_blk_write_time" double precision, OUT "temp_blk_read_time" double precision, OUT "temp_blk_write_time" double precision, OUT "wal_records" bigint, OUT "wal_fpi" bigint, OUT "wal_bytes" numeric, OUT "jit_functions" bigint, OUT "jit_generation_time" double precision, OUT "jit_inlining_count" bigint, OUT "jit_inlining_time" double precision, OUT "jit_optimization_count" bigint, OUT "jit_optimization_time" double precision, OUT "jit_emission_count" bigint, OUT "jit_emission_time" double precision, OUT "jit_deform_count" bigint, OUT "jit_deform_time" double precision, OUT "stats_since" timestamp with time zone, OUT "minmax_stats_since" timestamp with time zone) TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pg_stat_statements"("showtext" boolean, OUT "userid" "oid", OUT "dbid" "oid", OUT "toplevel" boolean, OUT "queryid" bigint, OUT "query" "text", OUT "plans" bigint, OUT "total_plan_time" double precision, OUT "min_plan_time" double precision, OUT "max_plan_time" double precision, OUT "mean_plan_time" double precision, OUT "stddev_plan_time" double precision, OUT "calls" bigint, OUT "total_exec_time" double precision, OUT "min_exec_time" double precision, OUT "max_exec_time" double precision, OUT "mean_exec_time" double precision, OUT "stddev_exec_time" double precision, OUT "rows" bigint, OUT "shared_blks_hit" bigint, OUT "shared_blks_read" bigint, OUT "shared_blks_dirtied" bigint, OUT "shared_blks_written" bigint, OUT "local_blks_hit" bigint, OUT "local_blks_read" bigint, OUT "local_blks_dirtied" bigint, OUT "local_blks_written" bigint, OUT "temp_blks_read" bigint, OUT "temp_blks_written" bigint, OUT "shared_blk_read_time" double precision, OUT "shared_blk_write_time" double precision, OUT "local_blk_read_time" double precision, OUT "local_blk_write_time" double precision, OUT "temp_blk_read_time" double precision, OUT "temp_blk_write_time" double precision, OUT "wal_records" bigint, OUT "wal_fpi" bigint, OUT "wal_bytes" numeric, OUT "jit_functions" bigint, OUT "jit_generation_time" double precision, OUT "jit_inlining_count" bigint, OUT "jit_inlining_time" double precision, OUT "jit_optimization_count" bigint, OUT "jit_optimization_time" double precision, OUT "jit_emission_count" bigint, OUT "jit_emission_time" double precision, OUT "jit_deform_count" bigint, OUT "jit_deform_time" double precision, OUT "stats_since" timestamp with time zone, OUT "minmax_stats_since" timestamp with time zone) TO "dashboard_user";


--
-- Name: FUNCTION "pg_stat_statements_info"(OUT "dealloc" bigint, OUT "stats_reset" timestamp with time zone); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pg_stat_statements_info"(OUT "dealloc" bigint, OUT "stats_reset" timestamp with time zone) FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pg_stat_statements_info"(OUT "dealloc" bigint, OUT "stats_reset" timestamp with time zone) TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pg_stat_statements_info"(OUT "dealloc" bigint, OUT "stats_reset" timestamp with time zone) TO "dashboard_user";


--
-- Name: FUNCTION "pg_stat_statements_reset"("userid" "oid", "dbid" "oid", "queryid" bigint, "minmax_only" boolean); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pg_stat_statements_reset"("userid" "oid", "dbid" "oid", "queryid" bigint, "minmax_only" boolean) FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pg_stat_statements_reset"("userid" "oid", "dbid" "oid", "queryid" bigint, "minmax_only" boolean) TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pg_stat_statements_reset"("userid" "oid", "dbid" "oid", "queryid" bigint, "minmax_only" boolean) TO "dashboard_user";


--
-- Name: FUNCTION "pgp_armor_headers"("text", OUT "key" "text", OUT "value" "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_armor_headers"("text", OUT "key" "text", OUT "value" "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_armor_headers"("text", OUT "key" "text", OUT "value" "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_armor_headers"("text", OUT "key" "text", OUT "value" "text") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_key_id"("bytea"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_key_id"("bytea") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_key_id"("bytea") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_key_id"("bytea") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_pub_decrypt"("bytea", "bytea"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_pub_decrypt"("bytea", "bytea") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_decrypt"("bytea", "bytea") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_decrypt"("bytea", "bytea") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_pub_decrypt"("bytea", "bytea", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_pub_decrypt"("bytea", "bytea", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_decrypt"("bytea", "bytea", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_decrypt"("bytea", "bytea", "text") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_pub_decrypt"("bytea", "bytea", "text", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_pub_decrypt"("bytea", "bytea", "text", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_decrypt"("bytea", "bytea", "text", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_decrypt"("bytea", "bytea", "text", "text") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_pub_decrypt_bytea"("bytea", "bytea"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_pub_decrypt_bytea"("bytea", "bytea") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_decrypt_bytea"("bytea", "bytea") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_decrypt_bytea"("bytea", "bytea") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_pub_decrypt_bytea"("bytea", "bytea", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_pub_decrypt_bytea"("bytea", "bytea", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_decrypt_bytea"("bytea", "bytea", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_decrypt_bytea"("bytea", "bytea", "text") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_pub_decrypt_bytea"("bytea", "bytea", "text", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_pub_decrypt_bytea"("bytea", "bytea", "text", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_decrypt_bytea"("bytea", "bytea", "text", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_decrypt_bytea"("bytea", "bytea", "text", "text") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_pub_encrypt"("text", "bytea"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_pub_encrypt"("text", "bytea") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_encrypt"("text", "bytea") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_encrypt"("text", "bytea") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_pub_encrypt"("text", "bytea", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_pub_encrypt"("text", "bytea", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_encrypt"("text", "bytea", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_encrypt"("text", "bytea", "text") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_pub_encrypt_bytea"("bytea", "bytea"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_pub_encrypt_bytea"("bytea", "bytea") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_encrypt_bytea"("bytea", "bytea") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_encrypt_bytea"("bytea", "bytea") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_pub_encrypt_bytea"("bytea", "bytea", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_pub_encrypt_bytea"("bytea", "bytea", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_encrypt_bytea"("bytea", "bytea", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_pub_encrypt_bytea"("bytea", "bytea", "text") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_sym_decrypt"("bytea", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_sym_decrypt"("bytea", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_decrypt"("bytea", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_decrypt"("bytea", "text") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_sym_decrypt"("bytea", "text", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_sym_decrypt"("bytea", "text", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_decrypt"("bytea", "text", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_decrypt"("bytea", "text", "text") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_sym_decrypt_bytea"("bytea", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_sym_decrypt_bytea"("bytea", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_decrypt_bytea"("bytea", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_decrypt_bytea"("bytea", "text") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_sym_decrypt_bytea"("bytea", "text", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_sym_decrypt_bytea"("bytea", "text", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_decrypt_bytea"("bytea", "text", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_decrypt_bytea"("bytea", "text", "text") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_sym_encrypt"("text", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_sym_encrypt"("text", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_encrypt"("text", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_encrypt"("text", "text") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_sym_encrypt"("text", "text", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_sym_encrypt"("text", "text", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_encrypt"("text", "text", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_encrypt"("text", "text", "text") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_sym_encrypt_bytea"("bytea", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_sym_encrypt_bytea"("bytea", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_encrypt_bytea"("bytea", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_encrypt_bytea"("bytea", "text") TO "dashboard_user";


--
-- Name: FUNCTION "pgp_sym_encrypt_bytea"("bytea", "text", "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."pgp_sym_encrypt_bytea"("bytea", "text", "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_encrypt_bytea"("bytea", "text", "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."pgp_sym_encrypt_bytea"("bytea", "text", "text") TO "dashboard_user";


--
-- Name: FUNCTION "uuid_generate_v1"(); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."uuid_generate_v1"() FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."uuid_generate_v1"() TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."uuid_generate_v1"() TO "dashboard_user";


--
-- Name: FUNCTION "uuid_generate_v1mc"(); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."uuid_generate_v1mc"() FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."uuid_generate_v1mc"() TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."uuid_generate_v1mc"() TO "dashboard_user";


--
-- Name: FUNCTION "uuid_generate_v3"("namespace" "uuid", "name" "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."uuid_generate_v3"("namespace" "uuid", "name" "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."uuid_generate_v3"("namespace" "uuid", "name" "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."uuid_generate_v3"("namespace" "uuid", "name" "text") TO "dashboard_user";


--
-- Name: FUNCTION "uuid_generate_v4"(); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."uuid_generate_v4"() FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."uuid_generate_v4"() TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."uuid_generate_v4"() TO "dashboard_user";


--
-- Name: FUNCTION "uuid_generate_v5"("namespace" "uuid", "name" "text"); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."uuid_generate_v5"("namespace" "uuid", "name" "text") FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."uuid_generate_v5"("namespace" "uuid", "name" "text") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."uuid_generate_v5"("namespace" "uuid", "name" "text") TO "dashboard_user";


--
-- Name: FUNCTION "uuid_nil"(); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."uuid_nil"() FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."uuid_nil"() TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."uuid_nil"() TO "dashboard_user";


--
-- Name: FUNCTION "uuid_ns_dns"(); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."uuid_ns_dns"() FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."uuid_ns_dns"() TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."uuid_ns_dns"() TO "dashboard_user";


--
-- Name: FUNCTION "uuid_ns_oid"(); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."uuid_ns_oid"() FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."uuid_ns_oid"() TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."uuid_ns_oid"() TO "dashboard_user";


--
-- Name: FUNCTION "uuid_ns_url"(); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."uuid_ns_url"() FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."uuid_ns_url"() TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."uuid_ns_url"() TO "dashboard_user";


--
-- Name: FUNCTION "uuid_ns_x500"(); Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON FUNCTION "extensions"."uuid_ns_x500"() FROM "postgres";
-- GRANT ALL ON FUNCTION "extensions"."uuid_ns_x500"() TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "extensions"."uuid_ns_x500"() TO "dashboard_user";


--
-- Name: TABLE "account_actions"; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE "public"."account_actions" TO "service_role";
GRANT SELECT ON TABLE "public"."account_actions" TO "authenticated";


--
-- Name: FUNCTION "action_result_json"("p_action" "public"."account_actions"); Type: ACL; Schema: private; Owner: postgres
--

REVOKE ALL ON FUNCTION "private"."action_result_json"("p_action" "public"."account_actions") FROM PUBLIC;


--
-- Name: FUNCTION "action_targets_match"("p_action_id" "uuid", "p_device_ids" "uuid"[]); Type: ACL; Schema: private; Owner: postgres
--

REVOKE ALL ON FUNCTION "private"."action_targets_match"("p_action_id" "uuid", "p_device_ids" "uuid"[]) FROM PUBLIC;


--
-- Name: FUNCTION "cleanup_remote_history"(); Type: ACL; Schema: private; Owner: postgres
--

REVOKE ALL ON FUNCTION "private"."cleanup_remote_history"() FROM PUBLIC;


--
-- Name: FUNCTION "enforce_command_action_consistency"(); Type: ACL; Schema: private; Owner: postgres
--

REVOKE ALL ON FUNCTION "private"."enforce_command_action_consistency"() FROM PUBLIC;


--
-- Name: FUNCTION "is_anonymous_user"(); Type: ACL; Schema: private; Owner: postgres
--

REVOKE ALL ON FUNCTION "private"."is_anonymous_user"() FROM PUBLIC;


--
-- Name: FUNCTION "repair_legacy_revoked_commands"(); Type: ACL; Schema: private; Owner: postgres
--

REVOKE ALL ON FUNCTION "private"."repair_legacy_revoked_commands"() FROM PUBLIC;


--
-- Name: TABLE "devices"; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE "public"."devices" TO "service_role";
GRANT SELECT ON TABLE "public"."devices" TO "authenticated";


--
-- Name: FUNCTION "require_active_device"("p_device_id" "uuid"); Type: ACL; Schema: private; Owner: postgres
--

REVOKE ALL ON FUNCTION "private"."require_active_device"("p_device_id" "uuid") FROM PUBLIC;


--
-- Name: FUNCTION "require_owner"(); Type: ACL; Schema: private; Owner: postgres
--

REVOKE ALL ON FUNCTION "private"."require_owner"() FROM PUBLIC;


--
-- Name: FUNCTION "resolve_device_settings"("p_device_id" "uuid"); Type: ACL; Schema: private; Owner: postgres
--

REVOKE ALL ON FUNCTION "private"."resolve_device_settings"("p_device_id" "uuid") FROM PUBLIC;


--
-- Name: FUNCTION "supersede_pending_device_commands"(); Type: ACL; Schema: private; Owner: postgres
--

REVOKE ALL ON FUNCTION "private"."supersede_pending_device_commands"() FROM PUBLIC;


--
-- Name: FUNCTION "claim_device_enrollment"("p_token" "text", "p_installation_id" "uuid", "p_device_name" "text", "p_local_lock_timer_minutes" integer, "p_local_usb_trigger_enabled" boolean, "p_local_lock_trigger_enabled" boolean); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."claim_device_enrollment"("p_token" "text", "p_installation_id" "uuid", "p_device_name" "text", "p_local_lock_timer_minutes" integer, "p_local_usb_trigger_enabled" boolean, "p_local_lock_trigger_enabled" boolean) FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."claim_device_enrollment"("p_token" "text", "p_installation_id" "uuid", "p_device_name" "text", "p_local_lock_timer_minutes" integer, "p_local_usb_trigger_enabled" boolean, "p_local_lock_trigger_enabled" boolean) TO "service_role";
GRANT ALL ON FUNCTION "public"."claim_device_enrollment"("p_token" "text", "p_installation_id" "uuid", "p_device_name" "text", "p_local_lock_timer_minutes" integer, "p_local_usb_trigger_enabled" boolean, "p_local_lock_trigger_enabled" boolean) TO "authenticated";


--
-- Name: FUNCTION "create_account_action"("p_action_type" "text", "p_idempotency_key" "uuid", "p_target_scope" "text", "p_device_ids" "uuid"[]); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."create_account_action"("p_action_type" "text", "p_idempotency_key" "uuid", "p_target_scope" "text", "p_device_ids" "uuid"[]) FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."create_account_action"("p_action_type" "text", "p_idempotency_key" "uuid", "p_target_scope" "text", "p_device_ids" "uuid"[]) TO "service_role";
GRANT ALL ON FUNCTION "public"."create_account_action"("p_action_type" "text", "p_idempotency_key" "uuid", "p_target_scope" "text", "p_device_ids" "uuid"[]) TO "authenticated";


--
-- Name: FUNCTION "create_device_enrollment"(); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."create_device_enrollment"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."create_device_enrollment"() TO "service_role";
GRANT ALL ON FUNCTION "public"."create_device_enrollment"() TO "authenticated";


--
-- Name: FUNCTION "device_reconcile"("p_device_id" "uuid", "p_applied_global_settings_revision" bigint, "p_applied_device_settings_revision" bigint, "p_actual_lock_timer_minutes" integer, "p_actual_usb_trigger_enabled" boolean, "p_actual_lock_trigger_enabled" boolean, "p_timer_remaining_seconds" bigint, "p_timer_running" boolean, "p_debug_mode" boolean, "p_reset_supported" boolean, "p_remote_enabled" boolean, "p_battery_percent" smallint, "p_battery_charging" boolean, "p_app_version" "text", "p_android_sdk" smallint, "p_android_release" "text", "p_last_error_code" "text", "p_last_error_message" "text"); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."device_reconcile"("p_device_id" "uuid", "p_applied_global_settings_revision" bigint, "p_applied_device_settings_revision" bigint, "p_actual_lock_timer_minutes" integer, "p_actual_usb_trigger_enabled" boolean, "p_actual_lock_trigger_enabled" boolean, "p_timer_remaining_seconds" bigint, "p_timer_running" boolean, "p_debug_mode" boolean, "p_reset_supported" boolean, "p_remote_enabled" boolean, "p_battery_percent" smallint, "p_battery_charging" boolean, "p_app_version" "text", "p_android_sdk" smallint, "p_android_release" "text", "p_last_error_code" "text", "p_last_error_message" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."device_reconcile"("p_device_id" "uuid", "p_applied_global_settings_revision" bigint, "p_applied_device_settings_revision" bigint, "p_actual_lock_timer_minutes" integer, "p_actual_usb_trigger_enabled" boolean, "p_actual_lock_trigger_enabled" boolean, "p_timer_remaining_seconds" bigint, "p_timer_running" boolean, "p_debug_mode" boolean, "p_reset_supported" boolean, "p_remote_enabled" boolean, "p_battery_percent" smallint, "p_battery_charging" boolean, "p_app_version" "text", "p_android_sdk" smallint, "p_android_release" "text", "p_last_error_code" "text", "p_last_error_message" "text") TO "service_role";
GRANT ALL ON FUNCTION "public"."device_reconcile"("p_device_id" "uuid", "p_applied_global_settings_revision" bigint, "p_applied_device_settings_revision" bigint, "p_actual_lock_timer_minutes" integer, "p_actual_usb_trigger_enabled" boolean, "p_actual_lock_trigger_enabled" boolean, "p_timer_remaining_seconds" bigint, "p_timer_running" boolean, "p_debug_mode" boolean, "p_reset_supported" boolean, "p_remote_enabled" boolean, "p_battery_percent" smallint, "p_battery_charging" boolean, "p_app_version" "text", "p_android_sdk" smallint, "p_android_release" "text", "p_last_error_code" "text", "p_last_error_message" "text") TO "authenticated";


--
-- Name: TABLE "device_commands"; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE "public"."device_commands" TO "service_role";
GRANT SELECT ON TABLE "public"."device_commands" TO "authenticated";


--
-- Name: FUNCTION "device_transition_command"("p_device_id" "uuid", "p_command_id" "uuid", "p_expected_state" "text", "p_new_state" "text", "p_result_code" "text", "p_detail" "text"); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."device_transition_command"("p_device_id" "uuid", "p_command_id" "uuid", "p_expected_state" "text", "p_new_state" "text", "p_result_code" "text", "p_detail" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."device_transition_command"("p_device_id" "uuid", "p_command_id" "uuid", "p_expected_state" "text", "p_new_state" "text", "p_result_code" "text", "p_detail" "text") TO "service_role";
GRANT ALL ON FUNCTION "public"."device_transition_command"("p_device_id" "uuid", "p_command_id" "uuid", "p_expected_state" "text", "p_new_state" "text", "p_result_code" "text", "p_detail" "text") TO "authenticated";


--
-- Name: FUNCTION "expire_account_commands"(); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."expire_account_commands"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."expire_account_commands"() TO "service_role";
GRANT ALL ON FUNCTION "public"."expire_account_commands"() TO "authenticated";


--
-- Name: FUNCTION "hook_single_owner_signup"("event" "jsonb"); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."hook_single_owner_signup"("event" "jsonb") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."hook_single_owner_signup"("event" "jsonb") TO "service_role";
GRANT ALL ON FUNCTION "public"."hook_single_owner_signup"("event" "jsonb") TO "supabase_auth_admin";


--
-- Name: TABLE "accounts"; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE "public"."accounts" TO "service_role";
GRANT SELECT ON TABLE "public"."accounts" TO "authenticated";


--
-- Name: FUNCTION "initialize_owner_account"("p_handle" "text"); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."initialize_owner_account"("p_handle" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."initialize_owner_account"("p_handle" "text") TO "service_role";
GRANT ALL ON FUNCTION "public"."initialize_owner_account"("p_handle" "text") TO "authenticated";


--
-- Name: FUNCTION "list_device_effective_settings"(); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."list_device_effective_settings"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."list_device_effective_settings"() TO "service_role";
GRANT ALL ON FUNCTION "public"."list_device_effective_settings"() TO "authenticated";


--
-- Name: FUNCTION "list_latest_device_commands"(); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."list_latest_device_commands"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."list_latest_device_commands"() TO "service_role";
GRANT ALL ON FUNCTION "public"."list_latest_device_commands"() TO "authenticated";


--
-- Name: FUNCTION "recover_device_session"("p_device_id" "uuid", "p_installation_id" "uuid"); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."recover_device_session"("p_device_id" "uuid", "p_installation_id" "uuid") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."recover_device_session"("p_device_id" "uuid", "p_installation_id" "uuid") TO "service_role";
GRANT ALL ON FUNCTION "public"."recover_device_session"("p_device_id" "uuid", "p_installation_id" "uuid") TO "authenticated";


--
-- Name: FUNCTION "rename_device"("p_device_id" "uuid", "p_name" "text"); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."rename_device"("p_device_id" "uuid", "p_name" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."rename_device"("p_device_id" "uuid", "p_name" "text") TO "service_role";
GRANT ALL ON FUNCTION "public"."rename_device"("p_device_id" "uuid", "p_name" "text") TO "authenticated";


--
-- Name: FUNCTION "revoke_device"("p_device_id" "uuid"); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."revoke_device"("p_device_id" "uuid") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."revoke_device"("p_device_id" "uuid") TO "service_role";
GRANT ALL ON FUNCTION "public"."revoke_device"("p_device_id" "uuid") TO "authenticated";


--
-- Name: FUNCTION "set_device_debug_mode"("p_device_id" "uuid", "p_debug_mode" boolean, "p_idempotency_key" "uuid"); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."set_device_debug_mode"("p_device_id" "uuid", "p_debug_mode" boolean, "p_idempotency_key" "uuid") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."set_device_debug_mode"("p_device_id" "uuid", "p_debug_mode" boolean, "p_idempotency_key" "uuid") TO "service_role";
GRANT ALL ON FUNCTION "public"."set_device_debug_mode"("p_device_id" "uuid", "p_debug_mode" boolean, "p_idempotency_key" "uuid") TO "authenticated";


--
-- Name: TABLE "account_settings"; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE "public"."account_settings" TO "service_role";
GRANT SELECT ON TABLE "public"."account_settings" TO "authenticated";


--
-- Name: FUNCTION "update_account_limits"("p_expected_revision" bigint, "p_enrollment_token_ttl_minutes" integer, "p_lock_command_ttl_minutes" integer, "p_force_reset_ttl_minutes" integer, "p_reset_timer_ttl_minutes" integer, "p_debug_mode_on_ttl_minutes" integer, "p_debug_mode_off_ttl_minutes" integer); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."update_account_limits"("p_expected_revision" bigint, "p_enrollment_token_ttl_minutes" integer, "p_lock_command_ttl_minutes" integer, "p_force_reset_ttl_minutes" integer, "p_reset_timer_ttl_minutes" integer, "p_debug_mode_on_ttl_minutes" integer, "p_debug_mode_off_ttl_minutes" integer) FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."update_account_limits"("p_expected_revision" bigint, "p_enrollment_token_ttl_minutes" integer, "p_lock_command_ttl_minutes" integer, "p_force_reset_ttl_minutes" integer, "p_reset_timer_ttl_minutes" integer, "p_debug_mode_on_ttl_minutes" integer, "p_debug_mode_off_ttl_minutes" integer) TO "service_role";
GRANT ALL ON FUNCTION "public"."update_account_limits"("p_expected_revision" bigint, "p_enrollment_token_ttl_minutes" integer, "p_lock_command_ttl_minutes" integer, "p_force_reset_ttl_minutes" integer, "p_reset_timer_ttl_minutes" integer, "p_debug_mode_on_ttl_minutes" integer, "p_debug_mode_off_ttl_minutes" integer) TO "authenticated";


--
-- Name: FUNCTION "update_account_settings"("p_expected_revision" bigint, "p_lock_timer_minutes" integer, "p_usb_trigger_enabled" boolean, "p_lock_trigger_enabled" boolean); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."update_account_settings"("p_expected_revision" bigint, "p_lock_timer_minutes" integer, "p_usb_trigger_enabled" boolean, "p_lock_trigger_enabled" boolean) FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."update_account_settings"("p_expected_revision" bigint, "p_lock_timer_minutes" integer, "p_usb_trigger_enabled" boolean, "p_lock_trigger_enabled" boolean) TO "service_role";
GRANT ALL ON FUNCTION "public"."update_account_settings"("p_expected_revision" bigint, "p_lock_timer_minutes" integer, "p_usb_trigger_enabled" boolean, "p_lock_trigger_enabled" boolean) TO "authenticated";


--
-- Name: TABLE "device_notes"; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE "public"."device_notes" TO "service_role";
GRANT SELECT ON TABLE "public"."device_notes" TO "authenticated";


--
-- Name: FUNCTION "update_device_notes"("p_device_id" "uuid", "p_notes" "text"); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."update_device_notes"("p_device_id" "uuid", "p_notes" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."update_device_notes"("p_device_id" "uuid", "p_notes" "text") TO "service_role";
GRANT ALL ON FUNCTION "public"."update_device_notes"("p_device_id" "uuid", "p_notes" "text") TO "authenticated";


--
-- Name: FUNCTION "update_device_settings"("p_device_id" "uuid", "p_expected_revision" bigint, "p_patch" "jsonb"); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."update_device_settings"("p_device_id" "uuid", "p_expected_revision" bigint, "p_patch" "jsonb") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."update_device_settings"("p_device_id" "uuid", "p_expected_revision" bigint, "p_patch" "jsonb") TO "service_role";
GRANT ALL ON FUNCTION "public"."update_device_settings"("p_device_id" "uuid", "p_expected_revision" bigint, "p_patch" "jsonb") TO "authenticated";


--
-- Name: FUNCTION "update_owner_handle"("p_handle" "text"); Type: ACL; Schema: public; Owner: postgres
--

REVOKE ALL ON FUNCTION "public"."update_owner_handle"("p_handle" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."update_owner_handle"("p_handle" "text") TO "service_role";
GRANT ALL ON FUNCTION "public"."update_owner_handle"("p_handle" "text") TO "authenticated";


--
-- Name: FUNCTION "_crypto_aead_det_decrypt"("message" "bytea", "additional" "bytea", "key_id" bigint, "context" "bytea", "nonce" "bytea"); Type: ACL; Schema: vault; Owner: supabase_admin
--

-- GRANT ALL ON FUNCTION "vault"."_crypto_aead_det_decrypt"("message" "bytea", "additional" "bytea", "key_id" bigint, "context" "bytea", "nonce" "bytea") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "vault"."_crypto_aead_det_decrypt"("message" "bytea", "additional" "bytea", "key_id" bigint, "context" "bytea", "nonce" "bytea") TO "service_role";


--
-- Name: FUNCTION "create_secret"("new_secret" "text", "new_name" "text", "new_description" "text", "new_key_id" "uuid"); Type: ACL; Schema: vault; Owner: supabase_admin
--

-- GRANT ALL ON FUNCTION "vault"."create_secret"("new_secret" "text", "new_name" "text", "new_description" "text", "new_key_id" "uuid") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "vault"."create_secret"("new_secret" "text", "new_name" "text", "new_description" "text", "new_key_id" "uuid") TO "service_role";


--
-- Name: FUNCTION "update_secret"("secret_id" "uuid", "new_secret" "text", "new_name" "text", "new_description" "text", "new_key_id" "uuid"); Type: ACL; Schema: vault; Owner: supabase_admin
--

-- GRANT ALL ON FUNCTION "vault"."update_secret"("secret_id" "uuid", "new_secret" "text", "new_name" "text", "new_description" "text", "new_key_id" "uuid") TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON FUNCTION "vault"."update_secret"("secret_id" "uuid", "new_secret" "text", "new_name" "text", "new_description" "text", "new_key_id" "uuid") TO "service_role";


--
-- Name: TABLE "job"; Type: ACL; Schema: cron; Owner: supabase_admin
--

-- GRANT SELECT ON TABLE "cron"."job" TO "postgres" WITH GRANT OPTION;


--
-- Name: TABLE "job_run_details"; Type: ACL; Schema: cron; Owner: supabase_admin
--

-- GRANT SELECT,INSERT,REFERENCES,DELETE,TRUNCATE,MAINTAIN,UPDATE ON TABLE "cron"."job_run_details" TO "postgres" WITH GRANT OPTION;


--
-- Name: TABLE "pg_stat_statements"; Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON TABLE "extensions"."pg_stat_statements" FROM "postgres";
-- GRANT ALL ON TABLE "extensions"."pg_stat_statements" TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON TABLE "extensions"."pg_stat_statements" TO "dashboard_user";


--
-- Name: TABLE "pg_stat_statements_info"; Type: ACL; Schema: extensions; Owner: postgres
--

-- REVOKE ALL ON TABLE "extensions"."pg_stat_statements_info" FROM "postgres";
-- GRANT ALL ON TABLE "extensions"."pg_stat_statements_info" TO "postgres" WITH GRANT OPTION;
-- GRANT ALL ON TABLE "extensions"."pg_stat_statements_info" TO "dashboard_user";


--
-- Name: TABLE "account_action_targets"; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE "public"."account_action_targets" TO "service_role";
GRANT SELECT ON TABLE "public"."account_action_targets" TO "authenticated";


--
-- Name: TABLE "command_events"; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE "public"."command_events" TO "service_role";
GRANT SELECT ON TABLE "public"."command_events" TO "authenticated";


--
-- Name: SEQUENCE "command_events_id_seq"; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON SEQUENCE "public"."command_events_id_seq" TO "service_role";


--
-- Name: TABLE "device_settings"; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE "public"."device_settings" TO "service_role";
GRANT SELECT ON TABLE "public"."device_settings" TO "authenticated";


--
-- Name: TABLE "device_status"; Type: ACL; Schema: public; Owner: postgres
--

GRANT ALL ON TABLE "public"."device_status" TO "service_role";
GRANT SELECT ON TABLE "public"."device_status" TO "authenticated";


--
-- Name: TABLE "secrets"; Type: ACL; Schema: vault; Owner: supabase_admin
--

-- GRANT SELECT,REFERENCES,DELETE,TRUNCATE ON TABLE "vault"."secrets" TO "postgres" WITH GRANT OPTION;
-- GRANT SELECT,DELETE ON TABLE "vault"."secrets" TO "service_role";


--
-- Name: TABLE "decrypted_secrets"; Type: ACL; Schema: vault; Owner: supabase_admin
--

-- GRANT SELECT,REFERENCES,DELETE,TRUNCATE ON TABLE "vault"."decrypted_secrets" TO "postgres" WITH GRANT OPTION;
-- GRANT SELECT,DELETE ON TABLE "vault"."decrypted_secrets" TO "service_role";


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: public; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "postgres";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "service_role";


--
-- Name: DEFAULT PRIVILEGES FOR SEQUENCES; Type: DEFAULT ACL; Schema: public; Owner: supabase_admin
--

-- ALTER DEFAULT PRIVILEGES FOR ROLE "supabase_admin" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "postgres";
-- ALTER DEFAULT PRIVILEGES FOR ROLE "supabase_admin" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "anon";
-- ALTER DEFAULT PRIVILEGES FOR ROLE "supabase_admin" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "authenticated";
-- ALTER DEFAULT PRIVILEGES FOR ROLE "supabase_admin" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "service_role";


--
-- Name: DEFAULT PRIVILEGES FOR FUNCTIONS; Type: DEFAULT ACL; Schema: public; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "postgres";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "service_role";


--
-- Name: DEFAULT PRIVILEGES FOR FUNCTIONS; Type: DEFAULT ACL; Schema: public; Owner: supabase_admin
--

-- ALTER DEFAULT PRIVILEGES FOR ROLE "supabase_admin" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "postgres";
-- ALTER DEFAULT PRIVILEGES FOR ROLE "supabase_admin" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "anon";
-- ALTER DEFAULT PRIVILEGES FOR ROLE "supabase_admin" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "authenticated";
-- ALTER DEFAULT PRIVILEGES FOR ROLE "supabase_admin" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "service_role";


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: public; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON TABLES TO "postgres";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON TABLES TO "service_role";


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: public; Owner: supabase_admin
--

-- ALTER DEFAULT PRIVILEGES FOR ROLE "supabase_admin" IN SCHEMA "public" GRANT ALL ON TABLES TO "postgres";
-- ALTER DEFAULT PRIVILEGES FOR ROLE "supabase_admin" IN SCHEMA "public" GRANT ALL ON TABLES TO "anon";
-- ALTER DEFAULT PRIVILEGES FOR ROLE "supabase_admin" IN SCHEMA "public" GRANT ALL ON TABLES TO "authenticated";
-- ALTER DEFAULT PRIVILEGES FOR ROLE "supabase_admin" IN SCHEMA "public" GRANT ALL ON TABLES TO "service_role";


--
-- Name: DEFAULT PRIVILEGES FOR FUNCTIONS; Type: DEFAULT ACL; Schema: -; Owner: postgres
--

ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" REVOKE ALL ON FUNCTIONS FROM PUBLIC;


--
-- Name: issue_graphql_placeholder; Type: EVENT TRIGGER; Schema: -; Owner: supabase_admin
--

-- CREATE EVENT TRIGGER "issue_graphql_placeholder" ON "sql_drop"
--          WHEN TAG IN ('DROP EXTENSION')
--    EXECUTE FUNCTION "extensions"."set_graphql_placeholder"();


-- ALTER EVENT TRIGGER "issue_graphql_placeholder" OWNER TO "supabase_admin";

--
-- Name: issue_pg_cron_access; Type: EVENT TRIGGER; Schema: -; Owner: supabase_admin
--

-- CREATE EVENT TRIGGER "issue_pg_cron_access" ON "ddl_command_end"
--          WHEN TAG IN ('CREATE EXTENSION')
--    EXECUTE FUNCTION "extensions"."grant_pg_cron_access"();


-- ALTER EVENT TRIGGER "issue_pg_cron_access" OWNER TO "supabase_admin";

--
-- Name: issue_pg_graphql_access; Type: EVENT TRIGGER; Schema: -; Owner: supabase_admin
--

-- CREATE EVENT TRIGGER "issue_pg_graphql_access" ON "ddl_command_end"
--          WHEN TAG IN ('CREATE EXTENSION')
--    EXECUTE FUNCTION "extensions"."grant_pg_graphql_access"();


-- ALTER EVENT TRIGGER "issue_pg_graphql_access" OWNER TO "supabase_admin";

--
-- Name: issue_pg_net_access; Type: EVENT TRIGGER; Schema: -; Owner: supabase_admin
--

-- CREATE EVENT TRIGGER "issue_pg_net_access" ON "ddl_command_end"
--          WHEN TAG IN ('CREATE EXTENSION')
--    EXECUTE FUNCTION "extensions"."grant_pg_net_access"();


-- ALTER EVENT TRIGGER "issue_pg_net_access" OWNER TO "supabase_admin";

--
-- Name: pgrst_ddl_watch; Type: EVENT TRIGGER; Schema: -; Owner: supabase_admin
--

-- CREATE EVENT TRIGGER "pgrst_ddl_watch" ON "ddl_command_end"
--    EXECUTE FUNCTION "extensions"."pgrst_ddl_watch"();


-- ALTER EVENT TRIGGER "pgrst_ddl_watch" OWNER TO "supabase_admin";

--
-- Name: pgrst_drop_watch; Type: EVENT TRIGGER; Schema: -; Owner: supabase_admin
--

-- CREATE EVENT TRIGGER "pgrst_drop_watch" ON "sql_drop"
--    EXECUTE FUNCTION "extensions"."pgrst_drop_watch"();


-- ALTER EVENT TRIGGER "pgrst_drop_watch" OWNER TO "supabase_admin";

--
--

-- ─── Default privileges: future objects are not auto-exposed ────────────────
-- pg_dump records default ACL grants but not the matching revokes, so they are
-- restored explicitly here. PostgreSQL grants EXECUTE on new functions to PUBLIC
-- by default, and the PUBLIC revoke must be global to be effective.

alter default privileges for role postgres in schema public
    revoke all on tables from anon, authenticated;
alter default privileges for role postgres in schema public
    revoke all on sequences from anon, authenticated;
alter default privileges for role postgres in schema public
    revoke execute on functions from anon, authenticated;
alter default privileges for role postgres in schema public
    revoke execute on functions from public;
alter default privileges for role postgres
    revoke execute on functions from public;

-- ─── Scheduled maintenance (data rows; not part of a pg_dump schema) ────────

select cron.unschedule('wasted-cleanup-anonymous-identities')
 where exists (select 1 from cron.job where jobname = 'wasted-cleanup-anonymous-identities');

select cron.schedule(
    'wasted-cleanup-anonymous-identities',
    '30 3 * * *',
    $job$
    -- anonymous identities older than 24 hours that never bound to a device
    delete from auth.users u
     where u.is_anonymous is true
       and u.created_at < now() - interval '24 hours'
       and not exists (
           select 1 from public.devices d where d.auth_user_id = u.id
       );

    -- identities belonging only to devices revoked for more than 30 days
    delete from auth.users u
     where u.is_anonymous is true
       and u.id in (
           select d.auth_user_id
             from public.devices d
            where d.auth_user_id is not null
            group by d.auth_user_id
           having bool_and(d.revoked_at is not null)
              and max(d.revoked_at) < now() - interval '30 days'
       );
    $job$
);

select cron.unschedule('wasted-cleanup-remote-history')
 where exists (select 1 from cron.job where jobname = 'wasted-cleanup-remote-history');

select cron.schedule(
    'wasted-cleanup-remote-history',
    '45 3 * * *',
    $job$select private.cleanup_remote_history();$job$
);

-- ─── Lock down default privileges ──────────────────────────────────────────
-- A brand-new Supabase project has "Automatically expose new tables" ON: an event
-- trigger grants anon/authenticated full table privileges, and functions get PUBLIC
-- EXECUTE. The explicit grants above add what we need, but they do not remove those
-- inherited privileges, so revoke them here instead of relying on project defaults.
-- This is what makes setup.sql safe on ANY project, whatever the dashboard toggles say.

do $$
declare
    tbl record;
    fn record;
begin
    for tbl in
        select c.oid::regclass as rel
          from pg_class c
          join pg_namespace n on n.oid = c.relnamespace
         where n.nspname = 'public'
           and c.relkind in ('r', 'p', 'v', 'm', 'S')
    loop
        execute format(
            'revoke all on %s from anon, authenticated, public', tbl.rel
        );
    end loop;

    for fn in
        select p.oid::regprocedure as f
          from pg_proc p
          join pg_namespace n on n.oid = p.pronamespace
         where n.nspname = 'public'
    loop
        execute format(
            'revoke all on function %s from public, anon', fn.f
        );
    end loop;
end;
$$;

-- Restore exactly the intended surface: service_role keeps everything, authenticated
-- gets SELECT on the ten app tables and EXECUTE on the owner/device RPCs. Nothing for anon.
grant all on all tables in schema public to service_role;
grant select on all tables in schema public to authenticated;
grant all on all sequences in schema public to service_role;

do $$
declare
    fn record;
begin
    for fn in
        select p.oid::regprocedure as f
          from pg_proc p
          join pg_namespace n on n.oid = p.pronamespace
         where n.nspname = 'public'
           and p.proname <> 'hook_single_owner_signup'
    loop
        execute format(
            'grant execute on function %s to authenticated, service_role', fn.f
        );
    end loop;
end;
$$;

-- The signup hook is Auth-internal: never callable through the Data API.
revoke all on function public.hook_single_owner_signup(jsonb) from public, anon, authenticated;
grant all on function public.hook_single_owner_signup(jsonb) to service_role, supabase_auth_admin;

-- Make the new functions/tables visible to PostgREST immediately.
notify pgrst, 'reload schema';
