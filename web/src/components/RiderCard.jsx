import { useState } from 'react'
import { driverStyle } from '../theme'
import { setDriverStuck } from '../api'

// Predicted-arrival timestamp -> "3:45 PM · 12 min" (or "· overdue").
function etaText(value) {
  if (!value) return '—'
  const t = new Date(value)
  const mins = Math.round((t.getTime() - Date.now()) / 60000)
  const clock = t.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  if (mins < 0) return `${clock} · overdue`
  return `${clock} · ${mins} min`
}

// Floating card for the clicked rider: who they are, their order, and the ETA.
// Shown over the top-left of the map; the route itself is drawn by MapView.
// `stuck` + `onStuckChange` live in App — the simulator doesn't expose its
// stuck flag, so the UI remembers which drivers it froze.
export default function RiderCard({ driver, order, stuck, onStuckChange, onClose }) {
  const status = driverStyle(driver.status)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState(null)

  const toggleStuck = async () => {
    setPending(true)
    setError(null)
    try {
      await setDriverStuck(driver.id, !stuck)
      onStuckChange(driver.id, !stuck)
    } catch {
      setError('simulator unreachable')
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="ridercard">
      <button className="ridercard__close" onClick={onClose} aria-label="Close">×</button>
      <div className="ridercard__head">
        <span className="ridercard__name">{driver.name || driver.id}</span>
        <span className="ridercard__status" style={{ '--c': status.color }}>{status.label}</span>
      </div>
      {order ? (
        <dl className="ridercard__rows">
          <div><dt>Restaurant</dt><dd>{order.restaurant}</dd></div>
          <div><dt>Customer</dt><dd>{order.customer_name}</dd></div>
          <div><dt>ETA</dt><dd>{etaText(order.current_eta)}</dd></div>
          <div><dt>Speed</dt><dd>{Math.round(driver.speed_kmph || 0)} km/h</dd></div>
        </dl>
      ) : (
        <p className="ridercard__idle">No active order.</p>
      )}

      <button
        className={`ridercard__incident${stuck ? ' ridercard__incident--stuck' : ''}`}
        onClick={toggleStuck}
        disabled={pending}
      >
        {pending ? '…' : stuck ? '↺ Recover driver' : '⚡ Simulate stuck'}
      </button>
      {error && <p className="ridercard__incident-note">{error}</p>}
      {stuck && !error && (
        <p className="ridercard__incident-note">frozen — stuck alert fires in ~2 windows</p>
      )}
    </div>
  )
}
