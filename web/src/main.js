import { readConfig, createSupabaseClient } from "./supabase-client.js";
import {
  wireAuth,
  currentSession,
  onAuthChange,
  signOut,
  changePassword,
  validatePasswordChange,
} from "./auth.js";
import {
  ensureOwnerAccount,
  updateOwnerHandle,
  validateHandle,
} from "./account.js";
import {
  fetchAccountSettings,
  fetchDashboardCore,
  renameDevice,
  revokeDevice,
  updateDeviceNotes,
} from "./devices.js";
import { createEnrollmentToken, tokenExpiryLabel } from "./enrollment.js";
import { saveAccountSettings, saveAccountLimits, saveDeviceSettings } from "./settings.js";
import { createAction, setDeviceDebugMode, newIdempotencyKey, RESET_TIMER, FORCE_RESET, LOCK_NOW } from "./commands.js";
import { batteryLabel, batteryPercent, batteryTone, isLowBattery } from "./battery.js";
import { devicePlatformLabel } from "./android.js";
import {
  DEVICE_FILTERS,
  filterCount,
  filterDevices,
  normalizeDeviceView,
  sortDevices,
} from "./device-view.js";
import {
  extrapolatedRemaining,
  isRecentlyConnected,
  formatRemaining,
  formatLastContact,
  formatDateTime,
  formatMinutes,
  FRESHNESS_WINDOW_MS,
} from "./timers.js";
import {
  LIMIT_DEFAULTS,
  LIMIT_FIELDS,
  commandExpirySummary,
  formatLimitMinutes,
  limitsFromSettings,
} from "./limits.js";
import {
  $,
  toast,
  setBusy,
  confirmAction,
  showMessage,
  clearMessage,
  copyText,
  wireDialogEnterKeys,
} from "./ui.js";
import { actionUnavailableMessage, isReachabilityError, retryDelayMs } from "./connection.js";
import { installRipple } from "./ripple.js";

const COLLAPSED_DEVICES_KEY = "wasted_dashboard_collapsed_devices";
const HIDDEN_DEVICE_IDS_KEY = "wasted_dashboard_hidden_device_ids";
const HIDDEN_EMAIL_KEY = "wasted_dashboard_hidden_email";
const DEVICE_VIEW_KEY = "wasted_dashboard_device_view";

function loadDeviceViewPrefs() {
  try {
    return normalizeDeviceView(JSON.parse(localStorage.getItem(DEVICE_VIEW_KEY) ?? "{}"));
  } catch {
    return normalizeDeviceView({});
  }
}

function saveDeviceViewPrefs(view) {
  try {
    localStorage.setItem(DEVICE_VIEW_KEY, JSON.stringify(view));
  } catch {
    // View preferences are best effort.
  }
}

function loadStoredBool(key) {
  try {
    return localStorage.getItem(key) === "true";
  } catch {
    return false;
  }
}

function saveStoredBool(key, value) {
  try {
    localStorage.setItem(key, String(value));
  } catch {
    // UI preferences are best effort.
  }
}

function loadStoredIdSet(key) {
  try {
    const value = JSON.parse(localStorage.getItem(key) ?? "[]");
    return new Set(Array.isArray(value) ? value.filter((item) => typeof item === "string") : []);
  } catch {
    return new Set();
  }
}

function saveStoredIdSet(key, values) {
  try {
    localStorage.setItem(key, JSON.stringify([...values]));
  } catch {
    // UI preferences are best effort; they never block the dashboard.
  }
}

const state = {
  client: null,
  session: null,
  handle: "",
  handleLoaded: false,
  accountEmail: "",
  accountEmailHidden: loadStoredBool(HIDDEN_EMAIL_KEY),
  online: navigator.onLine !== false,
  connectionReason: null,
  dataLoaded: false,
  loading: false,
  loadError: "",
  settingsLoading: false,
  settingsError: "",
  settingsRequestId: 0,
  cacheSavedAt: 0,
  channel: null,
  realtimeAccountId: null,
  refreshTimeout: null,
  retryTimer: null,
  refreshFailures: 0,
  refreshInFlight: false,
  explicitSignOut: false,
  connection: { projectUrl: "", publishableKey: "", dashboardUrl: "" },
  lastDeviceCount: null,
  tokenTimer: null,
  liveTimers: [],
  commandProgress: null,
  commandProgressTimer: null,
  targetScope: "selected",
  selectedDeviceIds: new Set(),
  deviceQuery: "",
  deviceView: loadDeviceViewPrefs(),
  collapsedDeviceIds: loadStoredIdSet(COLLAPSED_DEVICES_KEY),
  hiddenDeviceIds: loadStoredIdSet(HIDDEN_DEVICE_IDS_KEY),
  data: {
    devices: [],
    statusByDevice: new Map(),
    latestCommand: new Map(),
    effectiveSettingsByDevice: new Map(),
    notesByDevice: new Map(),
    settings: null,
  },
};

const CACHE_PREFIX = "wasted_dashboard_cache_v4_";
const ACTIVE_WINDOW_LABEL = `${FRESHNESS_WINDOW_MS / 1000} seconds`;
const THEME_KEY = "wasted_dashboard_theme";
const VALID_THEMES = new Set(["system", "light", "dark"]);

const config = readConfig();
if (!config) {
  const banner = $("#config-error");
  banner.textContent =
    "This dashboard is not configured: set SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY in .env " +
    "with your Supabase URL and publishable key.";
  banner.hidden = false;
  $("#boot-loading").hidden = true;
  $("#auth-section").hidden = true;
} else {
  boot(config);
}

async function boot(cfg) {
  applyFooter(cfg);
  wireThemePicker();
  applyConnectionInfo(cfg);
  applySetupLinks(cfg);
  applyOptionalLink("#footer-privacy", cfg.privacyUrl);
  applyOptionalLink("#footer-support", cfg.supportUrl);

  state.client = createSupabaseClient(cfg);
  wireInstallPrompt();
  state.session = await currentSession(state.client);

  wireAuth(state.client);
  wireDashboard();
  wireDashboardTabs();
  wireSignOut();
  wireHeaderMenu();
  wireDialogEnterKeys();
  installRipple();
  onAuthChange(state.client, (session, event) => {
    if (event === "SIGNED_OUT" && state.session && !state.explicitSignOut) {
      toast("Session ended — sign in again.", "info");
    }
    state.session = session;
    renderAuthState();
  });

  setInterval(tickTimers, 1000);
  setInterval(() => {
    if (!document.hidden) refreshDashboard();
  }, 60_000);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) refreshDashboard();
  });
  let offlineTimer;
  window.addEventListener("online", () => {
    clearTimeout(offlineTimer);
    clearTimeout(state.retryTimer);
    state.retryTimer = null;
    state.refreshFailures = 0;
    setDashboardOnline(true);
    refreshDashboard();
  });
  window.addEventListener("offline", () => {
    clearTimeout(offlineTimer);
    clearTimeout(state.retryTimer);
    state.retryTimer = null;
    offlineTimer = setTimeout(() => {
      const cached = loadCachedDashboard();
      setDashboardOnline(false, "offline");
      setDashboardLoadState(cached
        ? {}
        : { error: "You're offline and no cached devices are available. Reconnect, then retry." });
      renderSettings();
      renderDevices();
    }, 400);
  });

  renderAuthState();
}

function applyConnectionInfo(cfg) {
  const projectUrl = String(cfg.supabaseUrl ?? "").trim();
  const publishableKey = String(cfg.supabasePublishableKey ?? "").trim();
  const section = $("#menu-connection");
  if (!section || !projectUrl || !publishableKey) return;

  const localPreview = ["localhost", "127.0.0.1", "::1"].includes(location.hostname);
  let dashboardUrl = "";
  if (!localPreview) {
    const current = new URL(location.href);
    current.hash = "";
    current.search = "";
    dashboardUrl = current.origin + current.pathname;
  }
  if (!dashboardUrl) dashboardUrl = String(cfg.dashboardUrl ?? "").trim();

  state.connection = { projectUrl, publishableKey, dashboardUrl };
  $("#menu-project-url").textContent = projectUrl;
  $("#menu-publishable-key").textContent = publishableKey;
  const dashboardField = $("#menu-dashboard-url-field");
  if (dashboardField) {
    $("#menu-dashboard-url").textContent = dashboardUrl;
    dashboardField.hidden = !dashboardUrl;
  }
  section.hidden = false;
  $("#menu-divider").hidden = false;

  const fields = [
    ["#copy-project-url", "projectUrl", "Project URL"],
    ["#copy-publishable-key", "publishableKey", "Publishable key"],
    ["#copy-dashboard-url", "dashboardUrl", "Dashboard URL"],
  ];
  for (const [selector, key, label] of fields) {
    $(selector)?.addEventListener("click", async () => {
      try {
        await copyText(state.connection[key]);
        toast(`${label} copied.`, "success");
      } catch (_) {
        toast(`Could not copy ${label.toLowerCase()}.`, "error");
      }
    });
  }
}

function storedTheme() {
  try {
    const value = localStorage.getItem(THEME_KEY);
    return VALID_THEMES.has(value) ? value : "system";
  } catch {
    return "system";
  }
}

function updateThemeColor() {
  const meta = $("#theme-color-meta");
  if (!meta) return;
  const background = getComputedStyle(document.documentElement)
    .getPropertyValue("--background")
    .trim();
  if (background) meta.content = background;
}

function applyTheme(theme, { persist = true } = {}) {
  const selected = VALID_THEMES.has(theme) ? theme : "system";
  if (selected === "system") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.dataset.theme = selected;
  if (persist) {
    try {
      localStorage.setItem(THEME_KEY, selected);
    } catch {
      // The theme still applies for this page when storage is unavailable.
    }
  }
  for (const button of document.querySelectorAll("[data-theme-option]")) {
    button.setAttribute("aria-pressed", String(button.dataset.themeOption === selected));
  }
  updateThemeColor();
}

function wireThemePicker() {
  const buttons = [...document.querySelectorAll("[data-theme-option]")];
  if (!buttons.length) return;
  applyTheme(storedTheme(), { persist: false });
  for (const button of buttons) {
    button.addEventListener("click", () => applyTheme(button.dataset.themeOption));
  }
  const media = window.matchMedia("(prefers-color-scheme: dark)");
  media.addEventListener("change", () => {
    if (storedTheme() === "system") updateThemeColor();
  });
}

/** Clears typed credentials so a new sign-in never inherits the previous owner's input. */
function resetCredentialForms() {
  for (const selector of ["#account-password-form"]) {
    const form = $(selector);
    if (form) form.reset();
  }
  for (const selector of ["#account-password-message"]) {
    clearMessage(selector);
  }
  for (const toggle of document.querySelectorAll("[data-password-toggle]")) {
    const input = document.getElementById(toggle.dataset.passwordToggle);
    if (!input || input.id.startsWith("sign-in") || input.id.startsWith("create-")) continue;
    input.type = "password";
    toggle.setAttribute("aria-pressed", "false");
    toggle.setAttribute("aria-label", "Show password");
  }
}

function renderAccountEmail() {
  const email = state.accountEmail;
  const value = $("#account-email");
  const button = $("#account-email-toggle");
  if (value) {
    value.textContent = !email ? "—" : state.accountEmailHidden ? "••••••••••••••••" : email;
  }
  if (!button) return;
  button.disabled = !email;
  button.setAttribute("aria-pressed", String(state.accountEmailHidden));
  button.setAttribute("aria-label", state.accountEmailHidden
    ? "Show signed-in email"
    : "Hide signed-in email");
  button.title = state.accountEmailHidden
    ? "Show the signed-in email"
    : "Hide the signed-in email";
  setButtonIcon(button, state.accountEmailHidden ? "eye-off" : "eye");
}

function toggleAccountEmailVisibility() {
  if (!state.accountEmail) return;
  state.accountEmailHidden = !state.accountEmailHidden;
  saveStoredBool(HIDDEN_EMAIL_KEY, state.accountEmailHidden);
  renderAccountEmail();
}

function applyFooter(cfg) {
  const product = cfg.productName || "Wasted Remote";
  document.title = product;
  const title = $("#app-title .brand-title");
  if (title) title.textContent = product;
  $("#footer-product").textContent = product;
  $("#footer-version").textContent = cfg.version ? `— v${cfg.version}` : "";
  $("#footer-version").hidden = !cfg.version;
}

