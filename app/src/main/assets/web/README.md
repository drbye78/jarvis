# Jarvis management console (R13 §14.5)

A static, framework-free single-page app that ships inside the Android APK and
is served by the embedded Ktor/Netty management server at **`/`** (its REST API
is at **`/api/v1`**). There is **no build step, no bundler, no npm** — the files
in this directory are the artifact. Everything runs offline from the device.

```
assets/web/
├── index.html          the app shell (one <script type="module">, no inline code)
├── styles.css          the whole visual system (tokens + components)
├── app.js              boot, router, polling, banner, 401 handling
├── api.js              fetch wrapper for /api/v1 + global 401 signal
├── i18n.js             RU-first strings with an EN twin (no ICU machinery)
├── state.js            tiny observable store + pending-policy normalizer
├── ui.js               DOM/clipboard/modal/toast helpers (safe text building)
└── views/
    ├── login.js        password → session cookie
    ├── status.js       service/mode/TLS/pending-policies
    ├── settings.js     dynamic fields from GET /settings
    ├── secrets.js      write-only secrets
    ├── mcp.js          MCP server CRUD + per-server secret
    ├── management.js   mode radio + idle timeout + LAN warning
    ├── export.js       encrypted export download / import + result report
    └── password.js     password change
```

## Loading & polling model

- **No framework, no virtual DOM.** Each view is a pure `render*View(ctx)`
  function that builds DOM nodes; the shell mounts one at a time under the
  top bar and re-mounts it on navigation.
- **Routing is `location.hash`** (`#/settings/brain`). The server falls back to
  `index.html` for unknown paths, so a deep link survives a reload.
- **First paint:** `boot()` hydrates the stored theme/locale, then probes
  `GET /api/v1/status`. A `200` mounts the shell; a `401` mounts the login view.
- **Polling:** the shell re-fetches `GET /status` every **15 s**, and also on
  `visibilitychange` → visible and on window focus. Polling is paused while the
  tab is hidden. The `pendingPolicies` banner is driven by that payload.
- **Collections are fetched lazily and cached** (`settings`, `secrets`, `mcp`);
  a view asks for what it needs via `ctx.load*`, and `ctx.reload*` re-fetches
  and re-renders. In-flight requests are de-duplicated.
- **Mutations are immediate** (`PUT`/`POST`/`DELETE`); after a write the
  affected collection and `/status` are refreshed so the banner stays truthful.
- **401 is global.** A single `onUnauthorized` handler drops to the login view
  and shows a "session expired" notice; the login exchange opts out so a wrong
  password does not trigger it.

## Screens ↔ endpoints

| Screen | Reads | Writes |
|---|---|---|
| Login | `POST /sessions` (201 + cookie) | — |
| Status | `GET /status` (polled) | — |
| Settings | `GET /settings`(+`/{key}` lazy load) | `PUT /settings/{key}` |
| Secrets | `GET /secrets` | `PUT /secrets/{key}`, `DELETE /secrets/{key}` |
| MCP servers | `GET /mcp/servers` | `POST /mcp/servers`, `PUT /mcp/servers/{id}`, `DELETE /mcp/servers/{id}`, `PUT /mcp/servers/{id}/secret` |
| Management mode | `GET /status` | `POST /management/mode` |
| Export / Import | `GET /secrets` (for the include count) | `POST /export`, `POST /import` |
| Password | — | `POST /password/change` |
| Logout | — | `DELETE /sessions/current` |

`GET /settings` drives the Settings screen: `type` selects the control
(`BOOLEAN`→switch, `INT`/`LONG`→integer input, `FLOAT`→decimal input,
`STRING`→text, `ENUM`→select **only if** the payload carries an option list,
else a labelled text input, `JSON_BLOB`→read-only `<pre>` + note). `policy`
becomes the per-field badge; `secret:true` routes to the Secrets view because
the API is write-only.

## CSP & security constraints (must hold for every change)

The server sends the authoritative header:

```
Content-Security-Policy: default-src 'self'; script-src 'self';
  style-src 'self'; connect-src 'self'; frame-ancestors 'none'
```

Consequently the SPA:

- has **no inline scripts** and **no inline event handlers** (`onclick=`); all
  behaviour is bound with `addEventListener`;
- loads **no external fonts, scripts, styles or images** (no CDN, no web
  fonts — the type stack is system fonts only);
- has **no `eval`/`new Function`** and no string-to-code paths;
- talks only to its own origin (`fetch('/api/v1/…', { credentials: 'same-origin' })`);
- writes user-controlled strings via `textContent` (see `ui.el`), never
  `innerHTML`, so a setting value or server error can never inject markup. The
  only `innerHTML` uses are static, in-source SVG icon paths.

`index.html` also mirrors the policy in a `<meta http-equiv>` as
defence-in-depth; the browser enforces the intersection, so keep both in mind
when adding a capability. The server additionally sends `X-Content-Type-Options:
nosniff` — because of it, every asset must keep a conventional extension
(`.js`, `.css`, `.html`, `.svg`) so the served `Content-Type` is correct.

## Auth model

