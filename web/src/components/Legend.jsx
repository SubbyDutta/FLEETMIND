import { DRIVER_STATUS, PLACES } from '../theme'

// Floating key, bottom-left of the map: rider status colors + place markers.
export default function Legend() {
  const items = [...Object.values(DRIVER_STATUS), ...Object.values(PLACES)]
  return (
    <div className="legend">
      {items.map((s) => (
        <span key={s.label} className="legend__item">
          <span className="legend__dot" style={{ '--c': s.color }} />
          {s.label}
        </span>
      ))}
    </div>
  )
}
