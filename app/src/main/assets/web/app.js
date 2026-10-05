/**
 * Application shell and router.
 *
 * Boot sequence: hydrate locale/theme → probe the session with `GET /status`
 * → either show the login view or build the shell. The shell owns navigation,
 * the persistent pending-policy banner, status polling and the global 401
 * drop-to-login. Views are pure renderers that receive a small `ctx` and call
 * back into this module to reload data.
 */

import { api, ApiError, onUnauthorized, errorMessage } from "./api.js";
import { detectLocale, getLocale, setLocale, t } from "./i18n.js";
import { store } from "./state.js";
import {
  brandMark, button, clear, el, icon, iconButton, policyBadgeEl, statusPill, toast, toastError, toastWarn,
} from "./ui.js";
import { renderLogin } from "./views/login.js";
import { renderStatus, normalizeMode } from "./views/status.js";
import { renderSettings, categoryLabel } from "./views/settings.js";
import { renderSecrets } from "./views/secrets.js";
import { renderMcp } from "./views/mcp.js";
import { renderManagement } from "./views/management.js";
import { renderExport } from "./views/export.js";
import { renderPassword } from "./views/password.js";

const POLL_INTERVAL_MS = 15000;
const ROUTES = ["status", "settings", "secrets", "mcp", "management", "export", "password"];

const appRoot = document.getElementById("app");

let viewSlot = null;
let bannerSlot = null;
let titleEl = null;
let subtitleEl = null;
let headerStatusEl = null;
let navEl = null;
let pollTimer = null;
let loaded = { settings: false, secrets: false, mcp: false };

/* --------------------------------------------------------------- Context --- */

const ctx = {
  store,
  navigate,
  rerender: renderCurrentView,
  refreshStatus,
  reloadSettings: () => reload("settings"),
  reloadSecrets: () => reload("secrets"),
  reloadMcp: () => reload("mcp"),
  loadSettings: (force) => loadSettings(force),
  loadSecrets: (force) => loadSecrets(force),
  loadMcp: (force) => loadMcp(force),
  onSettingSaved,
  onAuthenticated: enterApp,
  onPasswordChanged: handlePasswordChanged,
  localeToggle,
  themeToggle,
};

/* ------------------------------------------------------------------ Boot --- */

async function boot() {
  setLocale(detectLocale());
  applyTheme(store.theme);

  onUnauthorized(() => {
    if (!store.authenticated) return;
    store.authenticated = false;
    stopPolling();
    showLogin();
    toastWarn(t("common.sessionExpired"));
  });

  try {
    const status = await api.status();
    store.status = status;
    store.lastPolledAt = Date.now();
    store.authenticated = true;
    enterApp({ haveStatus: true });
  } catch (err) {
    if (err instanceof ApiError && err.isAuth) showLogin();
    else showLogin();
  }
}

async function enterApp({ haveStatus = false } = {}) {
  store.authenticated = true;
  buildShell();
  // The status payload is only readable AFTER authentication, so a fresh login
  // (unlike a boot that was already authenticated) must fetch it here before
  // the first view renders. A failure is non-fatal: polling retries.
  if (!haveStatus) {
    try {
      store.status = await api.status();
      store.lastPolledAt = Date.now();
    } catch { /* poll will recover */ }
  }
  applyRoute(parseHash(), { force: true });
  startPolling();
  // The pending-policy banner maps `/status.pendingPolicies` keys to their
  // policies via `/settings`, so seed that metadata even on the Status screen.
  loadSettings().catch(() => {});
}

function showLogin() {
  store.authenticated = false;
  stopPolling();
  viewSlot = null;
  bannerSlot = null;
  clear(appRoot);
  appRoot.appendChild(renderLogin(ctx));
}

/* ----------------------------------------------------------------- Shell --- */

