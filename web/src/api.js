// All network access lives here. Nothing else in the app knows the backend URL.
// Override at build/dev time with VITE_API_BASE if the command-service moves.

const BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8086/api'
const SIM_BASE = import.meta.env.VITE_SIM_BASE || 'http://localhost:8085/sim'

async function getJson(path) {
  const res = await fetch(`${BASE}${path}`)
  if (!res.ok) throw new Error(`${path} -> HTTP ${res.status}`)
  return res.json()
}

export const fetchDrivers = () => getJson('/drivers')
export const fetchOrders = () => getJson('/orders')
export const fetchAlerts = () => getJson('/alerts')

// Route for one driver, as [[lat, lng], ...]. Backend: GET /drivers/{id}/route -> { points }.
// Returns [] on any error so the UI degrades gracefully until the endpoint exists.
export async function fetchRoute(driverId) {
  try {
    const data = await getJson(`/drivers/${driverId}/route`)
    return data.points || []
  } catch {
    return []
  }
}

// Open the live Server-Sent Events stream. The backend emits two named events:
//   "driver" -> { driverId, lat, lng, status, speed }
//   "alert"  -> { type, severity, driverId, orderId, reason }
// EventSource auto-reconnects, so we just report open/closed via onStatus.
export function openStream({ onDriver, onAlert, onStatus }) {
  const es = new EventSource(`${BASE}/stream`)
  es.onopen = () => onStatus(true)
  es.onerror = () => onStatus(false)
  es.addEventListener('driver', (e) => onDriver(JSON.parse(e.data)))
  es.addEventListener('alert', (e) => onAlert(JSON.parse(e.data)))
  return () => es.close()
}

// Test harness: freeze/unfreeze a driver in the simulator. Freezing stops the
// driver's movement, so the stream-processor's stuck detector raises an alert
// a couple of windows later — the incident the P9 agent investigates.
export async function setDriverStuck(driverId, stuck) {
  const res = await fetch(`${SIM_BASE}/${stuck ? 'incident' : 'recover'}/${driverId}`, { method: 'POST' })
  if (!res.ok) throw new Error(`simulator -> HTTP ${res.status}`)
  return res.text()
}

// P9: ask the dispatch agent. One question = one FINITE SSE episode that always
// ends with a "final" or "error" event ({ step, tool, payload } bodies).
// EventSource auto-reconnects when a stream closes — which here would re-run
// the entire episode (and re-execute write tools like reassign_order!), so
// every terminal path closes the source explicitly. Returns a cancel function.
// P11: ask the read-only analytics agent. Unlike the dispatch chat (a live SSE
// episode), analytics is one POST -> one JSON: the backend drains the agent's
// gRPC stream itself and returns { answer, steps, tools_used }. Failures come
// back as non-2xx with an { error } body.
export async function askAnalytics(question) {
  const res = await fetch(`${BASE}/analytics`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question }),
  })
  const body = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(body.error || `analytics -> HTTP ${res.status}`)
  return body
}

export function askAgent(question, { onStep, onFinal, onError }) {
  const es = new EventSource(`${BASE}/agent/chat?q=${encodeURIComponent(question)}`)
  const finish = (fn, payload) => { es.close(); fn(payload) }

  es.addEventListener('tool_call', (e) => onStep({ type: 'tool_call', ...JSON.parse(e.data) }))
  es.addEventListener('tool_result', (e) => onStep({ type: 'tool_result', ...JSON.parse(e.data) }))
  es.addEventListener('final', (e) => finish(onFinal, JSON.parse(e.data).payload))
  // The agent's terminal failure event is also named "error", colliding with
  // EventSource's own transport-error event: agent events carry data, transport
  // failures don't — both must end the episode.
  es.addEventListener('error', (e) =>
    finish(onError, e.data ? JSON.parse(e.data).payload : { error: 'connection to agent lost' }))

  return () => es.close()
}
