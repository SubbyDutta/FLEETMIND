import { authHeaders, onUnauthorized } from './auth'

const BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8090/api'
const SIM_BASE = import.meta.env.VITE_SIM_BASE || 'http://localhost:8090/sim'

export async function login(email, password) {
  const res = await fetch(`${BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (res.status === 401) throw new Error('Invalid email or password')
  if (res.status === 429) throw new Error('Too many login attempts — wait a moment')
  if (!res.ok) throw new Error(`login -> HTTP ${res.status}`)
  return res.json()
}

async function getJson(path) {
  const res = await fetch(`${BASE}${path}`, { headers: authHeaders() })
  if (res.status === 401) { onUnauthorized(); throw new Error('session expired') }
  if (!res.ok) throw new Error(`${path} -> HTTP ${res.status}`)
  return res.json()
}

export const fetchDrivers = () => getJson('/drivers')
export const fetchOrders = () => getJson('/orders')
export const fetchAlerts = () => getJson('/alerts')

export async function fetchRoute(driverId) {
  try {
    const data = await getJson(`/drivers/${driverId}/route`)
    return data.points || []
  } catch {
    return []
  }
}

async function readSse(url, { signal, onOpen, onEvent }) {
  const res = await fetch(url, { headers: { ...authHeaders(), Accept: 'text/event-stream' }, signal })
  if (res.status === 401) { onUnauthorized(); throw new Error('session expired') }
  if (!res.ok) throw new Error(`stream -> HTTP ${res.status}`)
  onOpen?.()
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const records = buffer.split(/\r?\n\r?\n/)
    buffer = records.pop()
    for (const record of records) {
      let event = 'message'
      const data = []
      for (const line of record.split(/\r?\n/)) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
      }
      if (data.length) onEvent(event, data.join('\n'))
    }
  }
}

export function openStream({ onDriver, onAlert, onStatus }) {
  const controller = new AbortController()
  let stopped = false
  const run = async () => {
    while (!stopped) {
      try {
        await readSse(`${BASE}/stream`, {
          signal: controller.signal,
          onOpen: () => onStatus(true),
          onEvent: (event, data) => {
            if (event === 'driver') onDriver(JSON.parse(data))
            else if (event === 'alert') onAlert(JSON.parse(data))
          },
        })
      } catch {
        if (stopped) return
      }
      onStatus(false)
      await new Promise((r) => setTimeout(r, 3000))
    }
  }
  run()
  return () => { stopped = true; controller.abort() }
}

export async function setDriverStuck(driverId, stuck) {
  const res = await fetch(`${SIM_BASE}/${stuck ? 'incident' : 'recover'}/${driverId}`, {
    method: 'POST',
    headers: authHeaders(),
  })
  if (res.status === 401) { onUnauthorized(); throw new Error('session expired') }
  if (!res.ok) throw new Error(`simulator -> HTTP ${res.status}`)
  return res.text()
}

export async function askAnalytics(question) {
  const res = await fetch(`${BASE}/analytics`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ question }),
  })
  if (res.status === 401) { onUnauthorized(); throw new Error('session expired') }
  const body = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(body.error || `analytics -> HTTP ${res.status}`)
  return body
}

export function askAgent(question, { onStep, onFinal, onError }) {
  const controller = new AbortController()
  let finished = false
  const finish = (fn, payload) => {
    if (finished) return
    finished = true
    controller.abort()
    fn(payload)
  }
  readSse(`${BASE}/agent/chat?q=${encodeURIComponent(question)}`, {
    signal: controller.signal,
    onEvent: (event, data) => {
      if (event === 'tool_call') onStep({ type: 'tool_call', ...JSON.parse(data) })
      else if (event === 'tool_result') onStep({ type: 'tool_result', ...JSON.parse(data) })
      else if (event === 'final') finish(onFinal, JSON.parse(data).payload)
      else if (event === 'error') finish(onError, JSON.parse(data).payload || JSON.parse(data))
    },
  })
    .then(() => finish(onError, { error: 'stream ended without a final answer' }))
    .catch(() => finish(onError, { error: 'connection to agent lost' }))
  return () => controller.abort()
}
