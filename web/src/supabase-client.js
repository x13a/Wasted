import { createClient } from "@supabase/supabase-js";

/** Reads the generated config block; returns null when unconfigured. */
export function readConfig() {
  const config = globalThis.WASTED_CONFIG;
  if (!config || !config.supabaseUrl || !config.supabasePublishableKey) return null;
  return config;
}

export function createSupabaseClient(config) {
  return createClient(config.supabaseUrl, config.supabasePublishableKey, {
    auth: {
      persistSession: true,
      autoRefreshToken: true,
      detectSessionInUrl: false,
    },
  });
}
