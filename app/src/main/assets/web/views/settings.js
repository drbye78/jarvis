/**
 * Settings. Fields are rendered dynamically from `GET /settings` by `type`;
 * each row carries its apply-policy badge. Secret settings are never edited
 * here — they route to the Secrets view, matching the write-only API.
 *
 * Saving is per setting (`PUT /settings/{key}`). Booleans and enums save on
 * change; text/number fields show an explicit save button and also submit on
 * Enter. A `409` is surfaced as a conflict and the list is reloaded, because a
 * concurrent writer means our cached value is stale.
 */

import { api, ApiError, errorMessage } from "../api.js";
import { t } from "../i18n.js";
import {
  badge, button, card, debounce, el, icon, policyBadgeEl, setBusy, toastError, toastSuccess,
} from "../ui.js";

/** Preferred category order mirrors `SettingsCategory.entries`. */
const CATEGORY_ORDER = [
  "brain", "speech", "listening", "weather_maps", "memory",
  "proactivity", "music", "accounts", "mcp", "management",
];

const state = { query: "" };

export function renderSettings(ctx) {
  const settings = ctx.store.settings;
  const root = el("div", { class: "stack view" });

  if (!Array.isArray(settings)) {
    root.appendChild(el("div", { class: "empty" }, el("p", { class: "muted", text: t("common.loading") })));
    return root;
  }
  if (!settings.length) {
    root.appendChild(el("div", { class: "empty" },
      icon("sliders", { size: 36 }),
      el("p", { class: "empty__title", text: t("settings.empty") })
    ));
    return root;
  }

  const groups = groupByCategory(settings);
  const activeCategory = ctx.store.route.category && groups.has(ctx.store.route.category)
    ? ctx.store.route.category
    : firstCategory(groups);

  // --- Toolbar -----------------------------------------------------------
  const search = el("input", {
    class: "input",
    type: "search",
    placeholder: t("settings.searchPlaceholder"),
    value: state.query,
    "aria-label": t("settings.searchPlaceholder"),
  });
  search.addEventListener("input", debounce(() => {
    state.query = search.value.trim();
    renderPanel();
  }, 140));

  const toolbar = el("div", { class: "filter-bar" },
    search,
    el("span", { class: "small faint", text: t("settings.count", { n: settings.length }) }),
    el("span", { class: "spacer" }),
    button(t("common.refresh"), { size: "btn--sm", iconName: "refresh", onClick: () => ctx.reloadSettings() })
  );
  root.appendChild(toolbar);

  // --- Layout: category rail + panel ------------------------------------
  const rail = el("nav", { class: "cat-rail", "aria-label": t("settings.title") });
  const panel = el("div", { class: "stack", id: "settings-panel" });
  root.appendChild(el("div", { class: "settings-layout" }, rail, panel));

  rebuildRail();
  renderPanel();

  function rebuildRail() {
    rail.replaceChildren();
    for (const [category, items] of groups) {
      const btn = el("button", {
        type: "button",
        class: `cat-rail__item${category === activeCategory ? " is-active" : ""}`,
        onClick: () => {
          if (state.query) { // searching spans categories; a click clears it
            state.query = "";
            search.value = "";
          }
          ctx.navigate({ name: "settings", category });
        },
      });
      btn.appendChild(el("span", { text: categoryLabel(category) }));
      btn.appendChild(el("span", { class: "cat-rail__count", text: String(items.length) }));
      rail.appendChild(btn);
    }
  }

  function renderPanel() {
    panel.replaceChildren();
    if (state.query) {
      const matches = filterItems(settings, state.query);
      if (!matches.length) {
        panel.appendChild(el("div", { class: "empty" },
          icon("sliders", { size: 32 }),
          el("p", { class: "empty__title", text: t("settings.noResults") })
        ));
        return;
      }
      panel.appendChild(settingsCard(null, matches, ctx, () => {
        // A save can change category membership; just re-render the list.
        ctx.reloadSettings();
      }));
      return;
    }
    const items = groups.get(activeCategory) || [];
    panel.appendChild(settingsCard(categoryLabel(activeCategory), items, ctx, () => ctx.reloadSettings()));
  }

  return root;
}

function settingsCard(title, items, ctx, afterSave) {
  return card({
    title,
    subtitle: title ? null : t("settings.searchPlaceholder"),
    flush: true,
    body: items.map((setting) => settingRow(setting, ctx, afterSave)),
  });
}

