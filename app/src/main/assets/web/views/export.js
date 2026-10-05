/**
 * Export / import. The export is always an encrypted envelope and requires a
 * passphrase; "include secrets" is an explicit opt-in with a live count and a
 * warning. Import posts `{passphrase, data}` where `data` is the raw envelope
 * text, and reports `{applied, skipped, errors}`.
 *
 * The download is produced by fetching the octet-stream as a Blob and clicking
 * a temporary object URL — no form action, no navigation, no server-side state.
 *
 * The last import result is kept in module state because a successful import
 * reloads the shell (settings/secrets/mcp + banner), which re-mounts this view;
 * without persistence the result the user just earned would vanish.
 */

import { api, errorMessage } from "../api.js";
import { t } from "../i18n.js";
import {
  badge, button, card, el, icon, setBusy, toastSuccess,
} from "../ui.js";

let lastImportResult = null;

export function renderExport(ctx) {
  const root = el("div", { class: "stack view" });

  // --- Export ------------------------------------------------------------
  const pass = el("input", { class: "input", type: "password", autocomplete: "new-password", placeholder: t("exportView.passphrasePlaceholder") });
  const passConfirm = el("input", { class: "input", type: "password", autocomplete: "new-password" });
  const secretCount = countSecrets(ctx);
  const includeSecrets = el("input", { type: "checkbox", disabled: secretCount === 0 });
  const secretsWarning = el("div", { class: "banner banner--warn" },
    icon("alert", { size: 18 }),
    el("div", { class: "banner__body" }, el("p", { class: "banner__text", text: t("exportView.includeSecretsWarning") }))
  );
  secretsWarning.hidden = true;
  includeSecrets.addEventListener("change", () => { secretsWarning.hidden = !includeSecrets.checked; });

  const exportStatus = el("p", { class: "field__error" });
  exportStatus.hidden = true;

  const downloadBtn = button(t("exportView.download"), { variant: "btn--primary", iconName: "download", type: "submit" });

  const exportForm = el("form", { class: "stack", novalidate: true },
    el("div", { class: "grid grid--2" },
      el("div", { class: "field" },
        el("label", { class: "field__label", for: "export-pass", text: t("exportView.passphrase") }),
        pass
      ),
      el("div", { class: "field" },
        el("label", { class: "field__label", for: "export-pass2", text: t("exportView.passphraseConfirm") }),
        passConfirm
      )
    ),
    el("div", { class: "field" },
      el("label", { class: "check" },
        includeSecrets,
        el("span", { text: secretCount
          ? t("exportView.includeSecrets", { n: secretCount })
          : t("exportView.noSecrets") })
      )
    ),
    secretsWarning,
    exportStatus,
    el("div", { class: "row row--end" }, downloadBtn)
  );
  pass.id = "export-pass";
  passConfirm.id = "export-pass2";
  exportForm.addEventListener("submit", (e) => { e.preventDefault(); doExport(); });

  root.appendChild(card({
    title: t("exportView.exportTitle"),
    subtitle: t("exportView.exportText"),
    body: exportForm,
  }));

  // --- Import ------------------------------------------------------------
  const fileInput = el("input", { class: "sr-only", type: "file", accept: ".jvx,.bin,.jarvis,.json,application/octet-stream,application/json" });
  const fileName = el("span", { class: "small muted mono", text: t("exportView.noFile") });
  const chooseBtn = button(t("exportView.chooseFile"), { iconName: "file", onClick: () => fileInput.click() });
  fileInput.addEventListener("change", () => {
    const file = fileInput.files && fileInput.files[0];
    fileName.textContent = file ? t("exportView.fileSelected", { name: file.name }) : t("exportView.noFile");
  });

  const importPass = el("input", { class: "input", type: "password", autocomplete: "current-password" });
  const importError = el("p", { class: "field__error" });
  importError.hidden = true;
  const importBtn = button(t("exportView.import"), { variant: "btn--primary", iconName: "upload", type: "submit" });
  const resultBox = el("div", {});
  if (lastImportResult) {
    appendResult(resultBox, lastImportResult);
  } else {
    resultBox.hidden = true;
  }

  const importForm = el("form", { class: "stack", novalidate: true },
    el("div", { class: "input-group" }, chooseBtn, fileName),
    fileInput,
    el("div", { class: "field", style: { maxWidth: "420px" } },
      el("label", { class: "field__label", for: "import-pass", text: t("exportView.passphrase") }),
      importPass
    ),
    importError,
    resultBox,
    el("p", { class: "field__hint", text: t("exportView.rollbackNote") }),
    el("div", { class: "row row--end" }, importBtn)
  );
  importPass.id = "import-pass";
  importForm.addEventListener("submit", (e) => { e.preventDefault(); doImport(); });

  root.appendChild(card({
    title: t("exportView.importTitle"),
    subtitle: t("exportView.importText"),
    body: importForm,
  }));

  return root;

  /* ------------------------------------------------------------ Actions --- */

  async function doExport() {
    exportStatus.hidden = true;
    if (pass.value.length < 8) {
      return showExportError(t("exportView.passphraseTooShort"));
    }
    if (pass.value !== passConfirm.value) {
      return showExportError(t("exportView.passphraseMismatch"));
    }
    setBusy(downloadBtn, true);
    try {
      const res = await api.export(pass.value, includeSecrets.checked);
      const blob = await res.blob();
      const suggested = filenameFrom(res.headers.get("content-disposition"));
      triggerDownload(blob, suggested || defaultFilename());
      toastSuccess(t("exportView.exportOk"));
      pass.value = "";
      passConfirm.value = "";
    } catch (err) {
      showExportError(errorMessage(err, t("exportView.exportFailed")));
    } finally {
      setBusy(downloadBtn, false);
    }
  }

  function showExportError(message) {
    exportStatus.textContent = message;
    exportStatus.hidden = false;
  }

  async function doImport() {
    importError.hidden = true;
    const file = fileInput.files && fileInput.files[0];
    if (!file) {
      importError.textContent = t("exportView.fileRequired");
      importError.hidden = false;
      return;
    }
    if (!importPass.value) {
      importError.textContent = t("common.required");
      importError.hidden = false;
      importPass.focus();
      return;
    }
    setBusy(importBtn, true);
    try {
      const data = await readAsText(file);
      const result = await api.import(importPass.value, data);
      lastImportResult = result;
      appendResult(resultBox, result);
      toastSuccess(t("exportView.importOk"));
      importPass.value = "";
      fileInput.value = "";
      fileName.textContent = t("exportView.noFile");
      ctx.reloadSettings();
      ctx.refreshStatus();
      ctx.reloadSecrets?.();
      ctx.reloadMcp?.();
    } catch (err) {
      importError.textContent = errorMessage(err, t("common.saveFailed"));
      importError.hidden = false;
    } finally {
      setBusy(importBtn, false);
    }
  }
}

