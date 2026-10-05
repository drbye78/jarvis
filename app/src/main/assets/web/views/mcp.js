/**
 * MCP servers. CRUD mirrors the on-device dialog: displayName, kind
 * (REMOTE/LOCAL/LAN), url, enabled, access (READ/WRITE), authHeaderName and a
 * write-only per-server secret. The URL policy differs by kind, so the form
 * surfaces the rule inline and switches its placeholder accordingly.
 */

import { api, errorMessage } from "../api.js";
import { t } from "../i18n.js";
import {
  badge, button, card, confirmModal, el, icon, iconButton, openModal, setBusy,
  toastError, toastSuccess,
} from "../ui.js";

const KINDS = [
  { value: "REMOTE", labelKey: "mcp.kindRemote", hintKey: "mcp.kindHintRemote", placeholder: "https://example.com/mcp" },
  { value: "LOCAL", labelKey: "mcp.kindLocal", hintKey: "mcp.kindHintLocal", placeholder: "http://127.0.0.1:8080/mcp" },
  { value: "LAN", labelKey: "mcp.kindLan", hintKey: "mcp.kindHintLan", placeholder: "https://192.168.1.10/mcp" },
];

export function renderMcp(ctx) {
  const servers = ctx.store.mcp;
  const root = el("div", { class: "stack view" });

  if (!Array.isArray(servers)) {
    root.appendChild(el("p", { class: "muted", text: t("common.loading") }));
    return root;
  }

  root.appendChild(el("div", { class: "row" },
    el("span", { class: "small faint", text: `${servers.length}` }),
    el("span", { class: "spacer" }),
    button(t("mcp.addServer"), { variant: "btn--primary", iconName: "plus", onClick: () => openServerDialog(null, ctx) })
  ));

  if (!servers.length) {
    root.appendChild(el("div", { class: "empty" },
      icon("plug", { size: 36 }),
      el("p", { class: "empty__title", text: t("mcp.empty") }),
      el("p", { class: "muted small", text: t("mcp.emptyHint") }),
      button(t("mcp.addServer"), { variant: "btn--primary", iconName: "plus", onClick: () => openServerDialog(null, ctx) })
    ));
    return root;
  }

  const list = el("div", {});
  for (const server of servers) list.appendChild(serverRow(server, ctx));
  root.appendChild(card({ flush: true, body: list }));
  return root;
}

/* ---------------------------------------------------------------- Row --- */

function serverRow(server, ctx) {
  const item = el("div", { class: "mcp-item" });
  const left = el("div", {});

  left.appendChild(el("div", { class: "mcp-item__name" },
    el("span", { text: server.displayName || t("mcp.displayName") }),
    kindBadge(server.kind),
    accessBadge(server.access),
    server.enabled ? null : badge(t("common.disabled"), "badge--muted")
  ));
  left.appendChild(el("div", { class: "mcp-item__url", text: server.url || "" }));

  const secretState = secretSet(ctx, server.id);
  left.appendChild(el("div", { class: "mcp-item__meta" },
    el("span", { class: "tag", text: server.authHeaderName ? `header: ${server.authHeaderName}` : t("mcp.authHeader") + ": —" }),
    el("span", { class: "tag" },
      icon("key", { size: 12 }),
      secretState == null ? t("mcp.secret") + ": —" : (secretState ? t("mcp.secretSet") : t("mcp.secretUnset"))
    )
  ));

  const actions = el("div", { class: "mcp-item__actions" });
  if (server.access === "WRITE") {
    actions.appendChild(iconButton("alert", { title: t("mcp.accessWriteWarning"), cls: "muted" }));
  }
  actions.appendChild(button(t("common.edit"), { size: "btn--sm", iconName: "edit", onClick: () => openServerDialog(server, ctx) }));
  actions.appendChild(button(t("common.delete"), { size: "btn--sm", variant: "btn--danger", iconName: "trash", onClick: () => deleteServer(server, ctx) }));

  item.append(left, actions);
  return item;
}

/* -------------------------------------------------------------- Dialog --- */