/* ------------------------------------------------------------- One row --- */

function settingRow(setting, ctx, afterSave) {
  const type = typeKey(setting.type);
  const row = el("div", { class: "setting-row", dataset: { key: setting.key } });

  const meta = el("div", { class: "setting-row__meta" },
    policyBadgeEl(setting.policy),
    setting.essential ? badge(t("settings.essential"), "badge--warn") : null,
    el("span", { class: "tag", text: type })
  );

  row.appendChild(el("div", { class: "setting-row__head" },
    el("div", { class: "setting-row__id" },
      el("div", { class: "setting-row__key", text: setting.key }),
      meta
    )
  ));

  if (setting.secret) {
    row.appendChild(el("div", { class: "setting-row__control" },
      el("span", { class: "secret-note" }, icon("lock", { size: 15 }), t("settings.secretRoute")),
      button(t("settings.openSecrets"), { size: "btn--sm", iconName: "key", onClick: () => ctx.navigate({ name: "secrets" }) })
    ));
    return row;
  }

  if (type === "json_blob" || type === "json") {
    row.appendChild(el("div", { class: "setting-row__control" },
      el("pre", { class: "json-view", text: prettyJson(setting.value) })
    ));
    row.appendChild(el("p", { class: "field__hint", text: t("settings.jsonNote") }));
    return row;
  }

  const { control, readValue } = buildControl(setting, type);
  const saveBtn = button(t("common.save"), {
    variant: "btn--primary",
    size: "btn--sm",
    cls: "setting-row__save",
    onClick: () => doSave(),
  });
  const statusEl = el("span", { class: "setting-row__saved" });
  statusEl.hidden = true;

  const controlWrap = el("div", { class: "setting-row__control" }, control, saveBtn, statusEl);
  row.appendChild(controlWrap);

  const original = serialize(setting.value, type);

  const markDirty = () => {
    const dirty = serialize(readValue(), type) !== original;
    row.classList.toggle("is-dirty", dirty);
    saveBtn.disabled = !dirty;
    if (dirty) { statusEl.hidden = true; }
  };

  // Wire change/input listeners depending on the control.
  if (type === "boolean") {
    control.addEventListener("change", () => doSave());
  } else if (control.tagName === "SELECT") {
    control.addEventListener("change", () => doSave());
  } else {
    control.addEventListener("input", markDirty);
    control.addEventListener("keydown", (e) => {
      if (e.key === "Enter") { e.preventDefault(); if (row.classList.contains("is-dirty")) doSave(); }
    });
  }

  const inlineError = el("p", { class: "field__error" });
  inlineError.hidden = true;
  row.appendChild(inlineError);

  async function doSave() {
    const parsed = parse(readValue(), type);
    if (parsed === INVALID) {
      inlineError.textContent = type === "boolean" ? t("settings.invalidBoolean")
        : (type === "int" || type === "long") ? t("settings.invalidInteger") : t("settings.invalidNumber");
      inlineError.hidden = false;
      return;
    }
    inlineError.hidden = true;
    setBusy(saveBtn, true);
    try {
      await api.putSetting(setting.key, parsed);
      setting.value = parsed;
      row.classList.remove("is-dirty");
      saveBtn.disabled = true;
      statusEl.hidden = false;
      statusEl.replaceChildren(icon("check", { size: 14 }), t("settings.savedOk"));
      toastSuccess(t("settings.savedOk"));
      // Keep the cached list consistent and refresh policy status.
      ctx.onSettingSaved?.(setting.key, parsed);
    } catch (err) {
      setBusy(saveBtn, false);
      if (err instanceof ApiError && err.status === 409) {
        toastError(t("settings.conflict"));
        ctx.reloadSettings();
      } else if (err instanceof ApiError && err.status === 400) {
        inlineError.textContent = errorMessage(err, t("common.saveFailed"));
        inlineError.hidden = false;
      } else {
        toastError(errorMessage(err, t("common.saveFailed")));
      }
    } finally {
      setBusy(saveBtn, false);
    }
  }

  markDirty();
  return row;
}

/* ---------------------------------------------------------- Controls --- */

