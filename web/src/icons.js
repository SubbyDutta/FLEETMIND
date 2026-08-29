import L from 'leaflet'
import { PLACES } from './theme'

// Map markers as Leaflet divIcons drawn from inline SVG glyphs (Lucide-style).
// No image files to bundle, scalable, and colored from the theme tokens.
// The visual look (size, ring, glow) lives in styles.css under .mk.

const GLYPH = {
  // bicycle / rider
  rider: '<circle cx="18.5" cy="17.5" r="3.5"/><circle cx="5.5" cy="17.5" r="3.5"/><circle cx="15" cy="5" r="1"/><path d="M12 17.5V14l-3-3 4-3 2 3h2"/>',
  // fork + knife
  food: '<path d="M5 2v6a2 2 0 0 0 2 2 2 2 0 0 0 2-2V2"/><path d="M7 10v12"/><path d="M17 2c1.5 1.2 2.5 3.5 2.5 6S18.5 13.2 17 14v8"/>',
  // house
  home: '<path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M9 22V12h6v10"/>',
}

const svg = (glyph) =>
  `<svg viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${glyph}</svg>`

const badge = (kind, glyph, color, size) =>
  L.divIcon({
    className: 'mk-wrap',
    html: `<div class="mk mk--${kind}" style="--c:${color}">${svg(glyph)}</div>`,
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2],
  })

export const riderIcon = (color) => badge('rider', GLYPH.rider, color, 34)
export const foodIcon = () => badge('place', GLYPH.food, PLACES.restaurant.color, 28)
export const homeIcon = () => badge('place', GLYPH.home, PLACES.dropoff.color, 28)