function applySetupLinks(cfg) {
  const ref = projectRef(cfg.supabaseUrl);
  const base = ref
    ? `https://supabase.com/dashboard/project/${ref}`
    : "https://supabase.com/dashboard";
  const project = $("#setup-project-link");
  if (project) project.href = base;
  const sql = $("#setup-sql-link");
  if (sql) sql.href = `${base}/sql/new`;
  const auth = $("#setup-auth-link");
  if (auth) auth.href = `${base}/auth/providers`;
  const hooks = $("#setup-hooks-link");
  if (hooks) hooks.href = `${base}/auth/hooks`;
  // Deep link for the Account tab's email-change guidance.
  const users = $("#account-users-link");
  if (users) users.href = `${base}/auth/users`;
}

function projectRef(value) {
  try {
    const host = new URL(value).hostname;
    if (!host.endsWith(".supabase.co")) return null;
    const ref = host.slice(0, -".supabase.co".length);
    if (!/^[a-z0-9-]+$/.test(ref) || /^YOUR-/.test(ref)) return null;
    return ref;
  } catch {
    return null;
  }
}

function applyOptionalLink(selector, url) {
  const link = $(selector);
  let parsed = null;
  try {
    parsed = url ? new URL(url) : null;
  } catch {
    parsed = null;
  }
  if (!parsed || parsed.protocol !== "https:") {
    link.hidden = true;
    link.removeAttribute("href");
    return;
  }
  link.href = parsed.href;
  link.hidden = false;
}

function setDashboardLoadState({ loading = false, error = "", refreshing = false } = {}) {
  state.loading = loading;
  state.loadError = error;
  const panel = $("#dashboard-loading");
  const title = $("#dashboard-loading-title");
  const detail = $("#dashboard-loading-detail");
  const retry = $("#dashboard-retry");
  const dashboard = $("#dashboard");
  if (dashboard) dashboard.setAttribute("aria-busy", String(loading));
  if (!panel) return;
  panel.hidden = !loading && !error;
  panel.dataset.state = error ? "error" : "loading";
  if (title) {
    title.textContent = error
      ? "Couldn't load dashboard"
      : refreshing
        ? "Refreshing dashboard…"
        : "Loading dashboard…";
  }
  if (detail) {
    const savedAt = state.cacheSavedAt
      ? ` from ${formatDateTime(state.cacheSavedAt)}`
      : "";
    detail.textContent = error || (refreshing
      ? `Showing saved data${savedAt} while checking for updates.`
      : "Fetching your devices and settings.");
  }
  if (retry) retry.hidden = !error;
}

function setDeviceChromeVisible(visible) {
  const summary = $("#device-summary");
  const toolbar = document.querySelector(".device-toolbar");
  if (summary) summary.hidden = !visible;
  if (toolbar) toolbar.hidden = !visible;
}

function renderDeviceSkeleton() {
  const summary = $("#device-summary");
  const devices = $("#devices");
  if (summary) {
    summary.className = "stat-grid skeleton-grid";
    summary.hidden = false;
    summary.replaceChildren(
      ...Array.from({ length: 4 }, () => el("div", { className: "skeleton-card" }, [
        el("div", { className: "skeleton-line short" }),
        el("div", { className: "skeleton-line tall" }),
        el("div", { className: "skeleton-line medium" }),
      ])),
    );
  }
  if (devices) {
    devices.className = "device-grid skeleton-devices";
    devices.hidden = false;
    devices.setAttribute("aria-busy", "true");
    devices.replaceChildren(
      ...Array.from({ length: 2 }, () => el("div", { className: "skeleton-card" }, [
        el("div", { className: "skeleton-line medium" }),
        el("div", { className: "skeleton-line short" }),
        el("div", { className: "skeleton-line" }),
        el("div", { className: "skeleton-line medium" }),
      ])),
    );
  }
  const toolbar = document.querySelector(".device-toolbar");
  if (toolbar) toolbar.hidden = true;
}

function renderAuthState() {
  const signedIn = Boolean(state.session);
  $("#boot-loading").hidden = true;
  $("#auth-section").hidden = signedIn;
  $("#dashboard").hidden = !signedIn;
  $("#sign-out").hidden = !signedIn;

  if (signedIn) {
    if (!state.dataLoaded) setDashboardLoadState({ loading: true });
    refreshDashboard();
  } else {
    teardownRealtime();
    clearEnrollmentToken();
    state.handle = "";
    state.handleLoaded = false;
    state.dataLoaded = false;
    state.loading = false;
    state.loadError = "";
    state.settingsLoading = false;
    state.settingsError = "";
    state.settingsRequestId += 1;
    state.cacheSavedAt = 0;
    state.refreshFailures = 0;
    state.lastDeviceCount = null;
    clearCommandProgress();
    setDashboardLoadState();
    setDeviceChromeVisible(false);
    state.data = {
      devices: [],
      statusByDevice: new Map(),
      latestCommand: new Map(),
      effectiveSettingsByDevice: new Map(),
      notesByDevice: new Map(),
      settings: null,
    };
    state.selectedDeviceIds.clear();
    $("#account-panel").hidden = true;
    state.accountEmail = "";
    renderAccountEmail();
    $("#account-message").textContent = "";
    resetCredentialForms();
  }
  if (!signedIn) closeHeaderMenu();
  syncHeaderMenu();
  setDashboardOnline(navigator.onLine !== false);
}

async function refreshDashboard() {
  if (!state.session || state.refreshInFlight) return;
  state.refreshInFlight = true;
  if (!state.dataLoaded) {
    const hasCachedDevices = loadCachedDashboard({ requireDevices: true });
    setDashboardLoadState({ loading: true, refreshing: hasCachedDevices });
    if (hasCachedDevices) {
      renderSettings();
      renderDevices();
    } else {
      renderDeviceSkeleton();
    }
  } else {
    setDashboardLoadState();
  }

  try {
    if (!navigator.onLine) {
      loadCachedDashboard();
      setDashboardOnline(false, "offline");
      state.loading = false;
      setDashboardLoadState(state.dataLoaded
        ? {}
        : { error: "You're offline and no cached devices are available. Reconnect, then retry." });
      renderSettings();
      renderDevices();
      return;
    }

    try {
      const settingsRequest = ++state.settingsRequestId;
      const settingsPromise = fetchAccountSettings(state.client)
        .then((settings) => ({ settings, error: null }))
        .catch((error) => ({ settings: null, error }));
      const [handle, data] = await Promise.all([
        state.handleLoaded
          ? Promise.resolve(state.handle)
          : ensureOwnerAccount(state.client, state.session),
        fetchDashboardCore(state.client),
      ]);
      state.handle = handle;
      state.handleLoaded = true;
      $("#account-line").textContent = state.handle
        ? `Account: ${state.handle}`
        : `Signed in as ${state.session.user.email ?? "owner"}`;
      state.accountEmail = state.session.user.email ?? "";
      renderAccountEmail();
      $("#account-panel").hidden = !state.handle;
      if (state.handle && document.activeElement !== $("#account-handle")) {
        $("#account-handle").value = state.handle;
      }

      const existingSettings = state.data.settings;
      state.data = { ...data, settings: existingSettings };
      state.settingsLoading = !existingSettings;
      state.settingsError = "";
      state.dataLoaded = true;
      state.loading = false;
      state.loadError = "";
      setDashboardLoadState();
      state.refreshFailures = 0;
      clearTimeout(state.retryTimer);
      state.retryTimer = null;
      if (
        state.lastDeviceCount !== null &&
        state.data.devices.length > state.lastDeviceCount &&
        !$("#token-output").hidden
      ) {
        clearEnrollmentToken();
        toast("Device enrolled. Waiting for its first sync.", "success");
      }
      state.lastDeviceCount = state.data.devices.length;
      saveDashboardCache();
      setDashboardOnline(true);
      setDeviceChromeVisible(true);
      renderSettings();
      renderDevices();
      void settingsPromise.then(({ settings, error }) => {
        if (settingsRequest !== state.settingsRequestId || !state.session) return;
        state.settingsLoading = false;
        if (error) {
          state.settingsError = error.message;
        } else {
          state.data.settings = settings;
          state.settingsError = "";
          saveDashboardCache();
        }
        renderSettings();
      });
      if (state.commandProgress) void refreshCommandProgress();
      subscribeRealtime(state.session.user.id);
      $("#offline-banner").hidden = true;
    } catch (error) {
      if (error.code === "OWNER_EXISTS" || error.code === "MISSING_PROFILE") {
        await signOut(state.client);
        showMessage("#auth-message", error.message, "error");
        return;
      }
      loadCachedDashboard();
      state.refreshFailures += 1;
      state.loading = false;

      if (!navigator.onLine) {
        setDashboardOnline(false, "offline");
        setDashboardLoadState(state.dataLoaded
          ? {}
          : { error: "You're offline and no cached devices are available. Reconnect, then retry." });
        renderSettings();
        renderDevices();
        return;
      }

      if (isReachabilityError(error)) {
        setDashboardOnline(false, "unreachable");
        setDashboardLoadState(state.dataLoaded
          ? {}
          : { error: "Could not reach Supabase. Check your connection and project URL, then retry." });
        renderSettings();
        renderDevices();
        if (state.refreshFailures >= 2 || !state.dataLoaded) {
          toast("Could not reach Supabase. Showing cached data; retrying.", "error");
        } else {
          toast("Could not refresh from Supabase. Retrying.", "error");
        }
        scheduleDashboardRetry();
      } else {
        setDashboardLoadState({ error: `Could not load dashboard: ${error.message}` });
        renderSettings();
        renderDevices();
        toast(`Refresh failed: ${error.message}`, "error");
      }
    }
  } finally {
    state.refreshInFlight = false;
  }
}

function scheduleDashboardRetry() {
  if (state.retryTimer) return;
  state.retryTimer = setTimeout(() => {
    state.retryTimer = null;
    if (!document.hidden) refreshDashboard();
  }, retryDelayMs(state.refreshFailures));
}

function dashboardCacheKey() {
  return state.session?.user?.id ? `${CACHE_PREFIX}${state.session.user.id}` : "";
}

function saveDashboardCache() {
  if (!state.dataLoaded || !state.session) return;
  const key = dashboardCacheKey();
  if (!key) return;
  try {
    state.cacheSavedAt = Date.now();
    localStorage.setItem(
      key,
      JSON.stringify({
        savedAt: state.cacheSavedAt,
        devices: state.data.devices,
        statusByDevice: [...state.data.statusByDevice.entries()],
        latestCommand: [...state.data.latestCommand.entries()],
        effectiveSettingsByDevice: [...state.data.effectiveSettingsByDevice.entries()],
        notesByDevice: [...state.data.notesByDevice.entries()],
        settings: state.data.settings,
      }),
    );
  } catch {
    // Cache is best effort; the in-memory dashboard still works.
  }
}

function loadCachedDashboard({ requireDevices = false } = {}) {
  if (state.dataLoaded) return true;
  const key = dashboardCacheKey();
  if (!key) return false;
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return false;
    const cached = JSON.parse(raw);
    if (!Array.isArray(cached.devices)) return false;
    if (requireDevices && !cached.devices.length) return false;
    state.data = {
      devices: cached.devices,
      statusByDevice: new Map(cached.statusByDevice ?? []),
      latestCommand: new Map(cached.latestCommand ?? []),
      effectiveSettingsByDevice: new Map(cached.effectiveSettingsByDevice ?? []),
      notesByDevice: new Map(cached.notesByDevice ?? []),
      settings: cached.settings ?? null,
    };
    state.dataLoaded = true;
    state.cacheSavedAt = Number(cached.savedAt) || 0;
    return true;
  } catch {
    return false;
  }
}

function clearDashboardCache() {
  const key = dashboardCacheKey();
  if (!key) return;
  try {
    localStorage.removeItem(key);
  } catch {
    // Cache is optional.
  }
}

function requireOnline(selector) {
  if (state.online) return true;
  showMessage(selector, actionUnavailableMessage(state.connectionReason), "error");
  return false;
}

