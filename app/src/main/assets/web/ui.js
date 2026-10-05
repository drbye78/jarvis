/**
 * DOM + presentation helpers. Everything the console builds is assembled from
 * these functions so user-controlled strings always go through `textContent`
 * (never `innerHTML`), which keeps the strict no-inline CSP honest and closes
 * the obvious injection paths (setting values, MCP names, error messages).
 */

import { t, policyBadge } from "./i18n.js";

/* ------------------------------------------------------------------ DOM --- */

/**
 * Hyperscript-ish builder. `attrs` keys:
 *   class, text, html (trusted only), dataset, on<event>, style (object),
 *   value, any DOM property, else an attribute.
 */
export function el(tag, attrs = null, ...children) {
  const node = document.createElement(tag);
  if (attrs) {
    for (const [key, value] of Object.entries(attrs)) {
      if (value == null || value === false) continue;
      if (key === "class") node.className = value;
      else if (key === "text") node.textContent = value;
      else if (key === "html") node.innerHTML = value; // trusted, static markup only
      else if (key === "dataset") Object.assign(node.dataset, value);
      else if (key === "style" && typeof value === "object") {
        for (const [prop, v] of Object.entries(value)) node.style.setProperty(prop, v);
      } else if (key.startsWith("on") && typeof value === "function") {
        node.addEventListener(key.slice(2).toLowerCase(), value);
      } else if (key === "value") {
        node.value = value;
      } else if (key in node && key !== "list" && key !== "form") {
        try { node[key] = value; } catch { node.setAttribute(key, value); }
      } else {
        node.setAttribute(key, value === true ? "" : value);
      }
    }
  }
  append(node, children);
  return node;
}

export function append(parent, children) {
  for (const child of children) {
    if (child == null || child === false) continue;
    if (Array.isArray(child)) append(parent, child);
    else if (child instanceof Node) parent.appendChild(child);
    else parent.appendChild(document.createTextNode(String(child)));
  }
  return parent;
}

export function frag(...children) {
  const f = document.createDocumentFragment();
  append(f, children);
  return f;
}

export function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
  return node;
}

export function text(value) {
  return document.createTextNode(value == null ? "" : String(value));
}

/* ---------------------------------------------------------------- Icons --- */

const ICONS = {
  pulse: '<path d="M3 12h3l2.5-7 3.5 14 3-9 2 2h4"/>',
  sliders: '<path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0"/><circle cx="16" cy="6" r="2"/><circle cx="10" cy="12" r="2"/><circle cx="18" cy="18" r="2"/>',
  key: '<circle cx="8" cy="15" r="4"/><path d="M10.8 12.2 20 3m-3 0h3v3m-6 0h3v3"/>',
  plug: '<path d="M9 3v5M15 3v5M7 8h10v3a5 5 0 0 1-5 5 5 5 0 0 1-5-5V8ZM12 16v5"/>',
  shield: '<path d="M12 3 5 6v5c0 4.4 3 8.3 7 9.6 4-1.3 7-5.2 7-9.6V6l-7-3Z"/><path d="m9 12 2 2 4-4"/>',
  swap: '<path d="M7 4v13M7 17l-3-3m3 3 3-3M17 20V7m0 0-3 3m3-3 3 3"/>',
  lock: '<rect x="4.5" y="10.5" width="15" height="10" rx="2"/><path d="M8 10.5V7a4 4 0 0 1 8 0v3.5"/>',
  logout: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/>',
  menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z"/>',
  monitor: '<rect x="2.5" y="4" width="19" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>',
  copy: '<rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
  check: '<path d="m5 13 4 4L19 7"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  trash: '<path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/>',
  edit: '<path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
  refresh: '<path d="M21 12a9 9 0 1 1-2.6-6.4M21 3v6h-6"/>',
  alert: '<path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/><path d="M12 9v4M12 17h.01"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
  eye: '<path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6-10-6-10-6Z"/><circle cx="12" cy="12" r="2.6"/>',
  eyeOff: '<path d="M3 3l18 18M10.6 5.1A9.7 9.7 0 0 1 12 5c6.5 0 10 6 10 6a17 17 0 0 1-3 3.6M6.2 6.6C3.8 8.2 2 11 2 11s3.5 6 10 6a9.8 9.8 0 0 0 3.4-.6M9.9 9.9a3 3 0 0 0 4.2 4.2"/>',
  download: '<path d="M12 3v12m0 0 4-4m-4 4-4-4M4 19h16"/>',
  upload: '<path d="M12 15V3m0 0 4 4m-4-4L8 7M4 19h16"/>',
  file: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5Z"/><path d="M14 3v5h5"/>',
  server: '<rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><path d="M7 7.5h.01M7 16.5h.01"/>',
  globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18 14 14 0 0 1 0-18Z"/>',
  home: '<path d="M4 10.5 12 4l8 6.5V20a1 1 0 0 1-1 1h-5v-6H10v6H5a1 1 0 0 1-1-1Z"/>',
  wifi: '<path d="M2.5 9a15 15 0 0 1 19 0M6 12.5a10 10 0 0 1 12 0M9.5 16a5 5 0 0 1 5 0"/><path d="M12 20h.01"/>',
  terminal: '<path d="M4 17l5-5-5-5M12 19h8"/>',
  arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
};