function openServerDialog(server, ctx) {
  const editing = !!server;
  const is = server || { displayName: "", kind: "REMOTE", url: "", enabled: true, access: "READ", authHeaderName: "" };

  const nameInput = el("input", { class: "input", type: "text", value: is.displayName, placeholder: t("mcp.displayNamePlaceholder") });

  const kindSelect = el("select", { class: "select" },
    KINDS.map((k) => el("option", { value: k.value, text: t(k.labelKey), selected: is.kind === k.value }))
  );
  const kindHint = el("p", { class: "field__hint" });
  const urlInput = el("input", { class: "input input--mono", type: "text" });

  const enabledSwitch = el("input", { type: "checkbox", checked: is.enabled !== false });
  const enabledLabel = el("label", { class: "switch" }, enabledSwitch,
    el("span", { class: "switch__track" }),
    el("span", { class: "small muted", text: is.enabled !== false ? t("common.enabled") : t("common.disabled") })
  );
  enabledSwitch.addEventListener("change", () => {
    enabledLabel.lastChild.textContent = enabledSwitch.checked ? t("common.enabled") : t("common.disabled");
  });

  const accessSelect = el("select", { class: "select" },
    el("option", { value: "READ", text: t("mcp.accessRead"), selected: is.access !== "WRITE" }),
    el("option", { value: "WRITE", text: t("mcp.accessWrite"), selected: is.access === "WRITE" })
  );
  const writeWarning = el("div", { class: "banner banner--warn" },
    icon("alert", { size: 18 }),
    el("div", { class: "banner__body" }, el("p", { class: "banner__text", text: t("mcp.accessWriteWarning") }))
  );
  writeWarning.hidden = is.access !== "WRITE";
  accessSelect.addEventListener("change", () => { writeWarning.hidden = accessSelect.value !== "WRITE"; });

  const headerInput = el("input", { class: "input input--mono", type: "text", value: is.authHeaderName || "", placeholder: t("mcp.authHeaderPlaceholder") });

  const secretInput = el("input", { class: "input input--mono", type: "password", autocomplete: "new-password" });
  const secretHint = editing
    ? (secretSet(ctx, server.id) ? t("mcp.secretSet") : t("mcp.secretUnset")) + " · " + t("mcp.secretKeep")
    : t("mcp.secretKeep");

  function syncKind() {
    const meta = KINDS.find((k) => k.value === kindSelect.value) || KINDS[0];
    kindHint.textContent = t(meta.hintKey);
    urlInput.placeholder = meta.placeholder;
    urlInput.value = urlInput.value || (editing && is.url ? is.url : "");
  }
  kindSelect.addEventListener("change", syncKind);
  if (editing) urlInput.value = is.url || "";
  syncKind();

  const error = el("p", { class: "field__error", role: "alert" });
  error.hidden = true;

  const body = [
    el("div", { class: "grid grid--2" },
      fieldWrap(t("mcp.displayName"), nameInput),
      fieldWrap(t("mcp.kind"), el("div", {}, kindSelect, kindHint))
    ),
    fieldWrap(t("mcp.url"), urlInput),
    el("div", { class: "grid grid--2" },
      fieldWrap(t("mcp.access"), el("div", {}, accessSelect)),
      fieldWrap(t("common.enabled"), enabledLabel)
    ),
    writeWarning,
    fieldWrap(t("mcp.authHeader"), headerInput),
    fieldWrap(t("mcp.secret"), el("div", {}, secretInput, el("p", { class: "field__hint", text: secretHint }))),
    error,
  ];

  let modal;
  const saveBtn = button(editing ? t("common.save") : t("common.add"), { variant: "btn--primary", onClick: () => submit() });
  const cancel = button(t("common.cancel"), { onClick: () => modal.close() });
  modal = openModal({
    title: editing ? t("mcp.editTitle") : t("mcp.createTitle"),
    subtitle: editing ? server.id : null,
    body,
    actions: [cancel, saveBtn],
  });

  async function submit() {
    const displayName = nameInput.value.trim();
    const url = urlInput.value.trim();
    if (!displayName) { fail(t("mcp.nameRequired")); nameInput.focus(); return; }
    if (!url) { fail(t("mcp.urlRequired")); urlInput.focus(); return; }
    error.hidden = true;

    const payload = {
      displayName,
      kind: kindSelect.value,
      url,
      enabled: enabledSwitch.checked,
      access: accessSelect.value,
      authHeaderName: headerInput.value.trim(),
    };

    setBusy(saveBtn, true);
    try {
      let serverId = editing ? server.id : null;
      if (editing) {
        await api.updateMcpServer(server.id, payload);
      } else {
        const created = await api.createMcpServer(payload);
        serverId = created && created.id ? created.id : null;
      }
      const secretValue = secretInput.value;
      if (secretValue && serverId) {
        await api.putMcpSecret(serverId, secretValue);
      } else if (secretValue && !serverId && !editing) {
        // The create response did not carry an id; reload and warn honestly.
        await ctx.reloadMcp();
        const match = (ctx.store.mcp || []).find((s) => s.url === url && s.displayName === displayName);
        if (match) await api.putMcpSecret(match.id, secretValue);
        else toastError(t("common.saveFailed"));
      }
      toastSuccess(editing ? t("mcp.updated") : t("mcp.created"));
      modal.close();
      ctx.reloadMcp();
      ctx.reloadSecrets?.();
    } catch (err) {
      error.textContent = errorMessage(err, t("common.saveFailed"));
      error.hidden = false;
      setBusy(saveBtn, false);
    }
  }

  function fail(message) {
    error.textContent = message;
    error.hidden = false;
  }
}

async function deleteServer(server, ctx) {
  const ok = await confirmModal({
    title: t("mcp.deleteTitle"),
    text: t("mcp.deleteText", { name: server.displayName || server.id }),
    confirmLabel: t("common.delete"),
    danger: true,
  });
  if (!ok) return;
  try {
    await api.deleteMcpServer(server.id);
    toastSuccess(t("mcp.deleted"));
    ctx.reloadMcp();
    ctx.reloadSecrets?.();
  } catch (err) {
    toastError(errorMessage(err, t("common.saveFailed")));
  }
}

/* ------------------------------------------------------------- Helpers --- */

function fieldWrap(label, control) {
  return el("div", { class: "field" },
    el("label", { class: "field__label", text: label }),
    control
  );
}

function kindBadge(kind) {
  const k = String(kind || "").toUpperCase();
  if (k === "LOCAL") return badge(t("mcp.kindLocal"), "badge--ok");
  if (k === "LAN") return badge(t("mcp.kindLan"), "badge--warn");
  return badge(t("mcp.kindRemote"), "badge--read");
}

function accessBadge(access) {
  return String(access || "").toUpperCase() === "WRITE"
    ? badge(t("mcp.accessWrite"), "badge--write")
    : badge(t("mcp.accessRead"), "badge--read");
}

/** Secret set-state: the server now reports `secretSet`; fall back to /secrets. */
export function secretSet(ctx, serverId) {
  if (Array.isArray(ctx.store.mcp)) {
    const server = ctx.store.mcp.find((s) => s.id === serverId);
    if (server && typeof server.secretSet === "boolean") return server.secretSet;
  }
  if (!Array.isArray(ctx.store.secrets)) return null;
  const entry = ctx.store.secrets.find((s) => s.key === `mcp_secret_${serverId}`);
  return entry ? !!entry.set : false;
}
