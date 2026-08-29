import { severityStyle, alertLabel } from '../theme'

// Live alerts feed. New items animate in (the "flash" the brief asks for) via the
// .alert entry animation in styles.css — newest is always on top.
export default function AlertsPanel({ alerts }) {
  return (
    <div className="panel alerts">
      <div className="panel__head">
        <h2 className="panel__title">Alerts</h2>
        <span className="panel__count">{alerts.length}</span>
      </div>

      {alerts.length === 0 && <p className="panel__empty">No alerts. All clear.</p>}

      <ul className="alerts__list">
        {alerts.map((a, i) => {
          const sev = severityStyle(a.severity)
          const who = a.driverId || a.driver_id || a.orderId || a.order_id || '—'
          return (
            <li key={a._id || a.id || i} className="alert" style={{ '--sev': sev.color }}>
              <div className="alert__top">
                <span className="alert__type">{alertLabel(a.type)}</span>
                <span className="alert__sev">{sev.label}</span>
              </div>
              <p className="alert__reason">{a.reason}</p>
              <div className="alert__meta">{who}</div>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
