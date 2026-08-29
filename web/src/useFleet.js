import { useEffect, useState } from 'react'
import { fetchDrivers, fetchOrders, fetchAlerts, openStream } from './api'

const ORDER_POLL_MS = 8000 // orders aren't streamed, so refresh them on a timer
const MAX_ALERTS = 50

// The one place that holds live fleet state. Components just read what they need.
//
//   drivers  - keyed by driverId so a "driver" SSE event upserts in place
//   orders   - replaced wholesale on each poll
//   alerts   - newest first, capped
//   connected- whether the SSE stream is currently open
export function useFleet() {
  const [drivers, setDrivers] = useState({})
  const [orders, setOrders] = useState([])
  const [alerts, setAlerts] = useState([])
  const [connected, setConnected] = useState(false)

  // Initial snapshot from the read API.
  useEffect(() => {
    fetchDrivers().then((rows) => {
      const next = {}
      for (const d of rows) next[d.id] = d
      setDrivers(next)
    }).catch(console.error)
    fetchAlerts().then(setAlerts).catch(console.error)
  }, [])

  // Live stream: driver moves + new alerts.
  useEffect(() => {
    const close = openStream({
      onStatus: setConnected,
      onDriver: (d) => {
        setDrivers((prev) => {
          const existing = prev[d.driverId] || {}
          // SSE omits the name; keep whatever the snapshot gave us.
          const merged = { ...existing, id: d.driverId, lat: d.lat, lng: d.lng, status: d.status, speed_kmph: d.speed }
          return { ...prev, [d.driverId]: merged }
        })
      },
      onAlert: (a) => {
        const withId = { ...a, _id: `${a.type}-${a.orderId}-${a.driverId}-${Date.now()}` }
        setAlerts((prev) => [withId, ...prev].slice(0, MAX_ALERTS))
      },
    })
    return close
  }, [])

  // Poll orders.
  useEffect(() => {
    let timer
    const tick = () => fetchOrders().then(setOrders).catch(console.error)
    tick()
    timer = setInterval(tick, ORDER_POLL_MS)
    return () => clearInterval(timer)
  }, [])

  return { drivers: Object.values(drivers), orders, alerts, connected }
}
