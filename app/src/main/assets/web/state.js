/**
 * A deliberately tiny observable store. The console has one screen on view at
 * a time and a handful of cached collections, so a full reactive framework
 * would be noise. Views read from here and the shell re-renders on change.
 */

function readStored(key, allowed, fallback) {
  try {
    const v = localStorage.getItem(key);
    return allowed.includes(v) ? v : fallback;
  } catch {
    return fallback;
  }
}

export const store = {
  locale: "ru",
  theme: readStored("jarvis.theme", ["system", "dark", "light"], "system"),
  authenticated: false,

  /** Last `GET /status` payload, or null before first load. */
  status: null,
  /** `GET /settings` array, or null when not yet loaded. */
  settings: null,
  /** `GET /secrets` metadata array, or null. */
  secrets: null,
  /** `GET /mcp/servers` array, or null. */
  mcp: null,

  /** Current route: { name, category }. */
  route: { name: "status", category: null },
  /** Epoch ms of the last successful status poll. */
  lastPolledAt: 0,
  /** True while any blocking load is in flight. */
  loading: false,

  _listeners: new Set(),

  set(patch, { silent = false } = {}) {
    Object.assign(this, patch);
    if (!silent) this.emit();
  },

  subscribe(fn) {
    this._listeners.add(fn);
    return () => this._listeners.delete(fn);
  },

  emit() {
    for (const fn of this._listeners) {
      try { fn(this); } catch (err) { console.error("store listener failed", err); }
    }
  },

  /** Index of settings by key, rebuilt whenever `settings` changes. */
  settingsByKey() {
    const map = new Map();
    if (Array.isArray(this.settings)) {
      for (const s of this.settings) map.set(s.key, s);
    }
    return map;
  },

  pendingPolicies() {
    const raw = this.status && Array.isArray(this.status.pendingPolicies)
      ? this.status.pendingPolicies
      : [];
    return normalizePending(raw, this.settingsByKey());
  },
};

/**
 * Normalize `pendingPolicies` defensively. The contract does not pin the
 * element shape, so accept:
 *   - policy tokens ("LIVE" | "SERVICE_RESTART" | "APP_RESTART", any case)
 *   - setting keys (mapped to that setting's policy)
 *   - objects ({key,policy} or {policy})
 * Returns a list of `{ policy, keys[] }` in restart-severity order, with LIVE
 * dropped (a live change is never pending a restart).
 */
export function normalizePending(raw, byKey) {
  const groups = new Map([
    ["SERVICE_RESTART", []],
    ["APP_RESTART", []],
  ]);
  const unknown = [];

  const add = (policy, key) => {
    const p = String(policy || "").toUpperCase();
    if (p !== "SERVICE_RESTART" && p !== "APP_RESTART") {
      if (key && !unknown.includes(key)) unknown.push(key);
      return;
    }
    if (key && !groups.get(p).includes(key)) groups.get(p).push(key);
  };

  for (const item of raw || []) {
    if (item == null) continue;
    if (typeof item === "string") {
      const upper = item.toUpperCase();
      if (upper === "SERVICE_RESTART" || upper === "APP_RESTART") {
        add(upper, null);
      } else if (byKey && byKey.has(item)) {
        add(byKey.get(item).policy, item);
      } else {
        // A pending key we cannot resolve yet: surface it rather than hide it.
        unknown.push(item);
      }
    } else if (typeof item === "object") {
      const policy = item.policy || item.applyPolicy;
      const key = item.key || item.setting || item.name;
      if (policy) add(policy, key);
      else if (key && byKey && byKey.has(key)) add(byKey.get(key).policy, key);
      else if (key) unknown.push(key);
    }
  }

  const out = [];
  for (const [policy, keys] of groups) {
    if (keys.length || raw.some((r) => typeof r === "string" && r.toUpperCase() === policy)) {
      out.push({ policy, keys });
    }
  }
  if (unknown.length) out.push({ policy: "UNKNOWN", keys: unknown });
  return out;
}
