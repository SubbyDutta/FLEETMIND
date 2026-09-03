import { useEffect, useRef, useState } from 'react'
import L from 'leaflet'
import { MAP_CENTER, MAP_ZOOM, MAP_BOUNDS, MIN_ZOOM, MAX_ZOOM, driverStyle, ROUTE_COLOR } from '../theme'
import { riderIcon, foodIcon, homeIcon } from '../icons'
import { fetchRoute } from '../api'
import Legend from './Legend'

// Leaflet is imperative, so we keep it in refs and drive it from effects:
//   - build the map once on mount
//   - upsert driver markers whenever the live driver list changes
//   - rebuild order markers (restaurant + dropoff) whenever the poll updates
//   - react to the selected rider: zoom in, draw its route, highlight the dot
// Selection state lives in App so the RiderCard can show the same rider's ETA.
export default function MapView({ drivers, orders, selectedId, onSelect }) {
  const elRef = useRef(null)
  const mapRef = useRef(null)
  const driverMarkers = useRef(new Map()) // driverId -> L.marker
  const ordersLayer = useRef(null)
  const routeLayer = useRef(null)
  const routePts = useRef([]) // full route for the active leg
  const routeLines = useRef([]) // polylines to keep trimmed as the rider moves
  const [refreshTick, setRefreshTick] = useState(0)

  // status of the selected rider; used so the route redraws when the leg flips
  // (TO_PICKUP -> TO_DROP) but NOT on every position ping.
  const selected = drivers.find((d) => d.id === selectedId)
  const selectedStatus = selected ? selected.status : null

  useEffect(() => {
    const bounds = L.latLngBounds(MAP_BOUNDS)
    const map = L.map(elRef.current, {
      zoomControl: false,
      maxBounds: bounds,
      maxBoundsViscosity: 1.0,
      minZoom: MIN_ZOOM,
      maxZoom: MAX_ZOOM,
      inertia: false,
      wheelPxPerZoomLevel: 120,
      zoomSnap: 0.25,
    }).setView(MAP_CENTER, MAP_ZOOM)
    map.setMaxBounds(bounds)
    L.control.zoom({ position: 'bottomright' }).addTo(map)
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap contributors',
      minZoom: MIN_ZOOM,
      maxZoom: MAX_ZOOM,
      bounds,
    }).addTo(map)
    routeLayer.current = L.layerGroup().addTo(map) // under markers
    ordersLayer.current = L.layerGroup().addTo(map)
    map.on('click', () => onSelect(null)) // click empty map = deselect
    mapRef.current = map
    return () => map.remove()
  }, [])

  // Live driver positions. Markers are created once and reused so they glide.
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    for (const d of drivers) {
      if (d.lat == null || d.lng == null) continue
      const { color } = driverStyle(d.status)
      let marker = driverMarkers.current.get(d.id)
      if (!marker) {
        marker = L.marker([d.lat, d.lng], { icon: riderIcon(color) }).addTo(map)
        marker.on('click', (e) => {
          L.DomEvent.stopPropagation(e) // don't let it bubble to the deselect handler
          onSelect(d.id)
        })
        // content resolved on open so it always shows the latest ping's data
        marker.bindTooltip(() => driverTooltip(marker._d), {
          direction: 'top', offset: [0, -12], className: 'driver-tip',
        })
        driverMarkers.current.set(d.id, marker)
      } else {
        marker.setLatLng([d.lat, d.lng])
        if (marker._status !== d.status) marker.setIcon(riderIcon(color))
      }
      marker._status = d.status
      marker._d = d

      if (d.id === selectedId && routePts.current.length > 1) {
        const trimmed = trimRoute(routePts.current, d.lat, d.lng)
        for (const line of routeLines.current) line.setLatLngs(trimmed)
      }
    }
  }, [drivers, selectedId])

  // Order pickups (restaurant) + dropoffs (customer home).
  useEffect(() => {
    const layer = ordersLayer.current
    if (!layer) return
    layer.clearLayers()
    for (const o of orders) {
      if (o.pickup_lat == null || o.dropoff_lat == null) continue
      L.marker([o.pickup_lat, o.pickup_lng], { icon: foodIcon() })
        .bindPopup(`<b>${o.restaurant || 'Restaurant'}</b><br/>Pickup`)
        .addTo(layer)
      L.marker([o.dropoff_lat, o.dropoff_lng], { icon: homeIcon() })
        .bindPopup(orderPopup(o))
        .addTo(layer)
    }
  }, [orders])

  // Selected rider: zoom in, highlight, and draw the route for the active leg.
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    routeLayer.current.clearLayers()
    routePts.current = []
    routeLines.current = []
    highlightSelected(driverMarkers.current, selectedId)
    if (!selectedId) return
    const marker = driverMarkers.current.get(selectedId)
    if (!marker) return
    map.flyTo(marker.getLatLng(), 15)
    if (selectedStatus !== 'TO_PICKUP' && selectedStatus !== 'TO_DROP') return
    fetchRoute(selectedId).then((points) => {
      if (points.length === 0 || routeLayer.current == null) return
      const start = trimRoute(points, marker.getLatLng().lat, marker.getLatLng().lng)
      const casing = L.polyline(start, { color: ROUTE_COLOR, weight: 11, opacity: 0.16, lineCap: 'round', lineJoin: 'round' }).addTo(routeLayer.current)
      const line = L.polyline(start, { color: ROUTE_COLOR, weight: 5, opacity: 0.95, lineCap: 'round', lineJoin: 'round', className: 'route-draw' }).addTo(routeLayer.current)
      const path = line.getElement()
      if (path) path.setAttribute('pathLength', '1')
      routePts.current = points
      routeLines.current = [casing, line]
    })
  }, [selectedId, selectedStatus, refreshTick])

  const routeActive = selectedStatus === 'TO_PICKUP' || selectedStatus === 'TO_DROP'

  const [query, setQuery] = useState('')
  const [miss, setMiss] = useState(false)
  const findDriver = (e) => {
    e.preventDefault()
    const q = query.trim().toLowerCase()
    if (!q) return
    const hit =
      drivers.find((d) => d.id.toLowerCase() === q || (d.name || '').toLowerCase() === q) ||
      drivers.find((d) => d.id.toLowerCase().includes(q) || (d.name || '').toLowerCase().includes(q))
    if (hit) {
      onSelect(hit.id) // the selection effect flies the map to the marker
      setQuery('')
      setMiss(false)
    } else {
      setMiss(true)
    }
  }

  return (
    <div className="map">
      <div ref={elRef} className="map__canvas" />
      <form className={`map-search${miss ? ' map-search--miss' : ''}`} onSubmit={findDriver}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
          <circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" />
        </svg>
        <input
          list="driver-ids"
          value={query}
          placeholder="Find driver…"
          onChange={(e) => { setQuery(e.target.value); setMiss(false) }}
        />
        <datalist id="driver-ids">
          {drivers.map((d) => (
            <option key={d.id} value={d.id}>{d.name || ''}</option>
          ))}
        </datalist>
      </form>
      {routeActive && (
        <button className="route-refresh" onClick={() => setRefreshTick((t) => t + 1)} title="Recompute route">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 12a9 9 0 1 1-2.64-6.36" /><path d="M21 3v5h-5" />
          </svg>
          Refresh route
        </button>
      )}
      <Legend />
    </div>
  )
}

