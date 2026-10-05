/**
 * Status / home. Everything here comes from `GET /status`; the shell owns the
 * polling cadence and re-renders this view when the payload changes.
 */

import { t } from "../i18n.js";
import {
  brandMark, button, card, copyButton, el, formatFingerprint,
  formatIdleTimeout, formatTime, icon, statusPill,
} from "../ui.js";

export function renderStatus(ctx) {
  const s = ctx.store.status || {};
  const mgmt = s.management || {};
  const tls = s.tls || {};
  const mode = normalizeMode(mgmt.mode);
  const active = !!mgmt.active;

  const root = el("div", { class: "stack view" });

  // --- Hero --------------------------------------------------------------
  // `service` is a fixed identifier ("jarvis"), NOT a run-state, so the hero
  // describes the management listener's mode instead of inventing a status.
  root.appendChild(el("section", { class: "hero" },
    el("div", { class: "hero__mark" }, brandMark({ size: 64, pulse: active })),
    el("div", {},
      el("h2", { class: "hero__title", text: modeLabel(mode) }),
      el("p", { class: "hero__sub", text: modeHint(mode) })
    ),
    el("div", { class: "hero__status" }, managementPill(mode, active))
  ));

  // --- LAN warning -------------------------------------------------------
  if (mode === "lan") {
    root.appendChild(el("div", { class: `banner banner--${active ? "danger" : "warn"}` },
      icon("alert", { size: 20 }),
      el("div", { class: "banner__body" },
        el("p", { class: "banner__title", text: t("status.lanWarningTitle") }),
        el("p", { class: "banner__text", text: t("status.lanWarningText") })
      ),
      button(t("status.openManagement"), {
        variant: "btn--sm",
        onClick: () => ctx.navigate({ name: "management" }),
      })
    ));
  }

  // --- Metrics -----------------------------------------------------------
  root.appendChild(el("div", { class: "grid grid--4" },
    metric(t("status.mode"), modeLabel(mode), modeHint(mode)),
    metric(t("status.active"), active ? t("status.active") : t("status.inactive"),
      active ? t("management.activeHint") : inactiveHint(mode), active ? "status-pill--active" : ""),
    metric(t("status.port"), mgmt.port ?? t("common.notAvailable"), null, "mono"),
    metric(t("status.idleTimeout"), formatIdleTimeout(mgmt.idleTimeoutMs), null, "mono")
  ));

  // --- TLS fingerprint ---------------------------------------------------
  const fingerprint = tls.sha256Fingerprint || tls.fingerprint || tls.sha256 || "";
  const fpCard = card({
    title: t("status.tlsFingerprint"),
    subtitle: t("status.tlsHint"),
    body: fingerprint
      ? el("div", { class: "fingerprint" },
          el("code", { text: formatFingerprint(fingerprint) }),
          copyButton(() => fingerprint, { title: t("common.copy") })
        )
      : el("p", { class: "muted", text: t("status.tlsUnavailable") }),
  });
  root.appendChild(fpCard);

  // --- Versions + quick actions -----------------------------------------
  const versions = card({
    title: t("common.version"),
    body: el("dl", { class: "kv" },
      el("dt", { text: t("status.service") }),
      el("dd", { class: "mono", text: serviceName(s.service) }),
      el("dt", { text: t("status.appVersion") }),
      el("dd", { class: "mono", text: s.appVersion || t("common.notAvailable") }),
      el("dt", { text: t("status.apiVersion") }),
      el("dd", { class: "mono", text: s.apiVersion != null ? String(s.apiVersion) : t("common.notAvailable") }),
      el("dt", { text: t("status.polled") }),
      el("dd", { class: "mono", text: formatTime(ctx.store.lastPolledAt) })
    ),
  });

  const quick = card({
    title: t("status.quickActions"),
    body: el("div", { class: "row" },
      button(t("status.openManagement"), { variant: "btn--primary", iconName: "shield", onClick: () => ctx.navigate({ name: "management" }) }),
      button(t("status.openSecrets"), { iconName: "key", onClick: () => ctx.navigate({ name: "secrets" }) }),
      button(t("status.openExport"), { iconName: "download", onClick: () => ctx.navigate({ name: "export" }) })
    ),
  });

  root.appendChild(el("div", { class: "grid grid--2" }, versions, quick));
  return root;
}

function metric(label, value, hint, extraClass = "") {
  return el("div", { class: "metric" },
    el("span", { class: "metric__label", text: label }),
    el("span", { class: `metric__value${extraClass === "mono" ? " mono" : ""}`, text: value }),
    hint ? el("span", { class: "metric__hint", text: hint }) : null
  );
}

/** The service identifier ("jarvis" → "Jarvis"); never a run-state. */
export function serviceName(service) {
  if (service == null) return t("common.appName");
  if (typeof service === "object") {
    return String(service.name || service.id || t("common.appName"));
  }
  const raw = String(service).trim();
  if (!raw) return t("common.appName");
  return raw.charAt(0).toUpperCase() + raw.slice(1);
}

export function normalizeMode(mode) {
  const v = String(mode ?? "").toLowerCase();
  if (v === "lan") return "lan";
  if (v === "localhost" || v === "local") return "localhost";
  return "disabled";
}

function modeLabel(mode) {
  if (mode === "lan") return t("status.modeLan");
  if (mode === "localhost") return t("status.modeLocalhost");
  return t("status.modeDisabled");
}

function modeHint(mode) {
  if (mode === "lan") return t("status.modeHintLan");
  if (mode === "localhost") return t("status.modeHintLocalhost");
  return t("status.modeHintDisabled");
}

function inactiveHint(mode) {
  if (mode === "disabled") return t("management.inactiveHintDisabled");
  return t("management.inactiveHintLan");
}

export function managementPill(mode, active) {
  const cls = active ? (mode === "lan" ? "status-pill--lan" : "status-pill--active") : "status-pill--stopped";
  const label = active ? t("status.active") : t("status.inactive");
  return statusPill(label, { cls, mode });
}
