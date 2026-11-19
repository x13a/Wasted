#!/usr/bin/env bash
# Negative verifier harness. Requires psql and a disposable database URL. Every drift case runs the
# real supabase/verify.sql after introducing drift; a PASS means verify.sql failed as expected.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
: "${DATABASE_URL:?Set DATABASE_URL to a disposable PostgreSQL/Supabase connection}"

TMP_DIR="$(mktemp -d)"
TMP_SQL="$TMP_DIR/case.sql"
TMP_LOG="$TMP_DIR/case.log"
trap 'rm -rf "$TMP_DIR"' EXIT

cd "$ROOT"

run_failure_case() {
  local name="$1"
  local setup="$2"
  cat >"$TMP_SQL" <<SQL
begin;
$setup
\echo __DRIFT_APPLIED__
\i supabase/verify.sql
rollback;
SQL
  if psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f "$TMP_SQL" >"$TMP_LOG" 2>&1; then
    echo "FAIL: $name — verify.sql did not reject the drift"
    sed -n '1,80p' "$TMP_LOG"
    exit 1
  fi
  if ! grep -q "__DRIFT_APPLIED__" "$TMP_LOG"; then
    echo "FAIL: $name — drift setup did not complete"
    sed -n '1,80p' "$TMP_LOG"
    exit 1
  fi
  if ! grep -qi "verification failed" "$TMP_LOG"; then
    echo "FAIL: $name — failure was not the expected verifier rejection"
    sed -n '1,80p' "$TMP_LOG"
    exit 1
  fi
  echo "PASS: $name — verify.sql rejected the drift"
}

run_failure_case "extra permissive policy" \
  "create policy verify_extra on public.accounts as permissive for select to authenticated using (true);"

run_failure_case "mismatched policy predicate" \
  "drop policy accounts_select_owner on public.accounts; create policy accounts_select_owner on public.accounts for select to authenticated using (true);"

run_failure_case "mismatched policy role" \
  "drop policy accounts_select_owner on public.accounts; create policy accounts_select_owner on public.accounts for select to anon using (id = (select auth.uid()));"

run_failure_case "mismatched policy mode" \
  "drop policy command_events_select_owner on public.command_events; create policy command_events_select_owner on public.command_events as restrictive for select to authenticated using (account_id = (select auth.uid()));"

run_failure_case "PUBLIC default function grant" \
  "alter default privileges for role postgres grant execute on functions to public;"

psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/verify.sql >/dev/null
echo "PASS: undrifted database — verify.sql passed"
