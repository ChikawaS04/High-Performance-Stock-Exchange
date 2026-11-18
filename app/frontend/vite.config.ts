import { defineConfig } from 'vitest/config'
import { loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

/** Where the dev server forwards the venue's paths unless EXCHANGE_BACKEND_URL says otherwise. */
const DEFAULT_BACKEND_URL = 'http://localhost:8080'

/** EXCHANGE_BACKEND_URL as an http(s) origin; anything else stops the dev server with a message. */
function backendUrl(value: string | undefined): string {
    const raw = value === undefined || value.trim() === '' ? DEFAULT_BACKEND_URL : value.trim()
    let url: URL
    try {
        url = new URL(raw)
    } catch {
        throw new Error(`EXCHANGE_BACKEND_URL is not a URL: ${raw}`)
    }
    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
        throw new Error(`EXCHANGE_BACKEND_URL must be an http or https URL: ${raw}`)
    }
    return url.origin
}

// Development mirrors production (P16-4, D10): the terminal talks only to same-origin paths, and the
// dev server forwards /ws (WebSocket upgrades included), /api, /health and /market to the venue, the
// way Caddy does in production. Only EXCHANGE_-prefixed variables are loaded (from the shell or
// .env.local); none of them is VITE_-prefixed, so none reaches the client bundle. No credential is
// loaded here: the venue fetches the ignition price itself (P16-3).
export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, process.cwd(), 'EXCHANGE_')
    const target = backendUrl(env.EXCHANGE_BACKEND_URL)
    return {
        plugins: [react()],
        server: {
            port: 5173,
            proxy: {
                '/ws': { target, ws: true },
                '/api': { target },
                '/health': { target },
                '/market': { target },
            },
        },
        test: {
            environment: 'jsdom',
            include: ['test/**/*.test.{ts,tsx}'],
        },
    }
})