function buildControl(setting, type) {
  const value = setting.value;
  if (type === "boolean") {
    const input = el("input", { type: "checkbox", checked: !!value });
    const label = el("label", { class: "switch" }, input,
      el("span", { class: "switch__track" }),
      el("span", { class: "small muted", text: value ? t("common.on") : t("common.off") })
    );
    input.addEventListener("change", () => {
      label.lastChild.textContent = input.checked ? t("common.on") : t("common.off");
    });
    return { control: label, readValue: () => input.checked };
  }

  if (type === "enum") {
    const options = enumOptions(setting);
    if (options) {
      const select = el("select", { class: "select", value: value == null ? "" : String(value) });
      for (const opt of options) {
        select.appendChild(el("option", { value: opt.value, text: opt.label, selected: String(value) === String(opt.value) }));
      }
      return { control: select, readValue: () => select.value };
    }
    const input = el("input", { class: "input input--mono", type: "text", value: value == null ? "" : String(value) });
    const wrap = el("div", { class: "stack" }, input,
      el("p", { class: "field__hint", text: t("settings.enumNoOptions") }));
    return { control: wrap, readValue: () => input.value };
  }

  if (type === "int" || type === "long") {
    const input = el("input", {
      class: "input input--mono", type: "number", step: "1",
      value: value == null ? "" : String(value),
    });
    return { control: input, readValue: () => input.value };
  }

  if (type === "float") {
    const input = el("input", {
      class: "input input--mono", type: "number", step: "any",
      value: value == null ? "" : String(value),
    });
    return { control: input, readValue: () => input.value };
  }

  // string (default)
  const input = el("input", { class: "input", type: "text", value: value == null ? "" : String(value) });
  return { control: input, readValue: () => input.value };
}

const INVALID = Symbol("invalid");

function parse(raw, type) {
  if (type === "boolean") return typeof raw === "boolean" ? raw : raw === "true";
  if (type === "int" || type === "long") {
    const value = Number(raw);
    return Number.isFinite(value) && Number.isInteger(value) ? value : INVALID;
  }
  if (type === "float") {
    const value = Number(raw);
    return Number.isFinite(value) ? value : INVALID;
  }
  return String(raw);
}

function serialize(value, type) {
  if (type === "boolean") return value ? "true" : "false";
  if (value == null) return "";
  return String(value);
}

function enumOptions(setting) {
  const raw = setting.options ?? setting.values ?? setting.choices ?? setting.enumValues ?? setting.enum;
  if (!Array.isArray(raw)) return null;
  return raw.map((entry) => {
    if (entry && typeof entry === "object") {
      const value = entry.value ?? entry.id ?? entry.key;
      return { value: String(value), label: String(entry.label ?? entry.title ?? value) };
    }
    return { value: String(entry), label: String(entry) };
  });
}

/* ------------------------------------------------------------- Helpers --- */

export function typeKey(type) {
  const v = String(type ?? "").toLowerCase().replace(/[\s-]/g, "_");
  if (v === "bool" || v === "boolean") return "boolean";
  if (v === "int" || v === "integer") return "int";
  if (v === "long") return "long";
  if (v === "float" || v === "double" || v === "number") return "float";
  if (v === "enum") return "enum";
  if (v === "json" || v === "json_blob" || v === "blob") return "json_blob";
  return "string";
}

export function categoryLabel(id) {
  return t(`categories.${id}`) === `categories.${id}` ? String(id || t("settings.categoryUnknown")) : t(`categories.${id}`);
}

function groupByCategory(settings) {
  const map = new Map();
  for (const setting of settings) {
    const category = setting.category || "other";
    if (!map.has(category)) map.set(category, []);
    map.get(category).push(setting);
  }
  // Order known categories first, then unknown alphabetically.
  const ordered = new Map();
  for (const category of CATEGORY_ORDER) if (map.has(category)) ordered.set(category, map.get(category));
  for (const category of [...map.keys()].sort()) if (!ordered.has(category)) ordered.set(category, map.get(category));
  return ordered;
}

function firstCategory(groups) {
  return groups.keys().next().value;
}

function filterItems(settings, query) {
  const q = query.toLowerCase();
  return settings.filter((s) => {
    const key = String(s.key || "").toLowerCase();
    const category = categoryLabel(s.category).toLowerCase();
    return key.includes(q) || category.includes(q);
  });
}

function prettyJson(value) {
  if (value == null) return "—";
  if (typeof value === "string") return value;
  try { return JSON.stringify(value, null, 2); } catch { return String(value); }
}
