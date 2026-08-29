# FleetMind — Ops Console (frontend)

A single-page live map of the Kolkata fleet. React + Vite + Leaflet.

## Run

```bash
npm install
npm run dev          # http://localhost:5173
```

The command-service (read API + SSE) must be running on **:8086**. To point
elsewhere, set `VITE_API_BASE` (e.g. `VITE_API_BASE=http://host:9000/api npm run dev`).

## How it fits together

```
api.js        all network access (REST + SSE). Nothing else knows the URL.
theme.js      enum -> color / label maps shared by every view.
useFleet.js   the one data hook: initial fetch + live SSE + order polling.
App.jsx       layout only; passes data down as props.
components/    TopBar · MapView · Legend · StatCards · AlertsPanel · RiderCard · AgentConsole
styles.css    the whole design system (tokens at the top).
```

Data flow: `useFleet` holds all state. Drivers and alerts arrive live over SSE
(`/api/stream`); orders aren't streamed by the backend, so they're polled every
8s. Every component is a plain view of the props it's handed.

## Add a feature later

- **New panel in the sidebar** → add a component, drop it in `App.jsx`.
- **New backend endpoint** → add one function in `api.js`.
- **New colors/labels** → edit the maps in `theme.js`; the map, legend, and
  panels all follow automatically.
- **Agent UI** → `AgentConsole.jsx`: floating orb + drawer with two modes —
  dispatch (live SSE tool timeline) and analytics (single POST → JSON answer).

Components stay style-free: real CSS lives in `styles.css`, and dynamic colors
are passed in as the `--c` / `--sev` custom properties.
