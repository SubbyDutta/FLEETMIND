import { useState } from 'react'
import { useFleet } from './useFleet'
import TopBar from './components/TopBar'
import MapView from './components/MapView'
import StatCards from './components/StatCards'
import AlertsPanel from './components/AlertsPanel'
import AgentConsole from './components/AgentConsole'
import RiderCard from './components/RiderCard'

// Layout only. All data comes from the one useFleet hook and flows down as props.
// Selection (which rider is clicked) lives here so both the map and the rider
// card stay in sync.
export default function App() {
  const { drivers, orders, alerts, connected } = useFleet()
  const [selectedId, setSelectedId] = useState(null)
  // Drivers this session froze via the incident button (the simulator doesn't
  // expose its stuck flag, so the UI tracks what it did).
  const [stuckIds, setStuckIds] = useState(() => new Set())

  const markStuck = (id, stuck) =>
    setStuckIds((prev) => {
      const next = new Set(prev)
      stuck ? next.add(id) : next.delete(id)
      return next
    })

  const selectedDriver = drivers.find((d) => d.id === selectedId) || null
  const selectedOrder = orders.find((o) => o.assigned_driver === selectedId) || null

  return (
    <div className="app">
      <TopBar connected={connected} />
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
