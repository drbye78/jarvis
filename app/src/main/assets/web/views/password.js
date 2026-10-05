/**
 * Password change. On success the server revokes every session, so the shell
 * is asked to drop to the login view with a success notice.
 *
 * The three fields live inside a real `<form>` so password managers recognise
 * the change-password flow; submission is intercepted (no navigation, and the
 * CSP's `form-action 'self'` is never exercised).
 */

import { api, ApiError, errorMessage } from "../api.js";
import { t } from "../i18n.js";
import { button, card, el, setBusy, toastError } from "../ui.js";

export function renderPassword(ctx) {
  const current = el("input", { class: "input", type: "password", name: "current-password", autocomplete: "current-password" });
  const next = el("input", { class: "input", type: "password", name: "new-password", autocomplete: "new-password" });
  const confirm = el("input", { class: "input", type: "password", name: "confirm-password", autocomplete: "new-password" });

  const error = el("p", { class: "field__error", role: "alert" });
  error.hidden = true;

  const submit = button(t("passwordView.submit"), { variant: "btn--primary", type: "submit" });

  const form = el("form", { class: "stack", novalidate: true },
    el("input", {
      type: "text", name: "username", autocomplete: "username", value: "jarvis",
      class: "sr-only", tabindex: "-1", readonly: true, "aria-hidden": "true",
    }),
    el("div", { class: "field", style: { maxWidth: "420px" } },
      el("label", { class: "field__label", for: "pw-current", text: t("passwordView.current") }),
      current
    ),
    el("div", { class: "grid grid--2" },
      el("div", { class: "field" },
        el("label", { class: "field__label", for: "pw-next", text: t("passwordView.next") }),
        next
      ),
      el("div", { class: "field" },
        el("label", { class: "field__label", for: "pw-confirm", text: t("passwordView.confirm") }),
        confirm
      )
    ),
    error,
    el("p", { class: "field__hint", text: t("passwordView.hint") }),
    el("div", { class: "row row--end" }, submit)
  );
  current.id = "pw-current";
  next.id = "pw-next";
  confirm.id = "pw-confirm";

  form.addEventListener("submit", (e) => { e.preventDefault(); doSubmit(); });

  const root = el("div", { class: "stack view" },
    card({ title: t("passwordView.subtitle"), body: form })
  );

  return root;

  async function doSubmit() {
    error.hidden = true;
    if (!next.value || next.value.length < 8) return fail(t("passwordView.tooShort"));
    if (next.value !== confirm.value) return fail(t("passwordView.mismatch"));
    if (next.value === current.value) return fail(t("passwordView.sameAsCurrent"));

    setBusy(submit, true);
    try {
      await api.changePassword(current.value, next.value);
      current.value = next.value = confirm.value = "";
      ctx.onPasswordChanged();
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) return fail(t("passwordView.wrongCurrent"));
      if (err instanceof ApiError && err.status === 400) return fail(errorMessage(err, t("passwordView.failed")));
      toastError(errorMessage(err, t("passwordView.failed")));
    } finally {
      setBusy(submit, false);
    }
  }

  function fail(message) {
    error.textContent = message;
    error.hidden = false;
  }
}
