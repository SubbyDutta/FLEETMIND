// App header: brand + live connection indicator (green pulse when the SSE
// stream is open, grey when it has dropped / is reconnecting).
export default function TopBar({ connected }) {
  return (
    <header className="topbar">
      <div className="topbar__brand">
        <span className="topbar__mark" />
        <span className="topbar__name">FleetMind</span>
        <span className="topbar__sub">Ops Console · Kolkata</span>
      </div>
      <div className={`conn ${connected ? 'conn--on' : 'conn--off'}`}>
        <span className="conn__dot" />
        {connected ? 'Live' : 'Reconnecting…'}
      </div>
    </header>
  )
}