/** A stroked 24×24 icon. Markup is static and trusted. */
export function icon(name, { size = 18, cls = "" } = {}) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "1.8");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("width", String(size));
  svg.setAttribute("height", String(size));
  if (cls) svg.setAttribute("class", cls);
  svg.innerHTML = ICONS[name] || ICONS.info;
  return svg;
}

/** The arc-reactor brand mark (SVG rings + a glowing core). */
export function brandMark({ size = 40, pulse = false } = {}) {
  const wrap = el("div", { class: `brand-mark${pulse ? " brand-mark--pulse" : ""}`, "aria-hidden": "true" });
  wrap.style.setProperty("--size", `${size}px`);
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 48 48");
  svg.innerHTML =
    '<circle class="ring-outer" cx="24" cy="24" r="19"/>' +
    '<circle class="ring-mid" cx="24" cy="24" r="12.5" stroke-dasharray="2.5 4"/>' +
    '<g class="ring-tick"><path d="M24 2.5v5M24 40.5v5M2.5 24h5M40.5 24h5"/></g>';
  wrap.appendChild(svg);
  wrap.appendChild(el("span", { class: "brand-core" }));
  return wrap;
}

/* --------------------------------------------------------------- Badges --- */

export function badge(textValue, cls = "badge--muted", { dot = true } = {}) {
  return el("span", { class: `badge ${cls}${dot ? "" : " badge--plain"}`, text: textValue });
}

export function policyBadgeEl(policy) {
  const { cls, text: label } = policyBadge(policy);
  const node = badge(label, cls);
  node.title = policyLabelSafe(policy);
  return node;
}

function policyLabelSafe(policy) {
  const p = String(policy || "").toUpperCase();
  if (p === "SERVICE_RESTART") return t("policy.SERVICE_RESTART_hint");
  if (p === "APP_RESTART") return t("policy.APP_RESTART_hint");
  return t("policy.LIVE_hint");
}

export function statusPill(label, { cls = "status-pill--stopped" } = {}) {
  return el("span", { class: `status-pill ${cls}` },
    el("span", { class: "status-dot" }),
    el("span", { text: label })
  );
}

/**
 * A card. `body` may be a Node, an array, or null. `flush` drops body padding
 * (for tables/lists that manage their own spacing).
 */
export function card({ title, subtitle, actions = null, body = null, footer = null, flush = false } = {}) {
  const node = el("section", { class: "card" });
  if (title || subtitle || actions) {
    const head = el("div", { class: "card__head" },
      el("div", { style: { flex: "1", minWidth: "0" } },
        title ? el("h3", { text: title }) : null,
        subtitle ? el("p", { class: "sub", text: subtitle }) : null
      ),
      actions || null
    );
    node.appendChild(head);
  }
  if (body != null) {
    const bodyEl = el("div", { class: `card__body${flush ? " card__body--flush" : ""}` });
    append(bodyEl, [body].flat().filter(Boolean));
    node.appendChild(bodyEl);
  }
  if (footer) {
    const footEl = el("div", { class: "card__foot" });
    append(footEl, [footer].flat().filter(Boolean));
    node.appendChild(footEl);
  }
  return node;
}