function setDashboardOnline(online, reason = state.connectionReason ?? "offline") {
  state.online = online;
  state.connectionReason = online ? null : reason;

  const banner = $("#offline-banner");
  const offline = reason === "offline";
  if (banner) {
    banner.classList.toggle("warning", !online && !offline);
    banner.classList.toggle("error", online || offline);
  }
  if (!state.session) {
    if (banner) {
      banner.textContent = offline
        ? "Offline — sign-in, account creation, and dashboard access require a connection. Reconnect and try again."
        : "Can't reach Supabase — sign-in and account creation are unavailable. Retrying automatically.";
      banner.hidden = online;
    }
    for (const selector of ["#sign-in", "#create-account"]) {
      const button = $(selector);
      if (button) button.disabled = !online;
    }
    return;
  }

  if (banner) {
    if (online) {
      banner.hidden = true;
    } else {
      const detail = state.dataLoaded
        ? state.cacheSavedAt
          ? ` Showing cached data last updated ${formatDateTime(state.cacheSavedAt)}.`
          : " Showing the data already loaded in this session."
        : " No cached dashboard data is available.";
      banner.textContent = offline
        ? "Offline —" +
          detail +
          " Commands, settings saves, enrollment, and refresh are disabled until the connection returns."
        : "Can't reach Supabase —" +
          detail +
          " Commands, settings saves, enrollment, and refresh are disabled until Supabase responds. Retrying automatically.";
      banner.hidden = false;
    }
  }

  const selectors = [
    "#create-token",
    "#settings-form button[type=submit]",
    "#limits-form button[type=submit]",
    "#limits-reset",
    "#account-form button[type=submit]",
    "#account-password-form button[type=submit]",
    "#target-all",
    "#target-selected",
  ];
  for (const selector of selectors) {
    const button = $(selector);
    if (button) button.disabled = !online;
  }
  document
    .querySelectorAll(
      "[data-device-rename], [data-device-revoke], [data-device-settings], [data-device-notes], [data-device-select], [data-debug-device]",
    )
    .forEach((button) => {
      button.disabled = !online || button.dataset.debugUnknown === "true";
    });
  if ($("#action-scope")) renderCommandScope(state.data.devices);
}

function subscribeRealtime(accountId) {
  if (state.channel && state.realtimeAccountId === accountId) return;
  teardownRealtime();
  const filter = `account_id=eq.${accountId}`;
  state.realtimeAccountId = accountId;
  state.channel = state.client
    .channel("wasted-dashboard")
    .on("postgres_changes", { event: "*", schema: "public", table: "devices", filter }, scheduleRefresh)
    .on("postgres_changes", { event: "*", schema: "public", table: "account_settings", filter }, scheduleRefresh)
    .on("postgres_changes", { event: "*", schema: "public", table: "device_settings", filter }, scheduleRefresh)
    .on("postgres_changes", { event: "*", schema: "public", table: "device_status", filter }, scheduleRefresh)
    .on("postgres_changes", { event: "*", schema: "public", table: "device_commands", filter }, scheduleRefresh)
    .subscribe((status) => {
      if (status === "SUBSCRIBED") {
        if (state.online) $("#offline-banner").hidden = true;
        scheduleRefresh();
      } else if (status === "CHANNEL_ERROR" || status === "TIMED_OUT") {
        // Realtime is only an invalidation channel; HTTP commands still work.
        scheduleRefresh();
      }
    });
}

function teardownRealtime() {
  if (!state.channel) return;
  const channel = state.channel;
  state.channel = null;
  state.realtimeAccountId = null;
  Promise.resolve(state.client.removeChannel(channel)).catch(() => {});
}

function scheduleRefresh() {
  if (document.hidden) return;
  clearTimeout(state.refreshTimeout);
  state.refreshTimeout = setTimeout(() => refreshDashboard(), 300);
}

function renderDevices() {
  const now = Date.now();
  const container = $("#devices");
  container.replaceChildren();
  container.removeAttribute("aria-busy");
  state.liveTimers = [];

  if (state.loading && !state.dataLoaded) {
    renderDeviceSkeleton();
    return;
  }

  if (!state.dataLoaded) {
    setDeviceChromeVisible(false);
    container.className = "device-grid";
    container.append(
      el("div", { className: "empty-state" }, [
        el("p", { text: state.loadError || "Devices haven't loaded yet." }),
        el("button", {
          className: "small",
          text: "Retry",
          icon: "refresh",
          attrs: { type: "button", "data-dashboard-retry": "" },
        }),
      ]),
    );
    return;
  }

  const { devices, statusByDevice, latestCommand, effectiveSettingsByDevice, notesByDevice } = state.data;
  const activeWindow = FRESHNESS_WINDOW_MS;
  const activeWindowLabel = ACTIVE_WINDOW_LABEL;
  const filteredDevices = filterDevices(
    devices,
    statusByDevice,
    notesByDevice,
    now,
    activeWindow,
    state.deviceView.filter,
    state.deviceQuery,
  );
  const visibleDevices = sortDevices(
    filteredDevices,
    statusByDevice,
    state.deviceView.sort,
    activeWindow,
  );
  container.className = `device-grid layout-${state.deviceView.layout}`;
  setDeviceChromeVisible(true);
  pruneSelectedDevices(devices);
  renderDeviceSummary(devices, statusByDevice, now, activeWindow, activeWindowLabel);
  renderDeviceToolbar(devices, statusByDevice, notesByDevice, now, filteredDevices.length);
  const unavailableWithoutCache = !state.online && !state.dataLoaded;
  const unavailablePrefix =
    state.connectionReason === "offline" ? "Offline" : "Can't reach Supabase";

  if (!devices.length) {
    container.append(
      el("div", { className: "empty-state" }, [
        el("p", {
          text: unavailableWithoutCache
            ? `${unavailablePrefix} — no cached dashboard data is available.`
            : "No devices enrolled yet.",
        }),
        el("p", {
          className: "hint",
          text: unavailableWithoutCache
            ? state.connectionReason === "offline"
              ? "Reconnect to load your devices. Commands and settings saves are disabled while offline."
              : "Supabase is unreachable, so cached data cannot be refreshed. Commands and settings saves are disabled until it responds."
            : "Enable Remote Management in the app, create an enrollment token below, " +
              "and claim it in the app. The device appears here after its first sync.",
        }),
      ]),
    );
    setDashboardOnline(state.online);
    return;
  }

  if (!visibleDevices.length) {
    container.append(
      el("div", { className: "empty-state" }, [
        el("p", { text: "No devices match these filters." }),
        el("p", {
          className: "hint",
          text: "Clear the search or choose a different device filter.",
        }),
        el("button", {
          className: "small",
          text: "Clear filters",
          icon: "close",
          attrs: { type: "button", "data-clear-device-filters": "" },
        }),
      ]),
    );
    setDashboardOnline(state.online);
    renderCommandScope(devices);
    return;
  }

  for (const device of visibleDevices) {
    const status = statusByDevice.get(device.id) ?? null;
    const effective = effectiveSettingsByDevice.get(device.id) ?? null;
    const command = latestCommand.get(device.id) ?? null;
    const fresh = isRecentlyConnected(status, now, activeWindow);
    const timerSpan = el("span", {
      className: status?.timer_running ? "timer-live" : "timer-inactive",
      text: timerText(status, now),
    });
    const lastContactEl = el("dd", { text: formatLastContact(status, now) });
    const enrolledAt = Date.parse(device.enrolled_at);
    const enrolledText = Number.isNaN(enrolledAt) ? "unknown" : formatDateTime(enrolledAt);
    const collapsed = state.collapsedDeviceIds.has(device.id);
    const hidden = state.hiddenDeviceIds.has(device.id);
    const note = notesByDevice.get(device.id)?.notes ?? "";
    const idValue = hidden ? "••••••••••••••••" : device.id;
    const listSummary = el("div", { className: "device-list-summary" }, [
      statusBadge(fresh ? "Active" : "Inactive", fresh ? "active" : "inactive"),
      el("span", { className: "muted", text: `Last contact: ${formatLastContact(status, now)}` }),
      el("span", { className: "muted", text: `Enrolled: ${enrolledText}` }),
      statusBadge(
        !status ? "Debug unknown" : status.debug_mode ? "Debug ON" : "Debug OFF",
        !status ? "unknown" : status.debug_mode ? "on" : "off",
      ),
    ]);
    const idRow = el("div", { className: "device-id-row" }, [
      el("span", { className: "device-id-label", text: "ID" }),
      el("span", { className: "device-id-value", text: idValue }),
      el("button", {
        className: "icon-button",
        icon: hidden ? "eye-off" : "eye",
        attrs: {
          type: "button",
          "data-device-id-toggle": device.id,
          "aria-pressed": String(hidden),
          "aria-label": hidden ? "Show device ID" : "Hide device ID",
          title: hidden ? "Show the full device ID" : "Hide the device ID",
        },
      }),
    ]);
    const collapseButton = el("button", {
      className: "card-toggle",
      icon: collapsed ? "chevron-right" : "chevron-down",
      attrs: {
        type: "button",
        "data-device-collapse": device.id,
        "aria-expanded": String(!collapsed),
        "aria-label": collapsed ? "Expand device card" : "Collapse device card",
        title: collapsed ? "Expand this device card" : "Collapse this device card",
      },
    });
    const card = el("article", {
      className: `device-card${collapsed ? " collapsed" : ""}`,
    }, [
      el("div", { className: "device-card-head" }, [
        el("div", { className: "device-card-title" }, [
          iconNode("devices"),
          el("h3", { text: device.name || "Unnamed device" }),
        ]),
        el("div", { className: "device-card-head-actions" }, [
          batteryPill(status, status?.battery_percent == null
            ? "The phone has not reported a battery level yet."
            : `Battery reported by the phone${status.battery_charging ? " · charging" : ""}.`, { fresh }),
          collapseButton,
        ]),
      ]),
      el("div", { className: "device-card-body" }, [
        listSummary,
        idRow,
        el("div", { className: "device-actions" }, [
          el("button", {
            className: "small",
            text: "Settings",
            icon: "controls",
            attrs: {
              type: "button",
              "data-device-settings": device.id,
              title: "Set this device's remote settings or inherit the global values",
            },
          }),
          el("button", {
            className: "small",
            text: "Rename",
            icon: "pencil",
            attrs: {
              type: "button",
              "data-device-rename": device.id,
              "data-device-name": device.name || "",
              title: "Rename this device in the dashboard only",
            },
          }),
          el("button", {
            className: "small danger-outline",
            text: "Revoke",
            icon: "trash",
            attrs: {
              type: "button",
              "data-device-revoke": device.id,
              title: "Stop remote commands for this device without wiping it",
            },
          }),
        ]),
        ...(status
          ? []
          : [
              el("p", {
                className: "hint",
                text: "Waiting for this device's first sync.",
              }),
            ]),
        el("section", { className: "device-notes" }, [
          el("div", { className: "device-notes-head" }, [
            iconNode("pencil"),
            el("h4", { text: "Notes" }),
            el("button", {
              className: "small quiet",
              text: note ? "Edit" : "Add note",
              icon: "pencil",
              attrs: {
                type: "button",
                "data-device-notes": device.id,
                title: note ? "Edit this device's private notes" : "Add private notes to this device",
              },
            }),
          ]),
          el("p", {
            className: `device-notes-text${note ? "" : " empty"}`,
            text: note || "No notes yet.",
          }),
        ]),
        el("dl", {}, [
          fieldTerm(
            "Connection",
            `Active means a status update arrived within the last ${activeWindowLabel}; the device normally reports about once a minute. Inactive means no contact in that window. Colour key: 🟢 Active · 🔴 Inactive.`,
            "activity",
          ),
          el("dd", {}, [
            statusBadge(fresh ? "Active" : "Inactive", fresh ? "active" : "inactive", fresh
              ? `Contacted the server within the last ${activeWindowLabel}.`
              : `No contact in the last ${activeWindowLabel}. The exact time is shown below.`),
          ]),
          fieldTerm(
            "Last contact",
            "The last successful status sync. It normally updates about once a minute, and immediately after a command is applied.",
            "clock",
          ),
          lastContactEl,
          fieldTerm(
            "Enrolled",
            "The date and time this device claimed its enrollment token and joined this account.",
            "clock",
          ),
          el("dd", { text: enrolledText }),
          fieldTerm(
            "Battery",
            "Battery level and charging state from the last status sync. If the device is inactive, this is the last known value, not a live reading. Unknown is not 0%. Colour key: 🟢 healthy · 🟡 21–50% · 🔴 20% or less · ⚪ inactive/unknown.",
            "battery",
          ),
          el("dd", {}, [
            batteryPill(status, status?.battery_percent == null
              ? "No battery level reported yet."
              : "Reported at the last status sync; not a live millisecond feed.", { fresh }),
          ]),
          fieldTerm(
            "Settings status",
            "Current means the phone has applied the latest global and per-device override revisions. Pending means it has not synced them yet. Colour key: 🟢 Current · 🟡 Pending.",
            "controls",
          ),
          el("dd", {}, [settingsStatusNode(status, effective)]),
          fieldTerm(
            "Live countdown",
            "Actual time remaining before the Lock trigger fires. It starts after the phone locks or the screen turns off, is cancelled when unlocked, and stays inactive while the Lock trigger is OFF on the phone.",
            "clock",
          ),
          el("dd", {}, [timerSpan]),
          fieldTerm(
            "Lock trigger",
            "The ON/OFF switch that arms the Lock workflow. It is not a timer and shows no remaining time. The saved setting is this device's override, otherwise the global value. Colour key: 🟢 ON · 🔴 OFF.",
            "lock",
          ),
          el("dd", {}, [
            desiredVsActualNode(effective?.lock_trigger_enabled, status?.actual_lock_trigger_enabled, formatEnabled),
          ]),
          fieldTerm(
            "Lock delay setting",
            "The effective wait before the Lock trigger fires — global default or this device's override, not time remaining. The phone reports it in whole minutes. It runs only while the trigger is ON.",
            "clock",
          ),
          el("dd", {}, [
            desiredVsActualNode(effective?.lock_timer_minutes, status?.actual_lock_timer_minutes, formatMinutes),
          ]),
          fieldTerm(
            "USB trigger",
            "When ON, connecting USB data to a locked device can fire the USB trigger. The effective value is the global default unless this device has an override. Colour key: 🟢 ON · 🔴 OFF.",
            "usb",
          ),
          el("dd", {}, [
            desiredVsActualNode(effective?.usb_trigger_enabled, status?.actual_usb_trigger_enabled, formatEnabled),
          ]),
          fieldTerm(
            "Debug Mode",
            "ON blocks Force Reset; Reset Timer and settings sync still work. The switch sends a command to this device and asks for confirmation. Colour key: 🟢 ON (safe) · 🔴 OFF (destructive commands allowed).",
            "bug",
          ),
          el("dd", {}, [
            statusBadge(
              !status ? "Unknown" : status.debug_mode ? "ON · wipes blocked" : "OFF",
              !status ? "unknown" : status.debug_mode ? "on" : "off",
              !status
                ? "Reported by the device after its first sync."
                : status.debug_mode
                  ? "ON blocks Force Reset; Reset Timer and settings sync still work."
                  : "OFF allows destructive remote commands such as Force Reset.",
            ),
            el("span", { className: "inline-actions" }, [debugSwitch(device, status)]),
          ]),
          fieldTerm(
            "Reset support",
            "Whether the device can run a reset with its current Device Admin/Device Owner protections. Android 14+ installs that are not Device Owner may use a best-effort wipe. Colour key: 🟢 Supported · 🔴 Not supported.",
            "shield",
          ),
          el("dd", {}, [
            !status
              ? statusBadge("Unknown", "unknown")
              : status.reset_supported
                ? statusBadge("Supported", "supported")
                : statusBadge("Not supported", "danger"),
          ]),
          fieldTerm(
            "App / OS",
            "The app version and Android release reported directly by the device.",
            "devices",
          ),
          el("dd", { text: devicePlatformLabel(status) }),
          fieldTerm(
            "Last command",
            "The newest remote command and its current state. The timestamp is when it was queued.",
            "send",
          ),
          el("dd", {}, [describeCommandNode(command)]),
        ]),
        el("p", {
          className: "card-note",
          text: "Settings show the saved setting (global default plus any device override). Device reports is the last value from the phone. The Live countdown is actual time left; it is inactive while unlocked or while the Lock trigger is OFF.",
        }),
      ]),
    ]);

    container.append(card);
    state.liveTimers.push({ el: timerSpan, status, lastContactEl });
  }
  setDashboardOnline(state.online);
  renderCommandScope(devices);
}

