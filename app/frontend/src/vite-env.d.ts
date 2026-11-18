/// <reference types="vite/client" />

// The one declaration of the terminal's VITE_ variables (P16-4).
interface ImportMetaEnv {
    /**
     * Optional override of the WebSocket endpoint. Unset by default: the terminal uses the same-origin
     * /ws (P16-4). A relative value is resolved against the page; an absolute ws:// or wss:// one is used as is.
     */
    readonly VITE_WS_URL?: string;
}

interface ImportMeta {
    readonly env: ImportMetaEnv;
}
