import { defineConfig } from 'vitest/config'
import { loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
    // Empty prefix loads ALL vars (including non-VITE_ ones) into the node config
    // process only. The Alpaca credentials are used solely to build the dev-proxy
    // headers below and never reach the client bundle (P11 D2).
    const env = loadEnv(mode, process.cwd(), '')
    return {
        plugins: [react()],
        server: {
            port: 5173,
            proxy: {
                // The frontend fetches a same-origin relative path (/alpaca/...), so
                // browser CORS never arises; the dev server forwards it to Alpaca with
                // the auth headers injected here, server-side. Dev-only: a static
                // `vite build` has no proxy (see FrontendREADME known limitations).
                '/alpaca': {
                    target: 'https://data.alpaca.markets',
                    changeOrigin: true,
                    rewrite: (p) => p.replace(/^\/alpaca/, ''),
                    headers: {
                        'APCA-API-KEY-ID': env.ALPACA_KEY_ID ?? '',
                        'APCA-API-SECRET-KEY': env.ALPACA_SECRET_KEY ?? '',
                    },
                },
            },
        },
        test: {
            environment: 'jsdom',
            include: ['test/**/*.test.{ts,tsx}'],
        },
    }
})