function renderDeviceSummary(devices, statusByDevice, now, activeWindow, activeWindowLabel) {
  const container = $("#device-summary");
  if (!container) return;
  container.className = "stat-grid";
  if (!state.dataLoaded && !state.online) {
    container.replaceChildren();
    return;
  }
  const active = devices.filter((device) =>
    isRecentlyConnected(statusByDevice.get(device.id), now, activeWindow),
  ).length;
  const debug = devices.filter((device) => statusByDevice.get(device.id)?.debug_mode).length;
  const lowBattery = devices.filter((device) => isLowBattery(statusByDevice.get(device.id))).length;
  container.replaceChildren(
    summaryCard("Devices", devices.length, "enrolled", "", {
      icon: "devices",
      info: "Every device currently enrolled in this account, including offline devices. Revoked devices are removed from this list.",
    }),
    summaryCard("Active", active, `within ${activeWindowLabel}`, active ? "success" : "muted", {
      icon: "activity",
      info: `Devices that contacted the server within the last ${activeWindowLabel}. The device normally reports about once a minute. This is a fixed dashboard display rule; it is not a phone setting.`,
    }),
    summaryCard("Debug Mode", debug, "wipes blocked", debug ? "success" : "muted", {
      icon: "bug",
      info: "Devices with Debug Mode ON. They receive commands and sync settings, but Force Reset is blocked and reported instead of wiping.",
    }),
    summaryCard("Low battery", lowBattery, "last reported ≤20%", lowBattery ? "danger" : "muted", {
      icon: "battery",
      info: "Devices whose last reported battery level was 20% or less, including inactive devices. The battery value may be from their last contact, not live.",
    }),
  );
}

function renderDeviceToolbar(devices, statusByDevice, notesByDevice, now, visibleCount) {
  const filter = state.deviceView.filter;
  const counts = {};
  for (const name of DEVICE_FILTERS) {
    counts[name] = filterCount(
      devices,
      statusByDevice,
      notesByDevice,
      now,
      FRESHNESS_WINDOW_MS,
      name,
    );
  }
  for (const button of document.querySelectorAll("[data-device-filter]")) {
    button.setAttribute("aria-pressed", String(button.dataset.deviceFilter === filter));
  }
  for (const [name, count] of Object.entries(counts)) {
    const element = document.querySelector(`[data-filter-count="${name}"]`);
    if (element) element.textContent = String(count);
  }
  const sort = $("#device-sort");
  if (sort) sort.value = state.deviceView.sort;
  const search = $("#device-search");
  if (search && search.value !== state.deviceQuery) search.value = state.deviceQuery;
  for (const button of document.querySelectorAll("[data-device-layout]")) {
    button.setAttribute("aria-pressed", String(button.dataset.deviceLayout === state.deviceView.layout));
  }
  const result = $("#device-result-count");
  if (result) {
    const total = devices.length;
    result.textContent = !total
      ? "0 devices"
      : filter !== "all" || state.deviceQuery.trim()
        ? `Showing ${visibleCount} of ${total} devices`
        : `${total} device${total === 1 ? "" : "s"}`;
  }
}

function clearDeviceFilters() {
  state.deviceQuery = "";
  state.deviceView = { ...state.deviceView, filter: "all" };
  saveDeviceViewPrefs(state.deviceView);
  renderDevices();
}

function pruneSelectedDevices(devices) {
  const active = new Set(devices.map((device) => device.id));
  for (const id of [...state.selectedDeviceIds]) {
    if (!active.has(id)) state.selectedDeviceIds.delete(id);
  }
}

function targetDevices() {
  if (state.targetScope === "all") return state.data.devices;
  return state.data.devices.filter((device) => state.selectedDeviceIds.has(device.id));
}

function commandTargets() {
  return {
    scope: state.targetScope,
    deviceIds: state.targetScope === "all" ? [] : [...state.selectedDeviceIds],
  };
}

function renderTargetDeviceList(devices) {
  const container = $("#target-device-list");
  if (!container) return;
  container.replaceChildren();
  container.hidden = state.targetScope === "all";
  if (state.targetScope === "all") return;
  if (!devices.length) {
    container.append(el("p", { className: "hint", text: "No enrolled devices to select." }));
    return;
  }

  const now = Date.now();
  const activeWindow = FRESHNESS_WINDOW_MS;
  for (const device of devices) {
    const status = state.data.statusByDevice.get(device.id);
    const fresh = isRecentlyConnected(status, now, activeWindow);
    const input = el("input", {
      attrs: {
        type: "checkbox",
        "data-device-select": device.id,
        "aria-label": `Select ${device.name || "this device"} for commands`,
      },
    });
    input.checked = state.selectedDeviceIds.has(device.id);
    input.disabled = !state.online;
    container.append(
      el("label", { className: "target-device-option" }, [
        input,
        el("span", { className: "target-device-name", text: device.name || "Unnamed device" }),
        statusBadge(fresh ? "Active" : "Inactive", fresh ? "active" : "inactive"),
      ]),
    );
  }
}

function renderCommandScope(devices) {
  const selected = devices.filter((device) => state.selectedDeviceIds.has(device.id));
  renderTargetDeviceList(devices);
  const scope = $("#action-scope");
  if (scope) {
    scope.textContent = state.targetScope === "all"
      ? `Applies to all ${devices.length} enrolled device(s); inactive devices receive it on reconnect.`
      : selected.length
        ? `Applies to ${selected.length} selected device(s): ${selected
            .map((device) => device.name || "Unnamed device")
            .join(", ")}.`
        : "Select devices above, or choose All enrolled devices.";
  }
  const summary = $("#target-summary");
  if (summary) {
    summary.textContent = state.targetScope === "all"
      ? `${devices.length} devices`
      : `${selected.length} of ${devices.length} selected`;
  }
  const canTarget = state.online && devices.length > 0 &&
    (state.targetScope === "all" || selected.length > 0);
  const disabledReason = !devices.length
    ? "Enroll a device first. Commands need at least one enrolled target."
    : state.online
      ? "Select at least one device in Command targets to enable this action."
      : "Commands are unavailable while offline or while Supabase is unreachable.";
  for (const selector of ["#lock-devices", "#reset-timer", "#force-reset"]) {
    const button = $(selector);
    if (!button) continue;
    button.disabled = !canTarget;
    button.title = canTarget ? ACTION_BUTTON_TITLES[selector] : disabledReason;
    button.setAttribute("aria-disabled", String(!canTarget));
    if (!canTarget) button.setAttribute("aria-describedby", "command-disabled-hint");
    else button.removeAttribute("aria-describedby");
  }
  const hint = $("#command-disabled-hint");
  if (hint) {
    hint.hidden = canTarget;
    hint.textContent = canTarget ? "" : disabledReason;
  }
  const all = $("#target-all");
  const selectedRadio = $("#target-selected");
  if (all) all.checked = state.targetScope === "all";
  if (selectedRadio) selectedRadio.checked = state.targetScope === "selected";
}

function setButtonIcon(button, icon) {
  button.querySelector("use")?.setAttribute("href", `#icon-${icon}`);
}

function toggleDeviceCollapsed(deviceId, button) {
  const collapsed = !state.collapsedDeviceIds.has(deviceId);
  if (collapsed) state.collapsedDeviceIds.add(deviceId);
  else state.collapsedDeviceIds.delete(deviceId);
  saveStoredIdSet(COLLAPSED_DEVICES_KEY, state.collapsedDeviceIds);
  const card = button.closest(".device-card");
  card?.classList.toggle("collapsed", collapsed);
  button.setAttribute("aria-expanded", String(!collapsed));
  button.setAttribute("aria-label", collapsed ? "Expand device card" : "Collapse device card");
  button.title = collapsed ? "Expand this device card" : "Collapse this device card";
  setButtonIcon(button, collapsed ? "chevron-right" : "chevron-down");
}

function toggleDeviceIdVisibility(deviceId, button) {
  const hidden = !state.hiddenDeviceIds.has(deviceId);
  if (hidden) state.hiddenDeviceIds.add(deviceId);
  else state.hiddenDeviceIds.delete(deviceId);
  saveStoredIdSet(HIDDEN_DEVICE_IDS_KEY, state.hiddenDeviceIds);
  const row = button.closest(".device-id-row");
  const value = row?.querySelector(".device-id-value");
  if (value) value.textContent = hidden ? "••••••••••••••••" : deviceId;
  button.setAttribute("aria-pressed", String(hidden));
  button.setAttribute("aria-label", hidden ? "Show device ID" : "Hide device ID");
  button.title = hidden ? "Show the full device ID" : "Hide the device ID";
  setButtonIcon(button, hidden ? "eye-off" : "eye");
}

function settingsStatusNode(status, effective) {
  if (!status || !effective) {
    return el("span", { className: "status-line" }, [
      statusBadge("Unknown", "unknown"),
      el("span", { className: "muted", text: "until the first sync" }),
    ]);
  }
  const current =
    Number(status.applied_global_settings_revision) >= Number(effective.global_revision) &&
    Number(status.applied_device_settings_revision) >= Number(effective.device_revision);
  const appliedAt = status.settings_applied_at
    ? ` · applied ${formatDateTime(Date.parse(status.settings_applied_at))}`
    : "";
  return el("span", { className: "status-line" }, [
    statusBadge(current ? "Current" : "Pending", current ? "current" : "pending"),
    el("span", {
      className: "muted",
      text: `global v${effective.global_revision}, device v${effective.device_revision}${appliedAt}`,
    }),
  ]);
}

