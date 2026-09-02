import { useState } from 'react'
import { useFleet } from './useFleet'
import { clearAuth, getAuth } from './auth'
import TopBar from './components/TopBar'
import MapView from './components/MapView'
import StatCards from './components/StatCards'
import AlertsPanel from './components/AlertsPanel'
import AgentConsole from './components/AgentConsole'
import RiderCard from './components/RiderCard'
import Login from './components/Login'

export default function App() {
  const [auth, setAuth] = useState(() => getAuth())

  if (!auth?.token) {
    return <Login onLoggedIn={setAuth} />
  }
  return <Dashboard auth={auth} onLogout={() => { clearAuth(); setAuth(null) }} />
}

function Dashboard({ auth, onLogout }) {
  const { drivers, orders, alerts, connected } = useFleet()
  const [selectedId, setSelectedId] = useState(null)
  const [stuckIds, setStuckIds] = useState(() => new Set())

  const markStuck = (id, stuck) =>
    setStuckIds((prev) => {
      const next = new Set(prev)
      stuck ? next.add(id) : next.delete(id)
      return next
    })

  const selectedDriver = drivers.find((d) => d.id === selectedId) || null
  const selectedOrder = orders.find((o) => o.assigned_driver === selectedId) || null

  const user = { email: auth.email || 'signed in', tenant: auth.tenant }

  return (
    <div className="app">
      <TopBar connected={connected} user={user} onLogout={onLogout} />
      <main className="app__body">
        <section className="app__map">
          <MapView drivers={drivers} orders={orders} selectedId={selectedId} onSelect={setSelectedId} />
          {selectedDriver && (
            <RiderCard
              driver={selectedDriver}
              order={selectedOrder}
              stuck={stuckIds.has(selectedDriver.id)}
              onStuckChange={markStuck}
              onClose={() => setSelectedId(null)}
            />
          )}
        </section>
        <aside className="app__side">
          <StatCards drivers={drivers} orders={orders} alerts={alerts} />
          <AlertsPanel alerts={alerts} />
        </aside>
      </main>
      <AgentConsole />
    </div>
  )
}