function buildShell() {
  navEl = buildNav();
  titleEl = el("h1", {});
  subtitleEl = el("p", { class: "topbar__sub" });
  headerStatusEl = el("div", { class: "row" });
  bannerSlot = el("div", {});
  viewSlot = el("div", {});

  const sidebar = el("aside", { class: "sidebar" },
    el("div", { class: "sidebar__brand" },
      brandMark({ size: 38 }),
      el("div", { class: "sidebar__brand-text" },
        el("span", { class: "sidebar__wordmark", text: t("common.appName").toUpperCase() }),
        el("span", { class: "sidebar__tag", text: t("common.console") })
      )
    ),
    navEl,
    el("div", { class: "sidebar__foot" },
      el("div", { class: "sidebar__controls" }, ctx.themeToggle(), ctx.localeToggle(), el("span", { class: "spacer" }), logoutButton()),
      el("p", { class: "tiny faint", text: t("common.kicker") })
    )
  );

  const menuBtn = iconButton("menu", {
    title: t("common.menu"),
    onClick: () => appRoot.querySelector(".shell")?.classList.toggle("nav-open"),
  });
  // Close the drawer when a nav item is chosen (mobile).
  navEl.addEventListener("click", () => appRoot.querySelector(".shell")?.classList.remove("nav-open"));

  const topbar = el("header", { class: "topbar" },
    el("div", { class: "mobile-bar" }, menuBtn, brandMark({ size: 28 })),
    el("div", { class: "topbar__title" }, titleEl, subtitleEl),
    el("div", { class: "topbar__actions" },
      headerStatusEl,
      button(t("common.refresh"), {
        variant: "ghost",
        size: "btn--sm",
        iconName: "refresh",
        cls: "btn--responsive-hide",
        onClick: () => refreshEverything(),
      }),
      iconButton("logout", { title: t("common.logout"), onClick: logout }),
    )
  );

  const shell = el("div", { class: "shell" },
    sidebar,
    el("main", { class: "main" },
      topbar,
      el("div", { class: "main__inner" }, bannerSlot, viewSlot)
    )
  );

  clear(appRoot);
  appRoot.appendChild(shell);
  document.title = `${t("common.appName")} — ${t("common.console")}`;
}

function buildNav() {
  const nav = el("nav", { class: "nav", "aria-label": t("common.console") });
  const sections = [
    { label: t("nav.sectionMain"), items: [navItem("status", "pulse", t("nav.status"))] },
    {
      label: t("nav.sectionConfig"),
      items: [
        navItem("settings", "sliders", t("nav.settings")),
        navItem("secrets", "key", t("nav.secrets")),
        navItem("mcp", "plug", t("nav.mcp")),
      ],
    },
    {
      label: t("nav.sectionSecurity"),
      items: [
        navItem("management", "shield", t("nav.management")),
        navItem("export", "swap", t("nav.export")),
        navItem("password", "lock", t("nav.password")),
      ],
    },
  ];
  for (const section of sections) {
    nav.appendChild(el("p", { class: "nav__label", text: section.label }));
    for (const item of section.items) nav.appendChild(item);
  }
  return nav;
}

function navItem(name, iconName, label) {
  return el("button", {
    type: "button",
    class: "nav__item",
    dataset: { route: name },
    onClick: () => navigate({ name }),
  }, icon(iconName, { size: 18 }), el("span", { text: label }));
}

function logoutButton() {
  return iconButton("logout", { title: t("common.logout"), onClick: logout });
}

async function logout() {
  try { await api.logout(); } catch { /* the cookie may already be gone */ }
  store.status = null;
  store.settings = null;
  store.secrets = null;
  store.mcp = null;
  loaded = { settings: false, secrets: false, mcp: false };
  showLogin();
}

/* ---------------------------------------------------------------- Router --- */