function statusBadge(text, tone = "unknown", title = "") {
  return el("span", {
    className: `status-badge ${tone}`,
    text,
    ...(title ? { attrs: { title } } : {}),
  });
}

function batteryPill(status, title, { fresh = true } = {}) {
  const percent = batteryPercent(status);
  const stale = !fresh && percent !== null;
  const gauge = el("span", {
    className: "battery-gauge",
    attrs: { "aria-hidden": "true" },
  }, percent === null
    ? []
    : [el("span", { className: "battery-fill", attrs: { style: `width:${percent}%` } })]);
  const titleText = stale
    ? "This device is inactive. The battery value is from its last contact, not live data."
    : title;
  return el("span", {
    className: `battery-pill ${batteryTone(status, { fresh })}`,
    attrs: titleText ? { title: titleText } : {},
  }, [gauge, el("span", { text: batteryLabel(status, { fresh }) })]);
}

function booleanNode(value) {
  if (value === true) return el("span", { className: "value-on", text: "ON" });
  if (value === false) return el("span", { className: "value-off", text: "OFF" });
  return el("span", { className: "value-unknown", text: "Unknown" });
}

function desiredVsActualNode(desired, actual, formatValue) {
  const desiredText =
    desired === undefined || desired === null ? "not set" : formatValue(desired);
  const actualText =
    actual === undefined || actual === null ? "unknown" : formatValue(actual);
  const actualNode = typeof actual === "boolean"
    ? booleanNode(actual)
    : el("span", { text: actualText });
  if (actualText === desiredText) {
    return el("span", { className: "status-line" }, [
      el("span", { text: "Device reports: " }),
      actualNode,
      el("span", { className: "muted", text: " · saved setting matches" }),
    ]);
  }
  return el("span", { className: "status-line" }, [
    el("span", { text: "Device reports: " }),
    actualNode,
    el("span", { className: "muted", text: " · saved setting: " }),
    el("span", { text: desiredText }),
  ]);
}

function summaryCard(label, value, detail, tone = "", { icon, info } = {}) {
  const head = el("span", { className: "stat-head" }, [
    icon ? iconNode(icon) : null,
    el("span", { className: "stat-label", text: label }),
    info
      ? el("button", {
          className: "info-button",
          attrs: {
            type: "button",
            "data-info": info,
            "aria-label": `About ${label}`,
            title: info,
          },
        }, [el("span", { text: "?" })])
      : null,
  ].filter(Boolean));
  return el("div", { className: "stat" }, [
    head,
    el("span", { className: `stat-value ${tone}`.trim(), text: String(value) }),
    el("span", { className: "stat-detail", text: detail }),
  ]);
}

function timerText(status, now) {
  if (!status) return "unknown";
  if (status.actual_lock_trigger_enabled === false) return "inactive — Lock trigger is OFF";
  if (!status.timer_running) return "inactive — no active lock countdown";
  return `live · ${formatRemaining(extrapolatedRemaining(status, now))} left (estimated)`;
}

function formatEnabled(value) {
  return value ? "ON" : "OFF";
}

const COMMAND_LABELS = {
  reset_timer: "Reset Timer",
  force_reset: "Force Reset",
  lock_now: "Lock Devices",
  debug_mode_on: "Debug Mode ON",
  debug_mode_off: "Debug Mode OFF",
};

const ACTION_BUTTON_TITLES = {
  "#lock-devices": "Lock the screen on the selected target devices; requires two confirmations",
  "#reset-timer": "Restart the lock countdown on the selected target devices",
  "#force-reset": "Queue a factory reset; requires Debug Mode OFF and a typed confirmation",
};

const STATE_LABELS = {
  queued: "queued",
  received: "received",
  executing: "executing",
  applied: "applied",
  reset_initiated: "reset initiated — no completion signal after a wipe",
  debug_blocked: "blocked by Debug Mode",
  rejected: "rejected",
  failed: "failed",
  expired: "expired",
};

const PROGRESS_STATE_LABELS = {
  sending: "sending",
  waiting: "waiting for device",
  not_sent: "not sent",
  queued: "queued",
  received: "received",
  executing: "executing",
  applied: "applied",
  reset_initiated: "reset initiated",
  debug_blocked: "blocked",
  rejected: "rejected",
  failed: "failed",
  expired: "expired",
};

const PROGRESS_TERMINAL_STATES = new Set([
  "applied",
  "reset_initiated",
  "debug_blocked",
  "rejected",
  "failed",
  "expired",
]);

const PROGRESS_STATE_TONES = {
  sending: "pending",
  waiting: "unknown",
  not_sent: "danger",
  queued: "queued",
  received: "received",
  executing: "executing",
  applied: "success",
  reset_initiated: "warning",
  debug_blocked: "warning",
  rejected: "danger",
  failed: "danger",
  expired: "danger",
};

function fieldTerm(label, info, icon) {
  return el("dt", {}, [
    icon ? iconNode(icon) : null,
    el("span", { text: label }),
    el("button", {
      className: "info-button",
      attrs: {
        type: "button",
        "data-info": info,
        "aria-label": `About ${label}`,
        title: info,
      },
    }, [el("span", { text: "?" })]),
  ].filter(Boolean));
}

function describeCommandNode(command) {
  if (!command) return el("span", { className: "muted", text: "none" });
  const label = COMMAND_LABELS[command.command_type] ?? command.command_type;
  const state = STATE_LABELS[command.state] ?? command.state;
  const result = command.result_code ? ` (${command.result_code})` : "";
  const when = Date.parse(command.created_at);
  const timestamp = Number.isNaN(when) ? command.created_at : formatDateTime(when);
  const tone = command.state === "applied"
    ? "success"
    : ["failed", "expired", "rejected"].includes(command.state)
      ? "danger"
      : command.state === "debug_blocked"
        ? "warning"
        : "pending";
  return el("span", { className: "status-line" }, [
    el("span", { text: `${label} — ` }),
    statusBadge(state, tone),
    el("span", { className: "muted", text: `${result} · ${timestamp}` }),
  ]);
}

function beginCommandProgress(label, deviceIds) {
  clearTimeout(state.commandProgressTimer);
  state.commandProgressTimer = null;
  const names = new Map(
    state.data.devices.map((device) => [
      device.id,
      device.name || "Unnamed device",
    ]),
  );
  state.commandProgress = {
    label,
    actionId: null,
    targets: [...new Set(deviceIds)].map((id) => ({
      id,
      name: names.get(id) ?? `Device ${id.slice(0, 8)}`,
    })),
    commands: new Map(),
    phase: "sending",
    error: "",
    readError: "",
    pollUntil: Date.now() + 60_000,
  };
  renderCommandProgress();
}

function trackCommandAction(actionId) {
  const progress = state.commandProgress;
  if (!progress || !actionId) return;
  progress.actionId = actionId;
  progress.phase = "tracking";
  renderCommandProgress();
  void refreshCommandProgress();
}

function failCommandProgress(message) {
  const progress = state.commandProgress;
  if (!progress) return;
  progress.phase = "error";
  progress.error = message;
  clearTimeout(state.commandProgressTimer);
  state.commandProgressTimer = null;
  renderCommandProgress();
}

function clearCommandProgress() {
  clearTimeout(state.commandProgressTimer);
  state.commandProgressTimer = null;
  state.commandProgress = null;
  const panel = $("#command-progress");
  if (panel) panel.hidden = true;
}

async function refreshCommandProgress() {
  const progress = state.commandProgress;
  if (!progress || !progress.actionId || !state.online || !state.session) {
    renderCommandProgress();
    return;
  }

  try {
    const result = await state.client
      .from("device_commands")
      .select("device_id,state,result_code,created_at")
      .eq("action_id", progress.actionId);
    if (state.commandProgress !== progress) return;
    if (result.error) throw new Error(result.error.message);
    progress.commands = new Map(
      (result.data ?? []).map((row) => [row.device_id, row]),
    );
    progress.readError = "";
    progress.phase = progress.targets.every((target) => {
      const command = progress.commands.get(target.id);
      return command && PROGRESS_TERMINAL_STATES.has(command.state);
    })
      ? "done"
      : "tracking";
  } catch (error) {
    if (state.commandProgress !== progress) return;
    progress.readError = error.message;
  }

  renderCommandProgress();
  scheduleCommandProgressPoll();
}

function scheduleCommandProgressPoll() {
  clearTimeout(state.commandProgressTimer);
  state.commandProgressTimer = null;
  const progress = state.commandProgress;
  if (
    !progress ||
    !progress.actionId ||
    progress.phase === "done" ||
    progress.phase === "error" ||
    Date.now() >= progress.pollUntil
  ) {
    return;
  }
  state.commandProgressTimer = setTimeout(() => {
    state.commandProgressTimer = null;
    void refreshCommandProgress();
  }, 3000);
}

function commandProgressSummary(progress) {
  if (progress.phase === "sending") {
    return {
      badge: "Sending",
      tone: "pending",
      detail: `Creating the command for ${progress.targets.length} target(s)…`,
    };
  }
  if (progress.phase === "error") {
    return {
      badge: "Not sent",
      tone: "danger",
      detail: progress.error || "The command could not be created.",
    };
  }

  const commands = progress.targets
    .map((target) => progress.commands.get(target.id))
    .filter(Boolean);
  const total = progress.targets.length;
  const states = commands.map((command) => command.state);
  const applied = states.filter((state) => state === "applied").length;
  const reset = states.filter((state) => state === "reset_initiated").length;
  const blocked = states.filter((state) => state === "debug_blocked").length;
  const failed = states.filter((state) => ["rejected", "failed", "expired"].includes(state)).length;
  const done = commands.length === total && commands.every((command) =>
    PROGRESS_TERMINAL_STATES.has(command.state)
  );

  if (done && applied === total) {
    return {
      badge: "Applied",
      tone: "success",
      detail: `Applied on all ${total} target(s).`,
    };
  }
  if (done && reset === total) {
    return {
      badge: "Initiated",
      tone: "warning",
      detail:
        "The reset was initiated on every target. Android provides no completion signal after a wipe.",
    };
  }
  if (done) {
    const parts = [];
    if (applied) parts.push(`${applied} applied`);
    if (reset) parts.push(`${reset} initiated`);
    if (blocked) parts.push(`${blocked} blocked by Debug Mode`);
    if (failed) parts.push(`${failed} failed`);
    return {
      badge: "Finished",
      tone: failed ? "danger" : "warning",
      detail: `Command finished: ${parts.join(", ")}.`,
    };
  }
  if (states.some((state) => state === "received" || state === "executing")) {
    return {
      badge: "Applying",
      tone: "pending",
      detail: "The device received the command; waiting for the final result.",
    };
  }
  if (commands.length === 0) {
    return {
      badge: "Waiting",
      tone: "pending",
      detail: "Waiting for the command to appear on the device.",
    };
  }
  if (states.every((state) => state === "queued")) {
    return {
      badge: "Queued",
      tone: "queued",
      detail:
        "The command is queued. A live device normally receives it within seconds; an offline device waits for its next sync.",
    };
  }
  return {
    badge: "Waiting",
    tone: "pending",
    detail: progress.readError
      ? `Waiting for device status (${progress.readError}).`
      : "Waiting for the remaining target(s).",
  };
}

function renderCommandProgress() {
  const panel = $("#command-progress");
  if (!panel) return;
  const progress = state.commandProgress;
  if (!progress) {
    panel.hidden = true;
    return;
  }

  const summary = commandProgressSummary(progress);
  panel.hidden = false;
  panel.dataset.tone = summary.tone;
  $("#command-progress-title").textContent = progress.label;
  $("#command-progress-detail").textContent = summary.detail;
  $("#command-progress-status").replaceChildren(
    statusBadge(summary.badge, summary.tone),
  );

  const list = $("#command-progress-list");
  list.replaceChildren();
  for (const target of progress.targets) {
    const command = progress.commands.get(target.id);
    const state = command?.state
      ?? (progress.phase === "error" ? "not_sent" : progress.phase === "sending" ? "sending" : "waiting");
    const tone = PROGRESS_STATE_TONES[state] ?? "unknown";
    const label = PROGRESS_STATE_LABELS[state] ?? state;
    const result = command?.result_code ? ` (${command.result_code})` : "";
    const when = command?.created_at ? Date.parse(command.created_at) : NaN;
    const timestamp = Number.isNaN(when) ? "" : ` · ${formatDateTime(when)}`;
    list.append(
      el("li", { className: "command-progress-row" }, [
        el("span", { className: "command-progress-device", text: target.name }),
        el("span", { className: "status-line" }, [
          statusBadge(label, tone),
          el("span", {
            className: "muted command-progress-time",
            text: `${result}${timestamp}`,
          }),
        ]),
      ]),
    );
  }
}

