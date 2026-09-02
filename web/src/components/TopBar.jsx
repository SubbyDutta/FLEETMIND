export default function TopBar({ connected, user, onLogout }) {
  return (
    <header className="topbar">
      <div className="topbar__brand">
        <span className="topbar__mark" />
        <span className="topbar__name">FleetMind</span>
        <span className="topbar__sub">Ops Console · Kolkata</span>
      </div>
      <div className="topbar__right">
        <div className={`conn ${connected ? 'conn--on' : 'conn--off'}`}>
          <span className="conn__dot" />
          {connected ? 'Live' : 'Reconnecting…'}
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
