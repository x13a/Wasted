const REACHABILITY_PATTERN =
  /failed to fetch|networkerror|load failed|fetch failed|timed? out|timeout|cors|522/i;

export function isReachabilityError(error) {
  if (!error) return false;
  if (error.name === "TypeError") return true;
  return REACHABILITY_PATTERN.test(String(error.message ?? error));
}

export function retryDelayMs(failures) {
  const count = Math.max(1, Number(failures) || 1);
  return Math.min(5_000 * 2 ** (count - 1), 30_000);
}

export function actionUnavailableMessage(reason) {
  return reason === "unreachable"
    ? "Can't reach Supabase. Try again in a moment."
    : "Offline — reconnect before using this action.";
}
