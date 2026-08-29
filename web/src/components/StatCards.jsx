// Three at-a-glance counters across the top of the sidebar.
export default function StatCards({ drivers, orders, alerts }) {
  const activeDrivers = drivers.filter((d) => d.status && d.status !== 'OFFLINE').length
  const openAlerts = alerts.length

  const cards = [
    { label: 'Drivers', value: activeDrivers, total: drivers.length },
    { label: 'Active orders', value: orders.length },
    { label: 'Alerts', value: openAlerts, danger: openAlerts > 0 },
  ]

  return (
    <div className="stats">
      {cards.map((c) => (
        <div key={c.label} className={`stat ${c.danger ? 'stat--danger' : ''}`}>
          <div className="stat__value">
            {c.value}
            {c.total != null && <span className="stat__total">/{c.total}</span>}
          </div>
          <div className="stat__label">{c.label}</div>
        </div>
      ))}
    </div>
  )
}
