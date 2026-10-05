/**
 * Management mode. The radio cards set the persisted intent; Apply sends
 * `POST /management/mode {mode, idleTimeoutMs}`. The listener's *active* state
 * is a separate, in-memory fact from `/status`, so the view shows it as a
 * distinct status and offers a one-click re-enable when an intent exists but
 * the socket is closed (idle auto-close, reboot).
 *
 * The idle timeout is in the same POST, so it applies together with the mode.
 * The port is NOT part of this endpoint — it is a service-restart setting owned
 * by the Settings view, and the card links there rather than inventing a write.
 */

import { api, errorMessage } from "../api.js";
import { t } from "../i18n.js";
import {
  badge, button, card, el, icon, setBusy, statusPill, toastError, toastSuccess,
} from "../ui.js";
import { normalizeMode } from "./status.js";

const MODES = [
  { id: "disabled", labelKey: "management.modeDisabled", textKey: "management.modeDisabledText", icon: "x" },
  { id: "localhost", labelKey: "management.modeLocalhost", textKey: "management.modeLocalhostText", icon: "terminal" },
  { id: "lan", labelKey: "management.modeLan", textKey: "management.modeLanText", icon: "wifi" },
];

export function renderManagement(ctx) {
  const s = ctx.store.status || {};
  const mgmt = s.management || {};
  const currentMode = normalizeMode(mgmt.mode);
  const active = !!mgmt.active;
  const currentIdleMinutes = idleToMinutes(mgmt.idleTimeoutMs);

  const root = el("div", { class: "stack view" });

  // --- State summary -----------------------------------------------------
  root.appendChild(el("section", { class: "hero" },
    el("div", { class: "hero__mark" }, icon("shield", { size: 44 })),
    el("div", {},
      el("h2", { class: "hero__title", text: active ? t("management.activeNow") : t("management.inactiveNow") }),
      el("p", { class: "hero__sub", text: active ? t("management.activeHint") : inactiveHint(currentMode) })
    ),
    el("div", { class: "hero__status" },
      statusPill(active ? t("status.active") : t("status.inactive"), {
        cls: active ? (currentMode === "lan" ? "status-pill--lan" : "status-pill--active") : "status-pill--stopped",
      })
    )
  ));

  // --- Mode selector -----------------------------------------------------
  const selected = { id: currentMode };
  const radios = el("div", { class: "radio-cards", role: "radiogroup", "aria-label": t("management.modeLabel") });
  const lanWarning = el("div", { class: "banner banner--danger" },
    icon("alert", { size: 20 }),
    el("div", { class: "banner__body" },
      el("p", { class: "banner__title", text: t("management.lanWarning") })
    )
  );
  lanWarning.hidden = selected.id !== "lan";

  const idleInput = el("input", {
    class: "input input--mono",
    type: "number",
    min: "1",
    max: "1440",
    step: "1",
    value: String(currentIdleMinutes),
  });

  const idleError = el("p", { class: "field__error" });
  idleError.hidden = true;

  for (const mode of MODES) {
    const input = el("input", { type: "radio", name: "mgmt-mode", value: mode.id, checked: selected.id === mode.id });
    input.addEventListener("change", () => {
      selected.id = mode.id;
      lanWarning.hidden = mode.id !== "lan";
      syncApply();
    });
    const cardEl = el("label", { class: "radio-card" },
      input,
      el("span", { class: "radio-card__dot" }),
      el("span", { class: "radio-card__body" },
        el("span", { class: "radio-card__title" },
          icon(mode.icon, { size: 16 }),
          t(mode.labelKey),
          mode.id === currentMode ? badge(t("common.set"), "badge--muted") : null
        ),
        el("span", { class: "radio-card__text", text: t(mode.textKey) })
      )
    );
    radios.appendChild(cardEl);
  }

  const applyBtn = button(t("common.apply"), { variant: "btn--primary", onClick: () => apply() });

  root.appendChild(card({
    title: t("management.modeLabel"),
    body: [
      radios,
      lanWarning,
      el("div", { class: "field", style: { maxWidth: "280px" } },
        el("label", { class: "field__label", text: t("management.idleLabel") }),
        idleInput,
        el("p", { class: "field__hint", text: t("management.idleHint") }),
        idleError
      ),
      el("div", { class: "row row--end" }, applyBtn),
    ],
  }));

  // --- Quick enable / disable -------------------------------------------
  const quickActions = el("div", { class: "row" });
  if (!active && currentMode !== "disabled") {
    quickActions.appendChild(button(t("management.enableNow"), {
      variant: "btn--primary",
      iconName: "shield",
      onClick: (e) => applyMode(currentMode, currentIdleMinutes, t("management.enableOk"), e.currentTarget),
    }));
  }
  if (active) {
    quickActions.appendChild(button(t("management.disableNow"), {
      variant: "btn--danger",
      iconName: "x",
      onClick: (e) => applyMode("disabled", currentIdleMinutes, t("management.disableOk"), e.currentTarget),
    }));
  }
  if (quickActions.childNodes.length) {
    root.appendChild(card({
      title: t("management.stateTitle"),
      body: [
        el("p", { class: "muted small", text: active ? t("management.activeHint") : inactiveHint(currentMode) }),
        quickActions,
      ],
    }));
  }

  // --- Port (read-only; owned by Settings) ------------------------------
  root.appendChild(card({
    title: t("management.portLabel"),
    body: el("div", { class: "row row--between" },
      el("div", {},
        el("span", { class: "mono", text: String(mgmt.port ?? t("common.notAvailable")) }),
        el("p", { class: "field__hint", text: t("management.portHint") })
      ),
      button(t("management.openSettings"), {
        size: "btn--sm",
        iconName: "sliders",
        onClick: () => ctx.navigate({ name: "settings", category: "management" }),
      })
    ),
  }));

  function syncApply() {
    applyBtn.disabled = selected.id === currentMode
      && String(idleInput.value) === String(currentIdleMinutes);
  }
  idleInput.addEventListener("input", () => {
    const valid = validateIdle(idleInput.value);
    idleError.hidden = valid != null;
    if (!valid) idleError.textContent = t("management.idleInvalid");
    syncApply();
  });
  syncApply();

  async function apply() {
    const minutes = validateIdle(idleInput.value);
    if (minutes == null) {
      idleError.textContent = t("management.idleInvalid");
      idleError.hidden = false;
      idleInput.focus();
      return;
    }
    await applyMode(selected.id, minutes, t("management.applyOk"));
  }

  async function applyMode(mode, minutes, successMessage, busyBtn) {
    const target = busyBtn || applyBtn;
    setBusy(target, true);
    try {
      await api.setManagementMode(mode, Math.round(minutes * 60000));
      toastSuccess(successMessage);
      await ctx.refreshStatus();
    } catch (err) {
      toastError(errorMessage(err, t("common.saveFailed")));
    } finally {
      setBusy(target, false);
    }
  }

  return root;
}

/** Parse the idle minutes field; null when out of range or not a number. */
function validateIdle(raw) {
  const value = Number(raw);
  if (!Number.isFinite(value) || !Number.isInteger(value) || value < 1 || value > 1440) return null;
  return value;
}

function idleToMinutes(ms) {
  const n = Number(ms);
  if (!Number.isFinite(n) || n <= 0) return 15;
  return Math.max(1, Math.round(n / 60000));
}

function inactiveHint(mode) {
  if (mode === "disabled") return t("management.inactiveHintDisabled");
  return t("management.inactiveHintLan");
}
