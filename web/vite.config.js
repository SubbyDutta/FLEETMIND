import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Frontend runs on :5173 and talks to the command-service on :8086.
// CORS is already open on the backend (@CrossOrigin), so no proxy is needed.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173 },
})