function renderSettings() {
  const settings = state.data.settings;
  const form = $("#settings-form");
  if (!settings) {
    $("#settings-revision").textContent = state.loading || state.settingsLoading
      ? "Loading settings…"
      : state.settingsError
        ? "settings unavailable — refresh to retry"
        : state.online
          ? "settings unavailable"
          : state.connectionReason === "offline"
            ? "offline — reconnect to load settings"
            : "Supabase unreachable — retrying";
    return;
  }
  $("#lock-trigger").checked = Boolean(settings.lock_trigger_enabled);
  $("#lock-minutes").value = String(settings.lock_timer_minutes);
  $("#usb-trigger").checked = Boolean(settings.usb_trigger_enabled);
  $("#lock-human").textContent =
    `Configured value: ${formatMinutes(settings.lock_timer_minutes)}`;
  const revision = $("#settings-revision");
  revision.textContent = `Global settings version ${settings.revision}${
    settings.initialized_at ? "" : " · not initialized yet"
  }${state.settingsError ? " · refresh failed" : ""}`;
  revision.title = state.settingsError
    ? `Could not refresh settings: ${state.settingsError}. The values shown are the last loaded values.`
    : "The global version increases each time global settings are saved. Devices with overrides still apply the newest global values for fields they inherit.";
  form.dataset.revision = String(settings.revision);
  renderLimits();
}

function renderLimits() {
  const settings = state.data.settings;
  const revision = $("#limits-revision");
  if (!settings) {
    if (revision) {
      revision.textContent = state.online
        ? "limits unavailable"
        : state.connectionReason === "offline"
          ? "offline — reconnect to load limits"
          : "Supabase unreachable — retrying";
    }
    return;
  }
  const limits = limitsFromSettings(settings);
  for (const field of LIMIT_FIELDS) {
    const input = $(`#${field.inputId}`);
    if (input) input.value = String(limits[field.key]);
  }
  if (revision) {
    revision.textContent = `Limits version ${settings.limits_revision}`;
    revision.title =
      "Limits are saved separately from trigger settings. Changing them does not make devices reapply trigger values.";
  }
  const enrollment = $("#enroll-token-validity");
  if (enrollment) {
    enrollment.textContent =
      `Valid for ${formatLimitMinutes(limits.enrollment_token_ttl_minutes)} and usable once.`;
  }
  const expiry = $("#help-command-expiry");
  if (expiry) expiry.textContent = commandExpirySummary(settings);
}

function readLimitForm() {
  const limits = {};
  for (const field of LIMIT_FIELDS) {
    const input = $(`#${field.inputId}`);
    const value = Number(input?.value);
    if (!Number.isInteger(value) || value < field.min || value > field.max) {
      const label = input?.closest(".field")?.querySelector("label")?.textContent ?? "This limit";
      showMessage(
        "#limits-message",
        `${label.trim()} must be between ${field.min} and ${field.max}.`,
        "error",
      );
      input?.focus();
      return null;
    }
    limits[field.key] = value;
  }
  return limits;
}

function applyLimitDefaults() {
  for (const field of LIMIT_FIELDS) {
    const input = $(`#${field.inputId}`);
    if (input) input.value = String(LIMIT_DEFAULTS[field.key]);
  }
  clearMessage("#limits-message");
  toast("Default limits filled in. Review them, then save.", "info");
}

function tickTimers() {
  const now = Date.now();
  for (const entry of state.liveTimers) {
    entry.el.textContent = timerText(entry.status, now);
    if (entry.lastContactEl) {
      entry.lastContactEl.textContent = formatLastContact(entry.status, now);
    }
  }
}

function debugSwitch(device, status) {
  const input = el("input", {
    attrs: {
      type: "checkbox",
      "data-debug-device": device.id,
      "aria-label": `Debug Mode for ${device.name || "this device"}`,
      ...(status ? {} : { "data-debug-unknown": "true" }),
    },
  });
  input.checked = Boolean(status?.debug_mode);
  input.disabled = !status;
  return el("label", {
    className: "switch",
    attrs: {
      title: status
        ? "ON blocks Force Reset; OFF allows destructive remote commands."
        : "The current value appears after the device's first sync.",
    },
  }, [
    input,
    el("span", { className: "switch-track", attrs: { "aria-hidden": "true" } }, [
      el("span", { className: "switch-thumb" }),
    ]),
  ]);
}

/** The switch sends one per-device command; cancelling restores the previous position. */
async function toggleDebugMode(deviceId, enabled, input) {
  if (!state.online) {
    input.checked = !enabled;
    toast(actionUnavailableMessage(state.connectionReason), "error");
    return;
  }
  const confirmed = await confirmAction(
    enabled
      ? {
          title: "Turn Debug Mode ON?",
          message:
            "Debug Mode blocks destructive remote commands: a Force Reset will be " +
            "logged and notified on the device instead of executing. Reset Timer and " +
            "settings sync keep working.",
          confirmLabel: "Enable",
          danger: false,
        }
      : {
          title: "Turn Debug Mode OFF?",
          message:
            "While Debug Mode is OFF, dangerous commands (including a remote Force Reset) " +
            "can wipe this device.",
          confirmLabel: "Disable",
        },
  );
  if (!confirmed) {
    input.checked = !enabled;
    return;
  }

  const label = enabled ? "Debug Mode ON" : "Debug Mode OFF";
  beginCommandProgress(label, [deviceId]);
  input.disabled = true;
  try {
    const result = await setDeviceDebugMode(
      state.client,
      deviceId,
      enabled,
      newIdempotencyKey(),
    );
    trackCommandAction(result.action_id);
    toast(`${label} sent. Waiting for the device.`, "info");
    scheduleRefresh();
  } catch (error) {
    input.checked = !enabled;
    failCommandProgress(error.message);
    toast(error.message, "error");
  } finally {
    input.disabled = false;
  }
}

function openDeviceSettingsDialog(deviceId) {
  if (!state.online) {
    toast(actionUnavailableMessage(state.connectionReason), "error");
    return;
  }
  const effective = state.data.effectiveSettingsByDevice.get(deviceId);
  const device = state.data.devices.find((item) => item.id === deviceId);
  if (!effective || !device) {
    toast("Device settings are not loaded yet.", "error");
    return;
  }
  const dialog = $("#device-settings-dialog");
  $("#device-settings-device-id").value = deviceId;
  $("#device-settings-revision").value = String(effective.device_revision);
  $("#device-settings-name").textContent = device.name || "Unnamed device";
  $("#device-settings-message").textContent = "";

  setOverrideField("ds-lock-trigger", "ds-lock-trigger-inherit", effective.lock_trigger_overridden);
  setOverrideField("ds-usb-trigger", "ds-usb-trigger-inherit", effective.usb_trigger_overridden);
  setOverrideField("ds-lock-minutes", "ds-lock-minutes-inherit", effective.lock_timer_overridden);
  $("#ds-lock-trigger").checked = Boolean(effective.lock_trigger_enabled);
  $("#ds-usb-trigger").checked = Boolean(effective.usb_trigger_enabled);
  $("#ds-lock-minutes").value = String(effective.lock_timer_minutes);
  renderDeviceSettingsGlobalCopy();

  dialog.returnValue = "";
  dialog.showModal();
  const onClose = async () => {
    dialog.removeEventListener("close", onClose);
    if (dialog.returnValue !== "ok") return;
    const revision = Number($("#device-settings-revision").value);
    const inheritMinutes = $("#ds-lock-minutes-inherit").checked;
    const minutes = Number($("#ds-lock-minutes").value);
    if (!inheritMinutes && (!Number.isInteger(minutes) || minutes < 1 || minutes > 525600)) {
      toast("Lock delay must be between 1 and 525600 minutes.", "error");
      return;
    }
    const patch = {
      lock_trigger_enabled: $("#ds-lock-trigger-inherit").checked
        ? null
        : $("#ds-lock-trigger").checked,
      lock_timer_minutes: inheritMinutes ? null : minutes,
      usb_trigger_enabled: $("#ds-usb-trigger-inherit").checked
        ? null
        : $("#ds-usb-trigger").checked,
    };
    const confirmed = await confirmAction({
      title: "Save device settings?",
      message:
        `Target: ${device.name || "Unnamed device"}.\n` +
        `Lock trigger: ${patch.lock_trigger_enabled === null ? "inherit global" : patch.lock_trigger_enabled ? "ON" : "OFF"}.\n` +
        `Lock delay: ${patch.lock_timer_minutes === null ? "inherit global" : formatMinutes(minutes)}.\n` +
        `USB trigger: ${patch.usb_trigger_enabled === null ? "inherit global" : patch.usb_trigger_enabled ? "ON" : "OFF"}.\n\n` +
        "This creates a new device settings version; no other device is affected.",
      confirmLabel: "Save device settings",
      danger: false,
    });
    if (!confirmed) return;
    try {
      await saveDeviceSettings(state.client, deviceId, revision, patch);
      toast("Device settings saved.", "success");
      scheduleRefresh();
    } catch (error) {
      if (error.code === "REVISION_CONFLICT") scheduleRefresh();
      toast(error.message, "error");
    }
  };
  dialog.addEventListener("close", onClose);
}

function setOverrideField(valueId, inheritId, overridden) {
  const inherit = $(`#${inheritId}`);
  const value = $(`#${valueId}`);
  inherit.checked = !overridden;
  value.disabled = !overridden;
}

function renderDeviceSettingsGlobalCopy() {
  const settings = state.data.settings;
  if (!settings) {
    for (const id of ["ds-lock-trigger-global", "ds-lock-minutes-global", "ds-usb-trigger-global"]) {
      const element = $(`#${id}`);
      if (element) element.textContent = "Global value unavailable.";
    }
    return;
  }
  const triggerInherited = $("#ds-lock-trigger-inherit")?.checked;
  const minutesInherited = $("#ds-lock-minutes-inherit")?.checked;
  const usbInherited = $("#ds-usb-trigger-inherit")?.checked;
  $("#ds-lock-trigger-global").textContent =
    `Global: ${settings.lock_trigger_enabled ? "ON" : "OFF"}${triggerInherited ? " · using global" : " · override saved"}.`;
  $("#ds-lock-minutes-global").textContent =
    `Global: ${formatMinutes(settings.lock_timer_minutes)}${minutesInherited ? " · using global" : " · override saved"}.`;
  $("#ds-usb-trigger-global").textContent =
    `Global: ${settings.usb_trigger_enabled ? "ON" : "OFF"}${usbInherited ? " · using global" : " · override saved"}.`;
}

function wireDeviceSettingsFields() {
  for (const [valueId, inheritId] of [
    ["ds-lock-trigger", "ds-lock-trigger-inherit"],
    ["ds-lock-minutes", "ds-lock-minutes-inherit"],
    ["ds-usb-trigger", "ds-usb-trigger-inherit"],
  ]) {
    const inherit = $(`#${inheritId}`);
    inherit?.addEventListener("change", () => {
      const value = $(`#${valueId}`);
      value.disabled = inherit.checked;
      renderDeviceSettingsGlobalCopy();
    });
  }
}

function openNotesDialog(deviceId) {
  if (!state.online) {
    toast(actionUnavailableMessage(state.connectionReason), "error");
    return;
  }
  const device = state.data.devices.find((item) => item.id === deviceId);
  if (!device) {
    toast("Device not found.", "error");
    return;
  }
  const dialog = $("#notes-dialog");
  const input = $("#device-notes");
  const count = $("#notes-count");
  const note = state.data.notesByDevice.get(deviceId)?.notes ?? "";
  $("#notes-device-name").textContent = device.name || "Unnamed device";
  input.value = note;
  $("#notes-message").textContent = "";
  const updateCount = () => {
    count.textContent = `${input.value.length} / 4000 characters`;
  };
  updateCount();
  input.addEventListener("input", updateCount);
  dialog.returnValue = "";
  dialog.showModal();
  const onClose = async () => {
    dialog.removeEventListener("close", onClose);
    input.removeEventListener("input", updateCount);
    if (dialog.returnValue !== "ok") return;
    const value = input.value.trim();
    if (value.length > 4000) {
      toast("Notes must be 4000 characters or fewer.", "error");
      return;
    }
    const button = $("#notes-save");
    setBusy(button, true, "Saving…");
    try {
      const updated = await updateDeviceNotes(state.client, deviceId, value);
      if (!updated) throw new Error("Notes were saved but the server returned no record.");
      state.data.notesByDevice.set(deviceId, updated);
      saveDashboardCache();
      renderDevices();
      toast(value ? "Device notes saved." : "Device notes cleared.", "success");
    } catch (error) {
      toast(error.message, "error");
    } finally {
      setBusy(button, false);
    }
  };
  dialog.addEventListener("close", onClose);
}