/* --------------------------------------------------------------- Buttons --- */

export function button(label, { variant = "", size = "", iconName = null, type = "button", onClick, title, cls = "" } = {}) {
  const btn = el("button", {
    type,
    class: `btn${variant ? ` ${variant}` : ""}${size ? ` ${size}` : ""}${cls ? ` ${cls}` : ""}`,
    onClick,
    title,
  });
  if (iconName) btn.appendChild(icon(iconName, { size: size === "btn--sm" ? 14 : 16 }));
  if (label) btn.appendChild(el("span", { text: label }));
  return btn;
}

export function iconButton(iconName, { label = null, title, onClick, cls = "" } = {}) {
  const btn = el("button", {
    type: "button",
    class: `icon-btn${label ? " icon-btn--label" : ""}${cls ? ` ${cls}` : ""}`,
    title: title || label || undefined,
    "aria-label": title || label || iconName || "button",
    onClick,
  });
  if (iconName) btn.appendChild(icon(iconName, { size: 17 }));
  if (label) btn.appendChild(el("span", { text: label }));
  return btn;
}

export function setBusy(btn, busy) {
  if (!btn) return;
  btn.classList.toggle("is-busy", !!busy);
  btn.disabled = !!busy;
}

/* ---------------------------------------------------------------- Fields --- */

export function field(label, control, { hint, error, id } = {}) {
  const controlId = id || `f-${Math.random().toString(36).slice(2, 9)}`;
  if (control && control.tagName && !control.id) control.id = controlId;
  if (label && control) control.setAttribute?.("aria-label", label);
  const wrap = el("div", { class: "field" });
  if (label) wrap.appendChild(el("label", { class: "field__label", for: controlId, text: label }));
  wrap.appendChild(control);
  if (hint) wrap.appendChild(el("p", { class: "field__hint", text: hint }));
  if (error) wrap.appendChild(el("p", { class: "field__error", text: error, role: "alert" }));
  return wrap;
}

/* --------------------------------------------------------------- Toasts --- */

let toastStack;

export function toast(message, kind = "info", { timeout = 4200 } = {}) {
  if (!toastStack) toastStack = document.getElementById("toasts");
  if (!toastStack) return;
  const iconName = kind === "error" ? "alert" : kind === "success" ? "check" : kind === "warn" ? "alert" : "info";
  const node = el("div", { class: `toast toast--${kind}`, role: "status" },
    icon(iconName, { size: 18 }),
    el("span", { class: "toast__msg", text: message }),
    iconButton("x", { title: t("common.close"), onClick: () => remove() })
  );
  function remove() {
    node.classList.add("is-leaving");
    setTimeout(() => node.remove(), 180);
  }
  toastStack.appendChild(node);
  if (timeout > 0) setTimeout(remove, timeout);
}

export const toastSuccess = (msg) => toast(msg, "success");
export const toastError = (msg) => toast(msg, "error", { timeout: 6000 });
export const toastWarn = (msg) => toast(msg, "warn", { timeout: 6000 });

/* ---------------------------------------------------------------- Modal --- */

let activeModal = null;

/**
 * Open a modal. `body` is a Node or array of Nodes; `actions` is a list of
 * buttons appended to the footer. Returns `{ close, root, footer }`.
 */
