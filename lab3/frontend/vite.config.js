import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Proxy API calls to Spring Boot so the browser never hits CORS.
// API_URL is set to http://api:8080 inside docker compose.
const target = process.env.API_URL || 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': target,
      '/whoami': target,
      '/actuator': target,
    },
  },
})
