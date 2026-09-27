/// <reference types="vite/client" />

interface ImportMetaEnv {
    /** WebSocket endpoint of the Java backend (P4-7 default: ws://localhost:8080/ws). */
    readonly VITE_WS_URL?: string;
    /** Instrument symbol for the P11 ignition-price fetch (default: ASML). */
    readonly VITE_IGNITION_SYMBOL?: string;
}

interface ImportMeta {
    readonly env: ImportMetaEnv;
}
