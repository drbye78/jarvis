/**
 * API client for the frozen `/api/v1` management surface.
 *
 * Auth model: the browser authenticates once via `POST /sessions` and then
 * relies on the `HttpOnly; Secure; SameSite=Strict` session cookie, so every
 * request uses `credentials: 'same-origin'`. REST callers that hold the
 * password directly would use `Authorization: Bearer`, which the browser UI
 * deliberately never does.
 *
 * A `401` anywhere (except the login exchange itself) invokes the module-level
 * unauthorized handler exactly once per transition, so the shell can drop to
 * the login view without every view inventing its own redirect.
 */

import { t } from "./i18n.js";

const BASE = "/api/v1";

export class ApiError extends Error {
  constructor(status, code, message, cause) {
    super(message || code || `HTTP ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.code = code || "http_error";
    if (cause !== undefined) this.cause = cause;
  }
  get isAuth() { return this.status === 401; }
  get isLockout() { return this.status === 423 || this.status === 429; }
}

let unauthorizedHandler = null;
let unauthorizedSignalled = false;

/** Register the global 401 handler (the shell shows the login view). */
export function onUnauthorized(fn) {
  unauthorizedHandler = fn;
}

/** Re-arm the 401 signal after a successful login. */
export function resetAuthState() {
  unauthorizedSignalled = false;
}

async function request(path, { method = "GET", body, headers, raw = false, skipAuthRedirect = false } = {}) {
  const opts = {
    method,
    credentials: "same-origin",
    headers: { Accept: "application/json", ...(headers || {}) },
  };
  if (body !== undefined) {
    if (body instanceof FormData) {
      opts.body = body; // let the browser set the multipart boundary
    } else {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
  }

  let res;
  try {
    res = await fetch(BASE + path, opts);
  } catch (cause) {
    throw new ApiError(0, "network_error", null, cause);
  }

  if (res.status === 401) {
    if (!skipAuthRedirect && !unauthorizedSignalled) {
      unauthorizedSignalled = true;
      if (unauthorizedHandler) unauthorizedHandler();
    }
    throw new ApiError(401, "unauthorized", await readErrorMessage(res));
  }

  if (!res.ok) {
    throw new ApiError(res.status, await readErrorCode(res), await readErrorMessage(res));
  }

  if (raw) return res;
  if (res.status === 204) return null;

  const contentType = res.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  }
  return res.text();
}

/** The API speaks `{"error":{"code","message"}}`; degrade to text otherwise. */
async function readErrorMessage(res) {
  try {
    const text = await res.clone().text();
    if (!text) return null;
    try {
      const json = JSON.parse(text);
      if (json && json.error) return json.error.message || json.error.code || null;
      if (json && json.message) return json.message;
    } catch {
      return text.length < 300 ? text : null;
    }
  } catch {
    /* body unreadable */
  }
  return null;
}

async function readErrorCode(res) {
  try {
    const json = await res.clone().json();
    return (json && json.error && json.error.code) || "http_error";
  } catch {
    return "http_error";
  }
}

const enc = encodeURIComponent;

export const api = {
  // --- sessions -----------------------------------------------------------
  async login(password) {
    await request("/sessions", {
      method: "POST",
      body: { password },
      skipAuthRedirect: true, // a bad password must not trigger the global drop
    });
    resetAuthState();
  },
  async logout() {
    await request("/sessions/current", { method: "DELETE", skipAuthRedirect: true });
  },

  // --- status -------------------------------------------------------------
  status: () => request("/status"),

  // --- settings -----------------------------------------------------------
  settings: () => request("/settings"),
  setting: (key) => request(`/settings/${enc(key)}`),
  putSetting: (key, value) => request(`/settings/${enc(key)}`, { method: "PUT", body: { value } }),

  // --- secrets (write-only) ----------------------------------------------
  secrets: () => request("/secrets"),
  putSecret: (key, value) => request(`/secrets/${enc(key)}`, { method: "PUT", body: { value } }),
  deleteSecret: (key) => request(`/secrets/${enc(key)}`, { method: "DELETE" }),

  // --- MCP servers --------------------------------------------------------
  mcpServers: () => request("/mcp/servers"),
  createMcpServer: (payload) => request("/mcp/servers", { method: "POST", body: payload }),
  updateMcpServer: (id, payload) => request(`/mcp/servers/${enc(id)}`, { method: "PUT", body: payload }),
  deleteMcpServer: (id) => request(`/mcp/servers/${enc(id)}`, { method: "DELETE" }),
  putMcpSecret: (id, value) => request(`/mcp/servers/${enc(id)}/secret`, { method: "PUT", body: { value } }),

  // --- management mode ----------------------------------------------------
  setManagementMode: (mode, idleTimeoutMs) =>
    request("/management/mode", { method: "POST", body: { mode, idleTimeoutMs } }),

  // --- password -----------------------------------------------------------
  changePassword: (current, next) =>
    request("/password/change", { method: "POST", body: { current, next } }),

  // --- export / import ----------------------------------------------------
  /**
   * The export is an opaque encrypted envelope. Returns the Response so the
   * caller can stream it into a Blob and trigger a download.
   */
  export: (passphrase, includeSecrets) =>
    request("/export", {
      method: "POST",
      body: { passphrase, includeSecrets },
      raw: true,
    }),
  import: (passphrase, data) =>
    request("/import", { method: "POST", body: { passphrase, data } }),
};

/** Human message for an ApiError, honest about network failures. */
const ERROR_KEYS = {
  bad_password: "errors.badPassword",
  unauthorized: "errors.unauthorized",
  locked_out: "errors.locked",
  invalid_value: "errors.invalidValue",
  unwritable_setting: "errors.unwritableSetting",
  missing_value: "errors.missingValue",
  unknown_setting: "errors.unknownSetting",
  unknown_secret: "errors.unknownSecret",
  unknown_server: "errors.unknownServer",
  invalid_mode: "errors.invalidMode",
  invalid_password: "errors.invalidPassword",
  missing_fields: "errors.missingFields",
  missing_passphrase: "errors.missingPassphrase",
  secret_setting: "errors.secretSetting",
  invalid_body: "errors.invalidBody",
  bad_host: "errors.badHost",
  cross_origin: "errors.crossOrigin",
  not_found: "errors.notFound",
};

/**
 * Localize an error for display. The server's `message` is English, so prefer a
 * Russian string keyed by the machine `code`; fall back to the server message,
 * then the caller's fallback.
 */
export function errorMessage(err, fallback = t("common.serverError")) {
  if (!(err instanceof ApiError)) return fallback;
  if (err.status === 0 || err.code === "network_error") return t("common.networkError");
  if (err.status === 423 || err.status === 429) {
    return t(err.code === "locked_out" ? "errors.locked" : "errors.tooMany");
  }
  if (err.status === 401) return t("errors.unauthorized");
  const key = ERROR_KEYS[err.code];
  if (key) return t(key);
  if (err.status >= 500) return t("common.serverError");
  return err.message || fallback;
}