The browser logs in once and then relies on the `HttpOnly; Secure;
SameSite=Strict` session cookie. The SPA never reads or stores the password; it
is passed straight to `POST /sessions` and dropped. `Authorization: Bearer
<password>` is the REST/script path and is deliberately **not** used by the UI.
HTTP `423`/`429` from failed logins are surfaced as the lockout message.

## The visual system (design notes)

- **Concept:** an instrument panel, not a marketing page. The Android app's
  palette ("arc-reactor teal" primary + warm amber accent, calm near-black
  surfaces) is extended to the web so the console reads as the same product.
- **Tokens:** all colour, spacing, radius, shadow and type values are CSS
  custom properties in `:root`, with a full light twin under
  `[data-theme="light"]` and a `prefers-color-scheme` path for `system`. Dark
  is the default.
- **Type:** a system sans stack for UI and a monospace stack for keys,
  fingerprints, ports and versions — the monospace is a deliberate signal for
  "exact value you may need to copy".
- **Colour roles:** teal = primary/active, amber = a change that needs a
  restart, red = danger/destructive, green = success/applied. Status is never
  colour-only — every pill carries a text label and a badge dot.
- **Layout:** persistent sidebar (collapses to a drawer under 820 px), a
  sticky top bar with the live management pill, and a single content column
  capped at 1180 px. Cards group related facts; metric tiles give the four
  status numbers a scannable rhythm.
- **Motion:** a short staggered nav reveal, view fade-rise, modal rise and
  toast slide — all one-shot and disabled under `prefers-reduced-motion`.
- **Honesty:** warnings are explicit (LAN exposure, include-secrets, write
  access, restart-required banner) and destructive actions confirm first.
- **Accessibility:** ≥38 px controls (icon buttons 36 px with a hit target),
  visible `:focus-visible` rings, `aria-live` toasts, focus-trapped modals,
  Esc-to-close, and label/`for` association on every field.

### Top 5 tasks

1. **Change a setting** — Settings → pick a category in the rail → edit the
   field → **Save** appears only on the changed row; the response's `policy`
   drives the badge, and a `SERVICE_RESTART`/`APP_RESTART` value adds itself to
   the pending banner at the top.
2. **Set a secret** — Secrets → **Задать** → type the value (never echoed back)
   → Save. The table only ever shows «Задан» / «Не задан».
3. **Add an MCP server** — MCP-серверы → **Добавить сервер** → name, kind
   (REMOTE/LOCAL/LAN, with the URL rule shown inline), URL, access, optional
   auth header and secret → Add. A WRITE server warns inline.
4. **Enable LAN** — Режим управления → pick «Локальная сеть» (the warning
   appears) → **Применить**. If LAN is selected but stopped, **Включить
   сейчас** reopens the listener; the stale-intent case is explicit.
5. **Export config** — Экспорт и импорт → enter + confirm a passphrase → tick
   «Включить секреты» if desired (a count and warning appear) → **Скачать
   архив**; the browser saves the encrypted envelope.

## Preview locally

The console is served by the on-device management server, so preview it against
that server over `adb forward` — no separate web server is needed:

```bash
# On the device: enable Management (any mode). LOCALHOST is the safe default.
# The default listen port is 8765 (Settings → Управление can change it).
adb forward tcp:8765 tcp:8765
# Then open in a browser:
#   https://localhost:8765/
```

The certificate is self-signed, so the browser shows a warning — that is
expected. Compare the SHA-256 fingerprint on the Status screen with the
browser's certificate details before trusting the session.

To iterate on the UI without a device you can serve this directory with any
static server **and** point `api.js`'s `BASE` at a running API; a plain static
server alone will render the login and then fail on `/api/v1`. Do not commit a
changed `BASE` — it must stay `/api/v1` (relative).

## Backend follow-ups (contract gaps encountered)

1. **Asset path mapping (integration blocker).** The SPA lives under
   `assets/web/`, but `AndroidAssetSource.read(path)` only strips the leading
   `/` and calls `assets.open(...)`, so a request for `/app.js` resolves to
   `assets/app.js` — not `assets/web/app.js`. It must prefix `web/` (or the
   route layer must), otherwise `GET /` cannot load `index.html` and the console
   never boots. The file location (`assets/web/`) is per STRATEGY §14.2; the
   mapping is the missing half.
2. **Enum option lists.** `GET /settings` returns `type:"ENUM"` but no options,
   so enum fields render as free-text with an honest note. Adding an
   `options: string[]` (or `[{value,label}]`) to enum entries would turn these
   into selects. Until then the UI degrades gracefully.
3. **`GET /settings/{key}`** returns `403 secret_setting` for secrets — fine,
   the UI never asks; it routes secrets to their own screen.
4. **Import transport.** The implementation takes `{passphrase, data}` where
   `data` is the **raw envelope text**, not base64. The SPA matches that; a
   multipart variant is not used.
5. **`pendingPolicies`** carries policy tokens (`SERVICE_RESTART`,
   `APP_RESTART`), not setting keys, so the banner explains *what a restart
   means* but cannot name the affected settings. Adding the changed keys would
   let the banner list them.
6. **`/status.service`** is the fixed string `"jarvis"` (an identifier, not a
   run-state), so the Status screen labels it as the service name and derives
   the hero from the management mode instead.