function parseHash() {
  const raw = (location.hash || "").replace(/^#\/?/, "");
  const [name, category] = raw.split("/");
  if (!ROUTES.includes(name)) return { name: "status", category: null };
  return { name, category: category ? decodeURIComponent(category) : null };
}

function routeToHash(route) {
  if (route.name === "settings" && route.category) return `#/settings/${encodeURIComponent(route.category)}`;
  return `#/${route.name}`;
}

function navigate(route) {
  const hash = routeToHash(route);
  if (location.hash === hash) {
    applyRoute(route, { force: true });
  } else {
    location.hash = hash;
  }
}

function applyRoute(route, { force = false } = {}) {
  const next = route || parseHash();
  const changed = force || next.name !== store.route.name || next.category !== store.route.category;
  store.route = next;
  if (!changed) return;
  updateActiveNav();
  renderCurrentView();
}

window.addEventListener("hashchange", () => applyRoute(parseHash()));

/* ------------------------------------------------------------ View render --- */

async function renderCurrentView() {
  if (!store.authenticated || !viewSlot) return;
  updateTitle();
  updateChrome();

  // Ensure the data a view needs is present before mounting it.
  try {
    await ensureData(store.route);
  } catch (err) {
    // 401 is handled globally; anything else shows an honest toast.
    if (!(err instanceof ApiError && err.isAuth)) {
      toastError(errorMessage(err, t("common.loadFailed")));
    }
    return;
  }

  const node = buildView(store.route);
  clear(viewSlot);
  if (node) viewSlot.appendChild(node);
}

function buildView(route) {
  switch (route.name) {
    case "status": return renderStatus(ctx);
    case "settings": return renderSettings(ctx);
    case "secrets": return renderSecrets(ctx);
    case "mcp": return renderMcp(ctx);
    case "management": return renderManagement(ctx);
    case "export": return renderExport(ctx);
    case "password": return renderPassword(ctx);
    default: return renderStatus(ctx);
  }
}

async function ensureData(route) {
  switch (route.name) {
    case "settings":
      await loadSettings();
      break;
    case "secrets":
      await Promise.all([loadSecrets(), loadMcp().catch(() => {})]); // mcp labels
      break;
    case "mcp":
      await Promise.all([loadMcp(), loadSecrets().catch(() => {})]); // set-state
      break;
    case "export":
      await loadSecrets();
      break;
    default:
      break;
  }
}

/* --------------------------------------------------------------- Loaders --- */

let inflight = { settings: null, secrets: null, mcp: null, status: null };

/** Fetch a collection without rendering; `reload` is the render-after variant. */
async function fetchCollection(name, force) {
  if (inflight[name]) return inflight[name];
  if (loaded[name] && !force) return;
  inflight[name] = (async () => {
    try {
      if (name === "settings") store.settings = await api.settings();
      else if (name === "secrets") store.secrets = await api.secrets();
      else store.mcp = await api.mcpServers();
      loaded[name] = true;
      // Refresh chrome (chiefly the pending-policy banner) without re-rendering
      // the view, so an in-progress form keeps focus.
      if (store.authenticated && viewSlot) updateChrome();
    } catch (err) {
      if (!(err instanceof ApiError && err.isAuth)) toastError(errorMessage(err, t("common.loadFailed")));
      throw err;
    } finally {
      inflight[name] = null;
    }
  })();
  return inflight[name];
}

const loadSettings = (force = false) => fetchCollection("settings", force);
const loadSecrets = (force = false) => fetchCollection("secrets", force);
const loadMcp = (force = false) => fetchCollection("mcp", force);

async function reload(name) {
  await fetchCollection(name, true);
  renderCurrentView();
}

async function refreshStatus() {
  if (!store.authenticated) return;
  try {
    store.status = await api.status();
    store.lastPolledAt = Date.now();
    updateChrome();
    if (store.route.name === "status" && viewSlot) renderCurrentView();
    if (store.route.name === "management" && viewSlot) renderCurrentView();
  } catch (err) {
    if (!(err instanceof ApiError && err.isAuth)) {
      // A transient poll failure is not worth a toast on every tick.
      console.warn("status poll failed", err);
    }
  }
}

async function refreshEverything() {
  try {
    await Promise.all([refreshStatus(), loadSettings(true), loadSecrets(true), loadMcp(true)]);
    if (viewSlot) renderCurrentView();
  } catch {
    /* individual loaders already reported */
  }
}

function onSettingSaved() {
  refreshStatus();
}

/* ------------------------------------------------------- Chrome / banner --- */

function updateTitle() {
  const route = store.route;
  const meta = routeMeta(route);
  titleEl.textContent = meta.title;
  subtitleEl.textContent = meta.subtitle;
}

function routeMeta(route) {
  switch (route.name) {
    case "status": return { title: t("status.title"), subtitle: t("status.subtitle") };
    case "settings": {
      const cat = route.category ? categoryLabel(route.category) : null;
      return { title: t("settings.title"), subtitle: cat ? `${t("settings.subtitle")} · ${cat}` : t("settings.subtitle") };
    }
    case "secrets": return { title: t("secrets.title"), subtitle: t("secrets.subtitle") };
    case "mcp": return { title: t("mcp.title"), subtitle: t("mcp.subtitle") };
    case "management": return { title: t("management.title"), subtitle: t("management.subtitle") };
    case "export": return { title: t("exportView.title"), subtitle: t("exportView.subtitle") };
    case "password": return { title: t("passwordView.title"), subtitle: t("passwordView.subtitle") };
    default: return { title: t("common.console"), subtitle: "" };
  }
}

function updateChrome() {
  if (!store.authenticated || !headerStatusEl) return;
  updateActiveNav();
  renderBanner();

  clear(headerStatusEl);
  const mgmt = (store.status && store.status.management) || {};
  const mode = normalizeMode(mgmt.mode);
  const active = !!mgmt.active;
  headerStatusEl.appendChild(statusPill(
    active ? `${t("status.active")} · ${modeLabel(mode)}` : t("status.inactive"),
    { cls: active ? (mode === "lan" ? "status-pill--lan" : "status-pill--active") : "status-pill--stopped" }
  ));
}

function modeLabel(mode) {
  if (mode === "lan") return t("status.modeLan");
  if (mode === "localhost") return t("status.modeLocalhost");
  return t("status.modeDisabled");
}

function updateActiveNav() {
  if (!navEl) return;
  for (const item of navEl.querySelectorAll(".nav__item")) {
    item.classList.toggle("is-active", item.dataset.route === store.route.name);
  }
}

function renderBanner() {
  if (!bannerSlot) return;
  clear(bannerSlot);
  const groups = store.pendingPolicies();
  if (!groups.length) return;

  const list = el("ul", { class: "banner__list" });
  for (const group of groups) {
    for (const key of group.keys) {
      list.appendChild(el("li", {},
        policyBadgeEl(group.policy),
        el("span", { class: "mono small", text: key }),
        el("span", { class: "small muted", text: policyExplain(group.policy) })
      ));
    }
    if (!group.keys.length) {
      list.appendChild(el("li", {}, policyBadgeEl(group.policy), el("span", { class: "small muted", text: policyExplain(group.policy) })));
    }
  }

  bannerSlot.appendChild(el("div", { class: "banner banner--warn" },
    icon("alert", { size: 20 }),
    el("div", { class: "banner__body" },
      el("p", { class: "banner__title", text: t("policy.title") }),
      el("p", { class: "banner__text", text: t("policy.text") }),
      list
    )
  ));
  bannerSlot.classList.add("mt-4");
}

function policyExplain(policy) {
  const p = String(policy).toUpperCase();
  if (p === "SERVICE_RESTART") return t("policy.SERVICE_RESTART_hint");
  if (p === "APP_RESTART") return t("policy.APP_RESTART_hint");
  if (p === "LIVE") return t("policy.LIVE_hint");
  return t("policy.UNKNOWN_hint");
}

/* ----------------------------------------------------------------- Theme --- */

function applyTheme(theme) {
  document.documentElement.setAttribute("data-theme", theme);
  store.theme = theme;
  try { localStorage.setItem("jarvis.theme", theme); } catch { /* ignore */ }
}

function themeToggle() {
  const order = ["system", "dark", "light"];
  const labels = { system: t("common.themeSystem"), dark: t("common.themeDark"), light: t("common.themeLight") };
  const icons = { system: "monitor", dark: "moon", light: "sun" };

  const btn = iconButton(icons[store.theme] || "monitor", {
    title: `${t("common.theme")}: ${labels[store.theme] || labels.system}`,
    onClick: () => {
      const next = order[(order.indexOf(store.theme) + 1) % order.length];
      applyTheme(next);
      const title = `${t("common.theme")}: ${labels[next]}`;
      btn.replaceChildren(icon(icons[next], { size: 17 }));
      btn.title = title;
      btn.setAttribute("aria-label", title);
    },
  });
  return btn;
}

function localeToggle() {
  return iconButton(null, {
    label: getLocale().toUpperCase(),
    title: t("common.language"),
    onClick: () => {
      setLocale(getLocale() === "ru" ? "en" : "ru");
      // Re-render both chrome and view; content strings are all keyed.
      buildShell();
      applyRoute(store.route, { force: true });
    },
  });
}

/* ---------------------------------------------------------------- Polling --- */

function startPolling() {
  stopPolling();
  pollTimer = setInterval(() => {
    if (document.hidden) return;
    refreshStatus();
  }, POLL_INTERVAL_MS);
}

function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
}

document.addEventListener("visibilitychange", () => {
  if (!document.hidden && store.authenticated) refreshStatus();
});
window.addEventListener("focus", () => {
  if (store.authenticated) refreshStatus();
});

/* -------------------------------------------------------------- Password --- */

function handlePasswordChanged() {
  store.authenticated = false;
  stopPolling();
  showLogin();
  toast(t("passwordView.changed"), "success", { timeout: 8000 });
}

/* -------------------------------------------------------------------- Go --- */

boot();