/** Render the `{applied, skipped, errors}` report into [resultBox]. */
function appendResult(resultBox, result) {
  const r = result || {};
  resultBox.replaceChildren();
  resultBox.hidden = false;
  const applied = normalizeCount(r.applied);
  const skipped = normalizeCount(r.skipped);
  const errors = normalizeErrors(r.errors);

  resultBox.appendChild(el("div", { class: "row" },
    badge(`${t("exportView.resultApplied")}: ${applied}`, "badge--ok"),
    badge(`${t("exportView.resultSkipped")}: ${skipped}`, "badge--muted"),
    errors.length ? badge(`${t("exportView.resultErrors")}: ${errors.length}`, "badge--danger") : null
  ));
  if (errors.length) {
    const list = el("ul", { class: "banner__list" });
    for (const msg of errors.slice(0, 20)) list.appendChild(el("li", { text: String(msg) }));
    resultBox.appendChild(el("div", { class: "banner banner--danger" },
      icon("alert", { size: 18 }),
      el("div", { class: "banner__body" }, el("p", { class: "banner__title", text: t("exportView.resultErrors") }), list)
    ));
  }
}

/* ------------------------------------------------------------- Helpers --- */

function countSecrets(ctx) {
  if (!Array.isArray(ctx.store.secrets)) return 0;
  return ctx.store.secrets.filter((s) => s.set).length;
}

function normalizeCount(value) {
  if (Array.isArray(value)) return value.length;
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

function normalizeErrors(value) {
  if (!value) return [];
  if (Array.isArray(value)) {
    return value.map((e) => (e && typeof e === "object" ? (e.message || e.key || JSON.stringify(e)) : String(e)));
  }
  if (typeof value === "object") return Object.entries(value).map(([k, v]) => `${k}: ${v}`);
  return [String(value)];
}

/**
 * Read a File as text. The import body is `{passphrase, data}` where `data` is
 * the raw encrypted-envelope text produced by `POST /export` (a UTF-8 JSON
 * document). Do not base64 it — the server's `core.import` parses the text
 * directly.
 */
function readAsText(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(reader.error || new Error("read failed"));
    reader.onload = () => resolve(String(reader.result || ""));
    reader.readAsText(file);
  });
}

function triggerDownload(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = el("a", { href: url, download: filename });
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 4000);
}

function defaultFilename() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, "0");
  return `jarvis-config-${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}.jvx`;
}

function filenameFrom(disposition) {
  if (!disposition) return null;
  const match = /filename\*?=(?:UTF-8''|")?([^";]+)/i.exec(disposition);
  return match ? decodeURIComponent(match[1].replace(/"/g, "")) : null;
}