export function openModal({ title, subtitle, body, actions = [], size = "" } = {}) {
  closeModal();
  const root = document.getElementById("modal-root");
  const backdrop = el("div", { class: "modal-backdrop", onClick: () => close() });
  const modal = el("div", { class: `modal${size ? ` ${size}` : ""}`, role: "dialog", "aria-modal": "true" });
  modal.setAttribute("aria-label", title || t("common.console"));

  const head = el("div", { class: "modal__head" },
    el("div", { style: { flex: "1" } },
      el("h2", { text: title || "" }),
      subtitle ? el("p", { class: "sub", text: subtitle }) : null
    ),
    iconButton("x", { title: t("common.close"), onClick: () => close() })
  );

  const bodyEl = el("div", { class: "modal__body" });
  append(bodyEl, [body].flat().filter(Boolean));
  const footer = el("div", { class: "modal__foot" });
  append(footer, actions);

  modal.append(head, bodyEl, footer);
  clear(root);
  root.append(backdrop, modal);
  root.hidden = false;

  const previouslyFocused = document.activeElement;
  const onKey = (e) => {
    if (e.key === "Escape") { e.preventDefault(); close(); }
    else if (e.key === "Tab") trapFocus(e, modal);
  };
  document.addEventListener("keydown", onKey);

  const first = modal.querySelector("input, select, textarea, button:not(.icon-btn)");
  if (first) setTimeout(() => first.focus(), 30);

  activeModal = { root, modal, body: bodyEl, footer, onKey, previouslyFocused, close };
  return activeModal;

  function close() {
    document.removeEventListener("keydown", onKey);
    clear(root);
    root.hidden = true;
    activeModal = null;
    if (previouslyFocused && previouslyFocused.focus) previouslyFocused.focus();
  }
}

export function closeModal() {
  if (activeModal) activeModal.close();
}

function trapFocus(e, container) {
  const focusables = container.querySelectorAll(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  );
  if (!focusables.length) return;
  const first = focusables[0];
  const last = focusables[focusables.length - 1];
  if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
  else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
}

/** Promise-based confirm dialog. */
export function confirmModal({ title, text: bodyText, confirmLabel = t("common.confirm"), danger = false }) {
  return new Promise((resolve) => {
    const modal = openModal({
      title,
      size: "modal--sm",
      body: el("p", { class: "muted", text: bodyText }),
      actions: [],
    });
    const cancel = button(t("common.cancel"), { onClick: () => done(false) });
    const ok = button(confirmLabel, { variant: danger ? "btn--danger" : "btn--primary", onClick: () => done(true) });
    append(modal.footer, [cancel, ok]);
    setTimeout(() => ok.focus(), 30);
    function done(value) {
      modal.close();
      resolve(value);
    }
  });
}

/* ------------------------------------------------------------ Clipboard --- */

export async function copyText(value) {
  const str = String(value ?? "");
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(str);
      return true;
    }
  } catch { /* fall through to the legacy path */ }
  try {
    const area = document.createElement("textarea");
    area.value = str;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.opacity = "0";
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand("copy");
    area.remove();
    return ok;
  } catch {
    return false;
  }
}

/** A copy button bound to a (possibly lazily resolved) string. */
export function copyButton(getValue, { label = null, title = t("common.copy") } = {}) {
  const btn = iconButton("copy", {
    label,
    title,
    cls: "copy-btn",
    onClick: async () => {
      const ok = await copyText(typeof getValue === "function" ? getValue() : getValue);
      toast(ok ? t("common.copied") : t("common.copyFailed"), ok ? "success" : "error");
    },
  });
  return btn;
}

/* ------------------------------------------------------------ Formatters --- */

/** Group a hex fingerprint into space-separated octets for readability. */
export function formatFingerprint(value) {
  const hex = String(value || "").replace(/[^0-9a-fA-F]/g, "").toUpperCase();
  if (!hex) return "";
  return hex.replace(/(.{2})/g, "$1 ").trim();
}

export function formatTime(epochMs) {
  if (!epochMs) return t("common.notAvailable");
  try {
    return new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" }).format(new Date(epochMs));
  } catch {
    return t("common.notAvailable");
  }
}

export function formatIdleTimeout(ms) {
  const n = Number(ms);
  if (!Number.isFinite(n) || n <= 0) return t("status.idleDisabled");
  const minutes = Math.round(n / 60000);
  if (minutes >= 60 && minutes % 60 === 0) {
    const hours = minutes / 60;
    const unit = getLocaleSafe() === "en" ? "h" : "ч";
    return `${hours} ${unit}`;
  }
  return `${minutes} ${t("common.minutes")}`;
}

function getLocaleSafe() {
  return document.documentElement.lang === "en" ? "en" : "ru";
}

export function debounce(fn, wait = 200) {
  let timer;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), wait);
  };
}

/** True for a plain object; used when normalizing flexible API shapes. */
export function isObject(value) {
  return value != null && typeof value === "object" && !Array.isArray(value);
}
