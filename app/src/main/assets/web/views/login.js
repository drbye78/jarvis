/**
 * Login — the only unauthenticated screen. A single password field exchanges
 * the password for a same-origin session cookie; the password itself is never
 * stored in JS beyond the lifetime of the submit handler.
 */

import { api, ApiError, errorMessage } from "../api.js";
import { t } from "../i18n.js";
import { brandMark, button, el, iconButton, setBusy, toast } from "../ui.js";

export function renderLogin(ctx) {
  const password = el("input", {
    class: "input",
    type: "password",
    name: "password",
    autocomplete: "current-password",
    placeholder: t("login.passwordPlaceholder"),
    required: true,
    autofocus: true,
  });
  const toggle = iconButton("eye", { title: t("login.showPassword") });
  let revealed = false;
  toggle.addEventListener("click", () => {
    revealed = !revealed;
    password.type = revealed ? "text" : "password";
    toggle.replaceChildren();
    toggle.appendChild(iconOnly(revealed ? "eyeOff" : "eye"));
    toggle.title = revealed ? t("login.hidePassword") : t("login.showPassword");
    toggle.setAttribute("aria-label", toggle.title);
  });

  const errorBox = el("p", { class: "field__error login__error", role: "alert" });
  errorBox.hidden = true;

  const submit = button(t("login.submit"), { variant: "btn--primary btn--lg btn--block", type: "submit" });

  const form = el("form", { class: "login__form", novalidate: true },
    // A hidden username pair so password managers recognise the credential
    // form (and the browser stops asking for one). The value is the fixed app
    // identity — this is a single-password appliance login.
    el("input", {
      type: "text",
      name: "username",
      autocomplete: "username",
      value: "jarvis",
      class: "sr-only",
      tabindex: "-1",
      readonly: true,
      "aria-hidden": "true",
    }),
    el("div", { class: "field" },
      el("label", { class: "field__label", for: "login-password", text: t("login.passwordLabel") }),
      el("div", { class: "input-group" }, password, toggle)
    ),
    errorBox,
    submit
  );
  password.id = "login-password";

  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const value = password.value;
    if (!value) {
      showError(t("login.failed"));
      password.focus();
      return;
    }
    errorBox.hidden = true;
    setBusy(submit, true);
    try {
      await api.login(value);
      password.value = "";
      toast(t("login.submit"), "success", { timeout: 1200 });
      ctx.onAuthenticated();
    } catch (err) {
      if (err instanceof ApiError && err.isLockout) {
        showError(t("login.locked"));
      } else if (err instanceof ApiError && err.status === 0) {
        showError(t("login.offlineHint"));
      } else if (err instanceof ApiError && err.status === 401) {
        showError(t("login.failed"));
      } else {
        showError(errorMessage(err, t("login.failed")));
      }
      password.select();
    } finally {
      setBusy(submit, false);
    }
  });

  function showError(message) {
    errorBox.textContent = message;
    errorBox.hidden = false;
  }

  const footer = el("div", { class: "login__footer" },
    el("span", { class: "tiny faint", text: `Jarvis · ${t("common.kicker")}` }),
    el("div", { class: "row" },
      ctx.localeToggle(),
      ctx.themeToggle()
    )
  );

  return el("div", { class: "login" },
    el("div", { class: "login__card" },
      el("div", { class: "login__mark" }, brandMark({ size: 72, pulse: true })),
      el("p", { class: "login__kicker", text: t("common.kicker") }),
      el("h1", { class: "login__title", text: t("login.title") }),
      el("p", { class: "login__sub", text: t("login.subtitle") }),
      form,
      footer,
      el("p", { class: "login__hint", text: t("login.certHint") })
    )
  );
}

function iconOnly(name) {
  // Local tiny shim so the toggle can swap its glyph without re-importing.
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "1.8");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("width", "17");
  svg.setAttribute("height", "17");
  svg.innerHTML = name === "eyeOff"
    ? '<path d="M3 3l18 18M10.6 5.1A9.7 9.7 0 0 1 12 5c6.5 0 10 6 10 6a17 17 0 0 1-3 3.6M6.2 6.6C3.8 8.2 2 11 2 11s3.5 6 10 6a9.8 9.8 0 0 0 3.4-.6M9.9 9.9a3 3 0 0 0 4.2 4.2"/>'
    : '<path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6-10-6-10-6Z"/><circle cx="12" cy="12" r="2.6"/>';
  return svg;
}
