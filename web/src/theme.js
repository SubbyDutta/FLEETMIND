// Shared display maps: how backend enums turn into colors + human labels.
// Kept in one place so the map, legend, alerts panel, and stat cards all agree.
// Colors are referenced by CSS via inline custom properties (--c), never by
// hard-coded styles in components. Tuned for the light map theme.

export const MAP_CENTER = [22.5726, 88.3639] // Kolkata
export const MAP_ZOOM = 12

export const MAP_BOUNDS = [
  [22.40, 88.20],
  [22.72, 88.52],
]
export const MIN_ZOOM = 11
export const MAX_ZOOM = 18

export const ROUTE_COLOR = '#2563eb'

export const DRIVER_STATUS = {
  IDLE:      { label: 'Idle',       color: '#f59e0b' },
  TO_PICKUP: { label: 'To pickup',  color: '#2563eb' },
  TO_DROP:   { label: 'To dropoff', color: '#16a34a' },
  OFFLINE:   { label: 'Offline',    color: '#94a3b8' },
}

// Fixed map locations (per order): the restaurant to pick up from and the
// customer's dropoff. Riders are colored by status (above); places are constant.
export const PLACES = {
  restaurant: { label: 'Restaurant', color: '#ea580c' },
  dropoff:    { label: 'Customer',   color: '#7c3aed' },
}

export const ALERT_TYPE = {
  SLA_BREACH:  { label: 'SLA breach' },
  STUCK:       { label: 'Driver stuck' },
  IDLE_DRIVER: { label: 'Idle driver' },
}

export const SEVERITY = {
  HIGH: { label: 'High',   color: '#dc2626' },
  MED:  { label: 'Medium', color: '#f59e0b' },
  LOW:  { label: 'Low',    color: '#2563eb' },
}

// Small fallbacks so an unknown enum never crashes the UI.
export const driverStyle = (s) => DRIVER_STATUS[s] || { label: s || 'Unknown', color: '#94a3b8' }
export const severityStyle = (s) => SEVERITY[s] || { label: s || '—', color: '#94a3b8' }
export const alertLabel = (t) => (ALERT_TYPE[t] || { label: t || 'Alert' }).label
