import { useEffect, useState } from 'react'

// Ticking UTC clock — the kind of detail every real ops console has.
function UtcClock() {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(id)
  }, [])
  const hh = String(now.getUTCHours()).padStart(2, '0')
  const mm = String(now.getUTCMinutes()).padStart(2, '0')
  const ss = String(now.getUTCSeconds()).padStart(2, '0')
  return (
    <span className="topbar__clock" title="Coordinated Universal Time">
      {hh}:{mm}:<span className="topbar__clock-s">{ss}</span> UTC
    </span>
  )
}

export default function TopBar({ connected, user, onLogout }) {
  return (
    <header className="topbar">
      <div className="topbar__brand">
        <span className="topbar__mark" />
        <span className="topbar__name">FleetMind</span>
        <span className="topbar__divider" />
        <span className="topbar__sub">Fleet Operations · Kolkata</span>
      </div>
      <div className="topbar__right">
        <UtcClock />
        <div className={`conn ${connected ? 'conn--on' : 'conn--off'}`}>
          <span className="conn__dot" />
          {connected ? 'Live · SSE' : 'Reconnecting…'}
        </div>
        {user && (
          <div className="topbar__user">
            <span className="topbar__tenant">{user.tenant}</span>
            <span className="topbar__email">{user.email}</span>
            <button className="topbar__logout" onClick={onLogout}>Sign out</button>
          </div>
        )}
      </div>
    </header>
  )
}