/** Rename is dashboard-only; it does not change the device identity or its credentials. */
function openRenameDialog(deviceId, currentName, button) {
  if (!state.online) {
    toast(actionUnavailableMessage(state.connectionReason), "error");
    return;
  }
  const dialog = $("#rename-dialog");
  const input = $("#rename-device-name");
  input.value = currentName;
  dialog.returnValue = "";
  dialog.showModal();
  const onClose = async () => {
    dialog.removeEventListener("close", onClose);
    if (dialog.returnValue !== "ok") return;
    const name = input.value.trim();
    if (!name || name.length > 80) {
      toast("Device name must be 1–80 characters.", "error");
      return;
    }
    setBusy(button, true, "Renaming…");
    try {
      await renameDevice(state.client, deviceId, name);
      toast("Device renamed.", "success");
      scheduleRefresh();
    } catch (error) {
      toast(error.message, "error");
    } finally {
      setBusy(button, false);
    }
  };
  dialog.addEventListener("close", onClose);
}

async function revokeDevicePrompt(deviceId, button) {
  if (!state.online) {
    toast(actionUnavailableMessage(state.connectionReason), "error");
    return;
  }
  const confirmed = await confirmAction({
    title: "Revoke this device?",
    message:
      "Remote commands stop immediately. The device keeps working normally and is not wiped. " +
      "To use it again, open Remote Management in the app, tap Reset enrollment, then claim a " +
      "new token.",
    confirmLabel: "Revoke device",
  });
  if (!confirmed) return;
  setBusy(button, true, "Revoking…");
  try {
    await revokeDevice(state.client, deviceId);
    toast("Device revoked.", "success");
    scheduleRefresh();
  } catch (error) {
    toast(error.message, "error");
  } finally {
    setBusy(button, false);
  }
}

function wireDashboardTabs() {
  const tabs = [...document.querySelectorAll("[data-dashboard-tab]")];
  const panels = [...document.querySelectorAll("[data-dashboard-panel]")];
  if (!tabs.length || !panels.length) return;

  const select = (name, focus = false) => {
    for (const tab of tabs) {
      const active = tab.dataset.dashboardTab === name;
      tab.setAttribute("aria-selected", String(active));
      tab.tabIndex = active ? 0 : -1;
      if (active && focus) tab.focus();
    }
    for (const panel of panels) {
      panel.hidden = panel.dataset.dashboardPanel !== name;
    }
  };

  tabs.forEach((tab, index) => {
    tab.addEventListener("click", () => select(tab.dataset.dashboardTab));
    tab.addEventListener("keydown", (event) => {
      let next = null;
      if (event.key === "ArrowRight" || event.key === "ArrowDown") {
        next = tabs[(index + 1) % tabs.length];
      } else if (event.key === "ArrowLeft" || event.key === "ArrowUp") {
        next = tabs[(index - 1 + tabs.length) % tabs.length];
      } else if (event.key === "Home") {
        next = tabs[0];
      } else if (event.key === "End") {
        next = tabs[tabs.length - 1];
      }
      if (!next) return;
      event.preventDefault();
      select(next.dataset.dashboardTab, true);
    });
  });

  select("devices");
}

function wireInstallPrompt() {
  const button = $("#install-app");
  if (!button) return;
  const dialog = $("#install-dialog");
  const message = $("#install-message");

  const standalone =
    window.matchMedia("(display-mode: standalone)").matches ||
    window.navigator.standalone === true;
  if (standalone) {
    button.hidden = true;
    syncHeaderMenu();
    return;
  }

  let deferredPrompt = null;
  window.addEventListener("beforeinstallprompt", (event) => {
    event.preventDefault();
    deferredPrompt = event;
  });

  button.addEventListener("click", async () => {
    if (deferredPrompt) {
      deferredPrompt.prompt();
      await deferredPrompt.userChoice.catch(() => undefined);
      deferredPrompt = null;
      button.hidden = true;
      syncHeaderMenu();
      return;
    }
    if (message && dialog) {
      message.textContent = installHelp();
      dialog.showModal();
    }
  });

  window.addEventListener("appinstalled", () => {
    deferredPrompt = null;
    button.hidden = true;
    syncHeaderMenu();
  });
}

function installHelp() {
  const ua = navigator.userAgent;
  const isFirefox = /Firefox|FxiOS/i.test(ua);
  const isSafari = /Safari/i.test(ua) && !/Chrome|Chromium|Edg|OPR/i.test(ua);
  const isLocal = ["localhost", "127.0.0.1", "::1"].includes(location.hostname);

  if (isLocal) {
    return "This is the local preview. Open the deployed HTTPS dashboard, then use the browser's install option. Firefox desktop cannot install web apps natively.";
  }
  if (isFirefox) {
    return "Firefox desktop does not install manifest-based web apps natively. Use Chrome or Edge, or install the “PWAs for Firefox” extension. On Firefox Android, use the browser menu → Add to Home screen (a browser shortcut, not a standalone app).";
  }
  if (isSafari) {
    return "On iPhone or iPad, tap Share → Add to Home Screen. On macOS Safari, choose File → Add to Dock.";
  }
  return "Use the install icon in the browser address bar or the browser menu → Install app. If no option appears, the page must be served over HTTPS.";
}

