# QMS Frontend

React 18 + TypeScript + Vite single-page app for the Queueing Management System:
the staff application (agent console, supervisor monitor, administration, reports) and the
unauthenticated device pages (kiosk, queue display, feedback).

The API it codes against is `../docs/api-contract.md`; the TypeScript mirror lives in `src/api/types.ts`.

## Requirements

Node 20.x and npm 10.x. Dependency versions are pinned exactly; `package-lock.json` is committed.

## Scripts

| Command | What it does |
|---|---|
| `npm install` | Install dependencies (use `npm ci` in CI) |
| `npm run dev` | Dev server on http://localhost:5173, proxying `/api` and `/ws` (WebSocket) to http://localhost:8080 (override with `QMS_API_TARGET=http://localhost:8090`) |
| `npm run build` | Type-check (`tsc -b`) and build to `dist/` |
| `npm run preview` | Serve the production build locally |
| `npm test` | Vitest in watch mode (`npm test -- --run` for a single run) |
| `npm run typecheck` | Type-check only |

## Environment variables

Copy `.env.example` to `.env` (or `.env.local`). Values are baked in at build time.

| Variable | Default | Purpose |
|---|---|---|
| `VITE_API_BASE_URL` | `/api/v1` | Base URL of the REST API. Keep it relative when the SPA and API share a reverse proxy. |
| `VITE_AGENCY_NAME` | `PhilHealth` | Agency name, used as the logo's alternative text and on printed tickets. |
| `VITE_AGENCY_LOGO` | `/branding/logo.svg` | Logo file under `public/`. The PhilHealth logo supplied in `docs/logo.svg` is bundled there; it is shown on a white plate so it stays legible on coloured headers. |

The STOMP endpoint is always derived from the page location: `ws(s)://<host>/ws`, so the reverse
proxy must forward `/ws` with WebSocket upgrade headers.

## Structure

```
src/
  api/          axios client (+401 → refresh → retry interceptor), device client, typed modules per contract area
  auth/         AuthContext (access token in memory, refresh token in sessionStorage), SiteContext, route guards
  components/   Layout, DataTable, PageHeader, Modal, ConfirmDialog, ReasonDialog, FormModal, ErrorAlert, StatusBadge, Spinner…
  hooks/        useAsync, useQueueTopic (STOMP + 15 s polling + auto-reconnect), useSiteQueue, useIdleTimer, useLongPress
  pages/        staff pages; pages/admin (administration); pages/device (kiosk, display, feedback)
  utils/        pure helpers (agent action rules, kiosk issuing/retry, metrics formatting, upload checks…)
```

Navigation and route guards share one definition (`src/navigation.ts`): a link is shown, and its
route allowed, when the user holds any of the listed permissions from `me.permissions`.

## Device setup (kiosk and queue display)

1. An administrator registers the device under **Admin › Devices** (type `KIOSK` or `DISPLAY`) and
   copies the API key shown once.
2. On the device open `https://<server>/kiosk` or `https://<server>/display`, paste the key and
   press **Save and start**. The key is stored in `localStorage` (`qms.deviceKey`) and sent as `X-Device-Key`.
3. To reset a device, press and hold the **top-left corner for 5 seconds**, then confirm. The key is
   removed and the setup screen appears again.

Client feedback can be collected on a kiosk at `/feedback/<ticketId>` (same device key).

### Recommended Chrome flags

```
# Kiosk: full screen, and print tickets silently to the default (80 mm thermal) printer
chrome --kiosk --kiosk-printing --noerrdialogs --disable-session-crashed-bubble https://<server>/kiosk

# Queue display: full screen and allow the chime/voice to play without a click
chrome --kiosk --autoplay-policy=no-user-gesture-required --noerrdialogs https://<server>/display
```

* Set the kiosk printer as the OS default and its paper size to 80 mm roll; the ticket layout uses
  `@page { size: 80mm auto }`.
* On the display, use the **Voice on/off** toggle to enable spoken announcements (Web Speech API).
  The chime is generated with the Web Audio API; no audio files are required.
* The display recovers on its own: WebSocket reconnects automatically, the board is polled every
  15 s, and the page reloads itself if no request has succeeded for 5 minutes.
* The kiosk returns to its home screen after 20 s without interaction.

## Testing

Unit and component tests use Vitest + Testing Library (jsdom) and live next to the code
(`*.test.ts[x]`). They cover permission guards, error rendering, agent-console action rules,
idempotent kiosk issuing, report metric formatting and other pure helpers.
