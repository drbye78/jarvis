/**
 * Secrets. The API is write-only: it reports `{key, set}` and never returns a
 * value, so this view can only set a new value or clear the existing one. No
 * value is ever rendered — not in the DOM, not in a tooltip.
 */

import { api, errorMessage } from "../api.js";
import { t } from "../i18n.js";
import {
  badge, button, card, confirmModal, el, icon, iconButton, openModal, setBusy, toastError, toastSuccess,
} from "../ui.js";

/** Friendly Russian names for the known vault keys; unknown keys fall back. */
const SECRET_LABELS = {
  picovoice_key: "Picovoice — ключ активации",
  salute_client_id: "Sber Salute — Client ID",
  salute_client_secret: "Sber Salute — Client Secret",
  gigachat_client_id: "GigaChat — Client ID",
  gigachat_client_secret: "GigaChat — Client Secret",
  openai_api_key: "OpenAI-совместимый API-ключ",
  yandex_api_key: "Yandex Cloud API-ключ (речь и AI Studio)",
  mapkit_api_key: "Yandex MapKit — ключ",
  open_meteo_proxy: "Прокси Open-Meteo",
  management_password: "Пароль управления",
  gigachat_token: "GigaChat — служебный токен",
  gigachat_token_expiry: "GigaChat — срок токена",
  salute_token: "Sber Salute — служебный токен",
  salute_token_expiry: "Sber Salute — срок токена",
};

/** Keys the app maintains itself; clearing them is usually a mistake. */
const INTERNAL = new Set([
  "gigachat_token", "gigachat_token_expiry", "salute_token", "salute_token_expiry",
]);

export function renderSecrets(ctx) {
  const secrets = ctx.store.secrets;
  const root = el("div", { class: "stack view" });

  if (!Array.isArray(secrets)) {
    root.appendChild(el("p", { class: "muted", text: t("common.loading") }));
    return root;
  }

  const rows = secrets.slice().sort((a, b) => labelFor(a.key, ctx).localeCompare(labelFor(b.key, ctx), "ru"));
  const setCount = rows.filter((r) => r.set).length;

  root.appendChild(el("div", { class: "row" },
    el("span", { class: "small faint", text: `${setCount} / ${rows.length}` }),
    el("span", { class: "spacer" }),
    button(t("common.refresh"), { size: "btn--sm", iconName: "refresh", onClick: () => ctx.reloadSecrets() })
  ));

  if (!rows.length) {
    root.appendChild(el("div", { class: "empty" },
      icon("key", { size: 36 }),
      el("p", { class: "empty__title", text: t("secrets.empty") })
    ));
    return root;
  }

  const table = el("table", { class: "data-table" });
  table.appendChild(el("thead", {},
    el("tr", {},
      el("th", { text: t("secrets.key") }),
      el("th", { text: t("secrets.state") }),
      el("th", { class: "cell-actions", text: t("secrets.actions") })
    )
  ));
  const body = el("tbody", {});
  for (const item of rows) body.appendChild(secretRow(item, ctx));
  table.appendChild(body);

  root.appendChild(card({
    flush: true,
    body: el("div", { class: "table-wrap" }, table),
    footer: el("p", { class: "small faint", text: t("secrets.writeOnlyNote") }),
  }));
  return root;
}

function secretRow(item, ctx) {
  const isSet = !!item.set;
  const tr = el("tr", {});
  tr.appendChild(el("td", {},
    el("div", { text: labelFor(item.key, ctx) }),
    el("div", { class: "mono tiny faint", text: item.key })
  ));
  tr.appendChild(el("td", {}, badge(isSet ? t("secrets.isSet") : t("secrets.isUnset"), isSet ? "badge--ok" : "badge--muted")));

  const actions = el("td", { class: "cell-actions" });
  actions.appendChild(button(t("secrets.setNew"), {
    size: "btn--sm",
    iconName: "edit",
    onClick: () => openSetDialog(item, ctx),
  }));
  if (isSet) {
    actions.appendChild(button(t("secrets.clear"), {
      size: "btn--sm",
      variant: "btn--danger",
      iconName: "trash",
      onClick: () => clearSecret(item, ctx),
    }));
  }
  tr.appendChild(actions);
  return tr;
}

/* ----------------------------------------------------------- Set dialog --- */

function openSetDialog(item, ctx) {
  const input = el("input", {
    class: "input",
    type: "password",
    autocomplete: "new-password",
    placeholder: t("secrets.newValuePlaceholder"),
  });
  let revealed = false;
  const reveal = iconButton("eye", {
    title: t("login.showPassword"),
    onClick: () => {
      revealed = !revealed;
      input.type = revealed ? "text" : "password";
      reveal.setAttribute("aria-label", revealed ? t("login.hidePassword") : t("login.showPassword"));
      reveal.title = revealed ? t("login.hidePassword") : t("login.showPassword");
    },
  });
  const error = el("p", { class: "field__error", role: "alert" });
  error.hidden = true;

  const body = [
    el("div", { class: "field" },
      el("label", { class: "field__label", text: t("secrets.newValue") }),
      el("div", { class: "input-group" }, input, reveal)
    ),
    el("p", { class: "field__hint", text: t("secrets.writeOnlyNote") }),
    error,
  ];

  let modal;
  const save = button(t("common.save"), { variant: "btn--primary", onClick: () => submit() });
  const cancel = button(t("common.cancel"), { onClick: () => modal.close() });
  modal = openModal({
    title: t("secrets.setTitleFor", { name: labelFor(item.key, ctx) }),
    subtitle: item.key,
    body,
    actions: [cancel, save],
  });
  input.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); submit(); } });

  async function submit() {
    const value = input.value;
    if (!value) {
      error.textContent = t("secrets.valueRequired");
      error.hidden = false;
      input.focus();
      return;
    }
    setBusy(save, true);
    try {
      await api.putSecret(item.key, value);
      toastSuccess(t("secrets.setOk"));
      modal.close();
      ctx.reloadSecrets();
    } catch (err) {
      error.textContent = errorMessage(err, t("common.saveFailed"));
      error.hidden = false;
      setBusy(save, false);
    }
  }
}

async function clearSecret(item, ctx) {
  const ok = await confirmModal({
    title: t("secrets.clearTitle"),
    text: t("secrets.clearText", { name: labelFor(item.key, ctx) }),
    confirmLabel: t("secrets.clear"),
    danger: true,
  });
  if (!ok) return;
  try {
    await api.deleteSecret(item.key);
    toastSuccess(t("secrets.clearOk"));
    ctx.reloadSecrets();
  } catch (err) {
    toastError(errorMessage(err, t("common.saveFailed")));
  }
}

/* ------------------------------------------------------------- Helpers --- */

export function labelFor(key, ctx) {
  if (SECRET_LABELS[key]) return SECRET_LABELS[key];
  if (String(key).startsWith("mcp_secret_")) {
    const id = String(key).slice("mcp_secret_".length);
    const server = Array.isArray(ctx.store.mcp) ? ctx.store.mcp.find((s) => s.id === id) : null;
    return server ? `MCP — ${server.displayName || id}` : `MCP — секрет ${id.slice(0, 8)}…`;
  }
  return key;
}

export function isInternalSecret(key) {
  return INTERNAL.has(key);
}

export function countSetSecrets(ctx) {
  if (!Array.isArray(ctx.store.secrets)) return 0;
  return ctx.store.secrets.filter((s) => s.set).length;
}