function wireDashboard() {
  $("#lock-minutes").addEventListener("input", (event) => {
    const minutes = Number(event.target.value);
    $("#lock-human").textContent = Number.isFinite(minutes) && minutes > 0
      ? `Configured value: ${formatMinutes(minutes)}`
      : "";
  });
  $("#settings-info").addEventListener("click", () => {
    toast(
      "These are global defaults for Lock trigger, lock delay, and USB trigger. Each device can override any field from its Settings button; an override wins until cleared with Inherit global. Global saves do not clear device overrides. A local phone change is reported separately and does not rewrite either target.",
      "info",
    );
  });

  $("#limits-info").addEventListener("click", () => {
    toast(
      "These limits are stored with your account and enforced by Supabase when commands or enrollment tokens are created. They decide how long a queued command waits for an offline phone, and how long a new enrollment token can be used. Existing commands and tokens keep the expiry they were created with.",
      "info",
    );
  });
  $("#limits-reset").addEventListener("click", applyLimitDefaults);
  $("#limits-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!requireOnline("#limits-message")) return;
    const settings = state.data.settings;
    if (!settings) {
      showMessage("#limits-message", "Account limits are not available.", "error");
      return;
    }
    const form = event.target;
    if (!form.reportValidity()) return;
    const limits = readLimitForm();
    if (!limits) return;
    const confirmed = await confirmAction({
      title: "Save command limits?",
      message:
        `Enrollment token: ${formatLimitMinutes(limits.enrollment_token_ttl_minutes)}. ` +
        `Lock Devices: ${formatLimitMinutes(limits.lock_command_ttl_minutes)}. ` +
        `Force Reset: ${formatLimitMinutes(limits.force_reset_ttl_minutes)}. ` +
        `Reset Timer: ${formatLimitMinutes(limits.reset_timer_ttl_minutes)}. ` +
        `Debug Mode ON: ${formatLimitMinutes(limits.debug_mode_on_ttl_minutes)}. ` +
        `Debug Mode OFF: ${formatLimitMinutes(limits.debug_mode_off_ttl_minutes)}. ` +
        "Only new commands and tokens use these values.",
      confirmLabel: "Save limits",
      danger: false,
    });
    if (!confirmed) return;
    const button = form.querySelector("button[type=submit]");
    clearMessage("#limits-message");
    setBusy(button, true);
    try {
      const updated = await saveAccountLimits(
        state.client,
        settings.limits_revision,
        limits,
      );
      state.data.settings = updated;
      renderSettings();
      renderDevices();
      toast("Command and enrollment limits saved.", "success");
    } catch (error) {
      if (error.code === "REVISION_CONFLICT") scheduleRefresh();
      showMessage("#limits-message", error.message, "error");
    } finally {
      setBusy(button, false);
    }
  });

  document.addEventListener("click", (event) => {
    const info = event.target.closest("button[data-info]");
    if (info) toast(info.dataset.info, "info");
  });

  $("#account-email-toggle").addEventListener("click", toggleAccountEmailVisibility);

  $("#account-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!requireOnline("#account-message")) return;
    const handle = $("#account-handle").value.trim();
    const validation = validateHandle(handle);
    if (validation) {
      showMessage("#account-message", validation, "error");
      $("#account-handle").focus();
      return;
    }
    if (handle === state.handle) {
      showMessage("#account-message", "That is already your account name.", "info");
      return;
    }
    const confirmed = await confirmAction({
      title: "Change account name?",
      message:
        `Change the account name to “${handle}”? This updates every browser signed into this account.`,
      confirmLabel: "Save name",
      danger: false,
    });
    if (!confirmed) return;

    const button = event.target.querySelector("button[type=submit]");
    clearMessage("#account-message");
    setBusy(button, true);
    try {
      const account = await updateOwnerHandle(state.client, handle);
      state.handle = account?.handle ?? handle;
      $("#account-handle").value = state.handle;
      $("#account-line").textContent = `Account: ${state.handle}`;
      toast("Account name updated.", "success");
    } catch (error) {
      showMessage("#account-message", error.message, "error");
    } finally {
      setBusy(button, false);
    }
  });

  $("#account-password-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!requireOnline("#account-password-message")) return;
    const currentInput = $("#account-current-password");
    const nextInput = $("#account-new-password");
    const confirmInput = $("#account-new-password-confirm");
    const validation = validatePasswordChange(
      currentInput.value,
      nextInput.value,
      confirmInput.value,
    );
    if (validation) {
      showMessage("#account-password-message", validation.message, "error");
      $(`#${validation.field}`)?.focus();
      return;
    }
    const confirmed = await confirmAction({
      title: "Change password?",
      message:
        "Your dashboard password will be replaced immediately. Browsers already signed in stay " +
        "signed in; new sign-ins need the new password.\n\n" +
        "Enrolled devices are not affected.",
      confirmLabel: "Update password",
      danger: false,
    });
    if (!confirmed) return;

    const button = event.target.querySelector("button[type=submit]");
    clearMessage("#account-password-message");
    setBusy(button, true);
    try {
      await changePassword(state.client, currentInput.value, nextInput.value, state.accountEmail);
      currentInput.value = "";
      nextInput.value = "";
      confirmInput.value = "";
      showMessage("#account-password-message", "Password updated.", "success");
      toast("Password updated.", "success");
    } catch (error) {
      showMessage("#account-password-message", error.message, "error");
    } finally {
      setBusy(button, false);
    }
  });

  const deviceSearch = $("#device-search");
  deviceSearch.addEventListener("input", () => {
    state.deviceQuery = deviceSearch.value;
    renderDevices();
  });
  $("#device-sort").addEventListener("change", (event) => {
    state.deviceView = { ...state.deviceView, sort: event.target.value };
    saveDeviceViewPrefs(state.deviceView);
    renderDevices();
  });
  document.querySelector(".device-toolbar").addEventListener("click", (event) => {
    const filterButton = event.target.closest("[data-device-filter]");
    if (filterButton) {
      state.deviceView = { ...state.deviceView, filter: filterButton.dataset.deviceFilter };
      saveDeviceViewPrefs(state.deviceView);
      renderDevices();
      return;
    }
    const layoutButton = event.target.closest("[data-device-layout]");
    if (layoutButton) {
      state.deviceView = { ...state.deviceView, layout: layoutButton.dataset.deviceLayout };
      saveDeviceViewPrefs(state.deviceView);
      renderDevices();
    }
  });

  for (const radio of document.querySelectorAll('input[name="command-target"]')) {
    radio.addEventListener("change", () => {
      if (!radio.checked) return;
      state.targetScope = radio.value === "all" ? "all" : "selected";
      renderCommandScope(state.data.devices);
    });
  }
  wireDeviceSettingsFields();
  $("#command-progress-dismiss").addEventListener("click", clearCommandProgress);

  $("#target-device-list").addEventListener("change", (event) => {
    const select = event.target.closest("input[data-device-select]");
    if (!select) return;
    if (select.checked) state.selectedDeviceIds.add(select.dataset.deviceSelect);
    else state.selectedDeviceIds.delete(select.dataset.deviceSelect);
    renderCommandScope(state.data.devices);
  });

  $("#devices").addEventListener("change", (event) => {
    const debug = event.target.closest("input[data-debug-device]");
    if (!debug) return;
    toggleDebugMode(debug.dataset.debugDevice, debug.checked, debug);
  });

  document.addEventListener("click", (event) => {
    if (event.target.closest("#dashboard-retry, [data-dashboard-retry]")) {
      refreshDashboard();
    }
  });

  $("#devices").addEventListener("click", (event) => {
    const clearFilters = event.target.closest("button[data-clear-device-filters]");
    if (clearFilters) {
      clearDeviceFilters();
      return;
    }
    const collapse = event.target.closest("button[data-device-collapse]");
    if (collapse) {
      toggleDeviceCollapsed(collapse.dataset.deviceCollapse, collapse);
      return;
    }
    const idToggle = event.target.closest("button[data-device-id-toggle]");
    if (idToggle) {
      toggleDeviceIdVisibility(idToggle.dataset.deviceIdToggle, idToggle);
      return;
    }
    const notesButton = event.target.closest("button[data-device-notes]");
    if (notesButton) {
      openNotesDialog(notesButton.dataset.deviceNotes);
      return;
    }
    const settingsButton = event.target.closest("button[data-device-settings]");
    if (settingsButton) {
      openDeviceSettingsDialog(settingsButton.dataset.deviceSettings);
      return;
    }
    const rename = event.target.closest("button[data-device-rename]");
    if (rename) {
      openRenameDialog(rename.dataset.deviceRename, rename.dataset.deviceName, rename);
      return;
    }
    const revoke = event.target.closest("button[data-device-revoke]");
    if (revoke) {
      revokeDevicePrompt(revoke.dataset.deviceRevoke, revoke);
    }
  });

  $("#settings-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!requireOnline("#settings-message")) return;
    const settings = state.data.settings;
    if (!settings) {
      showMessage("#settings-message", "Account settings are not available.", "error");
      return;
    }
    const form = event.target;
    if (!form.reportValidity()) return;
    const lockTrigger = $("#lock-trigger").checked;
    const lockMinutes = Number($("#lock-minutes").value);
    const usbEnabled = $("#usb-trigger").checked;
    const confirmed = await confirmAction({
      title: "Save trigger settings?",
      message:
        `Lock trigger: ${lockTrigger ? "ON" : "OFF"}. ` +
        `Lock delay: ${formatMinutes(lockMinutes)}. ` +
        `USB trigger: ${usbEnabled ? "enabled" : "disabled"}. ` +
        "This creates a new global settings version. Every device that inherits a field applies its new value at the next sync; device overrides remain in place.",
      confirmLabel: "Save and sync",
      danger: false,
    });
    if (!confirmed) return;
    const button = form.querySelector("button[type=submit]");
    clearMessage("#settings-message");
    setBusy(button, true);
    try {
      const updated = await saveAccountSettings(
        state.client,
        settings.revision,
        lockMinutes,
        usbEnabled,
        lockTrigger,
      );
      state.data.settings = updated;
      toast("Global settings saved — inheriting devices will apply them.", "success");
      renderSettings();
      scheduleRefresh();
    } catch (error) {
      if (error.code === "REVISION_CONFLICT") scheduleRefresh();
      showMessage("#settings-message", error.message, "error");
    } finally {
      setBusy(button, false);
    }
  });

  $("#lock-devices").addEventListener("click", async () => {
    const targets = targetDevices();
    if (!targets.length) {
      toast("Select at least one device first.");
      return;
    }
    const names = targets.map((device) => device.name || "Unnamed device").join(", ");
    const first = await confirmAction({
      title: "Lock devices?",
      message:
        `This locks the screen on ${targets.length} device(s) at their next sync:\n${names}\n\n` +
        "It does not wipe data or change settings. You will be asked once more.",
      confirmLabel: "Continue",
      danger: false,
    });
    if (!first) return;
    const second = await confirmAction({
      title: "Confirm lock",
      message:
        `Send the lock command to ${targets.length} device(s) now? The devices stay locked until ` +
        "someone unlocks them locally.",
      confirmLabel: "Lock now",
      danger: false,
    });
    if (second) await sendAction(LOCK_NOW);
  });

  $("#reset-timer").addEventListener("click", async () => {
    const targets = targetDevices();
    if (!targets.length) {
      toast("Select at least one device first.");
      return;
    }
    const now = Date.now();
    const activeTimers = targets
      .map((device) => {
        const status = state.data.statusByDevice.get(device.id);
        if (!status?.timer_running) return null;
        const remaining = formatRemaining(extrapolatedRemaining(status, now));
        return `${device.name || "Unnamed device"}: ${remaining} remaining`;
      })
      .filter(Boolean);
    const preview = activeTimers.length
      ? `Active timers now:\n${activeTimers.join("\n")}`
      : "No targeted device currently has an active lock countdown.";
    const confirmed = await confirmAction({
      title: "Reset Timer",
      message:
        `${preview}\n\nRestart the lock countdown on ${targets.length} targeted device(s). Devices ` +
        "that are unlocked or have the Lock trigger OFF stay inactive. The cards update from the " +
        "devices after they sync.",
    });
    if (confirmed) await sendAction(RESET_TIMER);
  });

  $("#force-reset").addEventListener("click", async () => {
    const targets = targetDevices();
    if (!targets.length) {
      toast("Select at least one device first.");
      return;
    }
    const confirmed = await confirmAction({
      title: "Force Reset",
      message:
        `Send a factory-reset command to ${targets.length} targeted device(s):\n` +
        `${targets.map((device) => device.name || "Unnamed device").join(", ")}\n\n` +
        "Devices in Debug Mode will NOT be reset. This cannot be undone.",
      typedPhrase: "RESET",
    });
    if (confirmed) await sendAction(FORCE_RESET);
  });

  $("#create-token").addEventListener("click", async () => {
    if (!state.online) {
      toast(actionUnavailableMessage(state.connectionReason), "error");
      return;
    }
    const tokenMinutes = limitsFromSettings(state.data.settings).enrollment_token_ttl_minutes;
    const confirmed = await confirmAction({
      title: "Create enrollment token?",
      message:
        `This creates a single-use code valid for ${formatLimitMinutes(tokenMinutes)}. Anyone ` +
        "with the code can add a device to this account, so share it only with your own device.",
      confirmLabel: "Create token",
      danger: false,
    });
    if (!confirmed) return;
    const button = $("#create-token");
    setBusy(button, true);
    try {
      const { token, expiresAt } = await createEnrollmentToken(state.client);
      clearEnrollmentToken();
      $("#token-value").textContent = token;
      $("#token-output").hidden = false;
      updateTokenExpiry(expiresAt);
      state.tokenTimer = setInterval(() => updateTokenExpiry(expiresAt), 1000);
    } catch (error) {
      toast(`Could not create a token: ${error.message}`, "error");
    } finally {
      setBusy(button, false);
    }
  });

  $("#copy-token").addEventListener("click", async () => {
    try {
      await copyText($("#token-value").textContent);
      toast("Token copied.", "success");
    } catch {
      toast("Copy failed — select the token manually.", "error");
    }
  });
}

function clearEnrollmentToken() {
  clearInterval(state.tokenTimer);
  state.tokenTimer = null;
  const value = $("#token-value");
  if (value) value.textContent = "";
  const output = $("#token-output");
  if (output) output.hidden = true;
  const expiry = $("#token-expiry");
  if (expiry) expiry.textContent = "";
}

function updateTokenExpiry(expiresAt) {
  const label = tokenExpiryLabel(expiresAt);
  $("#token-expiry").textContent = label;
  if (label.startsWith("Expired")) {
    clearEnrollmentToken();
    toast("Enrollment token expired — create a new one.", "info");
  }
}

async function sendAction(actionType) {
  if (!requireOnline("#commands-message")) return;
  const targets = commandTargets();
  if (targets.scope === "selected" && targets.deviceIds.length === 0) {
    showMessage("#commands-message", "Select at least one device first.", "error");
    return;
  }
  const button = actionType === FORCE_RESET
    ? $("#force-reset")
    : actionType === LOCK_NOW
      ? $("#lock-devices")
      : $("#reset-timer");
  const label = COMMAND_LABELS[actionType] ?? actionType;
  beginCommandProgress(label, targetDevices().map((device) => device.id));
  clearMessage("#commands-message");
  setBusy(button, true);
  try {
    const result = await createAction(
      state.client,
      actionType,
      newIdempotencyKey(),
      targets.scope,
      targets.deviceIds,
    );
    trackCommandAction(result.action_id);
    toast(`${label} sent. Waiting for the device.`, "info");
    scheduleRefresh();
  } catch (error) {
    failCommandProgress(error.message);
    showMessage("#commands-message", error.message, "error");
  } finally {
    setBusy(button, false);
  }
}

function wireSignOut() {
  $("#sign-out").addEventListener("click", async () => {
    const confirmed = await confirmAction({
      title: "Sign out?",
      message:
        "This browser will no longer access your account. The Wasted app keeps running and syncing; existing settings, timers, and queued commands are unchanged. Sign in again to send new commands or view devices.",
      confirmLabel: "Sign out",
      danger: false,
    });
    if (!confirmed) return;
    state.explicitSignOut = true;
    clearEnrollmentToken();
    clearDashboardCache();
    try {
      await signOut(state.client);
      toast("Signed out.", "info");
    } finally {
      state.explicitSignOut = false;
    }
  });
}

function wireHeaderMenu() {
  const header = document.querySelector(".app-header");
  const toggle = $("#menu-toggle");
  const actions = $("#header-actions");
  if (!header || !toggle || !actions) return;

  const setOpen = (open, restoreFocus = false) => {
    header.classList.toggle("menu-open", open);
    toggle.setAttribute("aria-expanded", String(open));
    toggle.setAttribute("aria-label", open ? "Close menu" : "Open menu");
    if (open) {
      actions.querySelector("button:not([hidden])")?.focus({ preventScroll: true });
    } else if (restoreFocus) {
      toggle.focus({ preventScroll: true });
    }
  };

  toggle.addEventListener("click", (event) => {
    event.stopPropagation();
    setOpen(!header.classList.contains("menu-open"));
  });
  document.addEventListener("click", (event) => {
    if (!header.contains(event.target)) setOpen(false);
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && header.classList.contains("menu-open")) {
      setOpen(false, true);
    }
  });
  actions.querySelectorAll("button:not(.menu-copy)").forEach((button) => {
    button.addEventListener("click", () => setOpen(false));
  });
  const desktop = window.matchMedia("(min-width: 641px)");
  desktop.addEventListener?.("change", (event) => {
    if (event.matches) setOpen(false);
  });
}

function closeHeaderMenu() {
  const header = document.querySelector(".app-header");
  if (!header) return;
  header.classList.remove("menu-open");
  const toggle = $("#menu-toggle");
  if (!toggle) return;
  toggle.setAttribute("aria-expanded", "false");
  toggle.setAttribute("aria-label", "Open menu");
}

function syncHeaderMenu() {
  const toggle = $("#menu-toggle");
  const actions = $("#header-actions");
  if (!toggle || !actions) return;
  const visible =
    !$("#menu-connection")?.hidden ||
    [...actions.querySelectorAll("button:not(.menu-copy)")].some((button) => !button.hidden);
  toggle.hidden = !visible;
  if (!visible) closeHeaderMenu();
}

function el(tag, { className, text, attrs, icon } = {}, children = []) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (icon) node.append(iconNode(icon));
  if (text !== undefined) {
    if (tag === "button" || icon) {
      const label = document.createElement("span");
      label.className = "button-label";
      label.textContent = text;
      node.append(label);
    } else {
      node.textContent = text;
    }
  }
  if (attrs) {
    for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
  }
  for (const child of children) node.append(child);
  return node;
}

function iconNode(name) {
  const namespace = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(namespace, "svg");
  svg.setAttribute("class", "icon");
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS(namespace, "use");
  use.setAttribute("href", `#icon-${name}`);
  svg.append(use);
  return svg;
}