function trimRoute(points, lat, lng) {
  if (!points || points.length < 2) return points
  let best = 0
  let bestD = Infinity
  for (let i = 0; i < points.length; i++) {
    const dx = points[i][0] - lat
    const dy = points[i][1] - lng
    const d = dx * dx + dy * dy
    if (d < bestD) { bestD = d; best = i }
  }
  return [[lat, lng], ...points.slice(best + 1)]
}

// Toggle the .mk--selected ring on whichever rider marker is active.
function highlightSelected(markers, selectedId) {
  for (const [id, marker] of markers) {
    const el = marker.getElement()
    if (!el) continue
    const dot = el.querySelector('.mk')
    if (dot) dot.classList.toggle('mk--selected', id === selectedId)
  }
}

const driverTooltip = (d) => {
  if (!d) return ''
  const name = d.name && d.name !== d.id ? ` · ${d.name}` : ''
  return `<b>${d.id}</b>${name}<br/><span class="driver-tip__status">${d.status || ''}</span>`
}

const orderPopup = (o) => {
  const eta = o.current_eta ? new Date(o.current_eta).toLocaleTimeString() : '—'
  return `<b>${o.customer_name || o.id}</b><br/>${o.restaurant || ''}<br/>Status: ${o.status}<br/>ETA: ${eta}`
}
