import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// No credential is loaded here (P16-3): the venue fetches the ignition price from Alpaca itself and
// serves it at GET /market/ignition, so the dev server holds no keys and proxies nothing to Alpaca.
// It only forwards /market to the local venue.
export default defineConfig({
    plugins: [react()],
    server: {
        port: 5173,
        proxy: {
            '/market': { target: 'http://localhost:8080' },
        },
    },
    test: {
        environment: 'jsdom',
        include: ['test/**/*.test.{ts,tsx}'],
    },
})
