/** Creates a one-time enrollment token using the account's saved lifetime. */
export async function createEnrollmentToken(client) {
  const { data, error } = await client.rpc("create_device_enrollment");
  if (error) throw new Error(error.message);
  const row = Array.isArray(data) ? data[0] : data;
  if (!row || !row.enrollment_token) throw new Error("No enrollment token returned.");
  return { token: row.enrollment_token, expiresAt: row.expires_at };
}

export function tokenExpiryLabel(expiresAt, nowMs = Date.now()) {
  const expires = Date.parse(expiresAt);
  if (Number.isNaN(expires)) return "Expires soon";
  const remaining = Math.max(0, Math.floor((expires - nowMs) / 1000));
  if (remaining === 0) return "Expired — create a new token";
  const minutes = Math.floor(remaining / 60);
  const seconds = remaining % 60;
  return `Expires in ${minutes}:${String(seconds).padStart(2, "0")}`;
}
