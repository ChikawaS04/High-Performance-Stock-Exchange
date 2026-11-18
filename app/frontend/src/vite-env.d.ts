/// <reference types="vite/client" />

interface ImportMetaEnv {
    /** WebSocket endpoint of the Java backend (P4-7 default: ws://localhost:8080/ws). */
    readonly VITE_WS_URL?: string;
}

interface ImportMeta {
    readonly env: ImportMetaEnv;
}
