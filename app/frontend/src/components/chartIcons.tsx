/**
 * The price chart band's icons (Charts follow-up, decision 1), from the approved
 * mockup. Inline stroke SVG in currentColor, aria-hidden: the control that carries
 * an icon keeps its text label or an aria-label.
 */

/** Two candles, one filled and one hollow, each with a wick. */
export function CandleIcon() {
    return (
        <svg className="chart-icon" width="14" height="14" viewBox="0 0 14 14" aria-hidden="true" data-icon="candles">
            <line x1="4" y1="1" x2="4" y2="13" stroke="currentColor" />
            <rect x="2" y="4" width="4" height="6" fill="currentColor" />
            <line x1="10" y1="2" x2="10" y2="12" stroke="currentColor" />
            <rect x="8.5" y="5" width="3" height="4" fill="none" stroke="currentColor" />
        </svg>
    );
}

/** A polyline. */
export function LineIcon() {
    return (
        <svg className="chart-icon" width="14" height="14" viewBox="0 0 14 14" aria-hidden="true" data-icon="line">
            <polyline points="1,11 4,7 7,9 10,4 13,5" fill="none" stroke="currentColor" strokeWidth="1.4" />
        </svg>
    );
}

/** A line over small volume columns. */
export function IndicatorIcon() {
    return (
        <svg className="chart-icon" width="14" height="14" viewBox="0 0 14 14" aria-hidden="true" data-icon="indicators">
            <polyline points="1,10 4,6 7,8 13,2" fill="none" stroke="currentColor" strokeWidth="1.3" />
            <rect x="1" y="11.5" width="2" height="1.5" fill="currentColor" />
            <rect x="4.5" y="10.5" width="2" height="2.5" fill="currentColor" />
            <rect x="8" y="9.5" width="2" height="3.5" fill="currentColor" />
            <rect x="11.5" y="11" width="2" height="2" fill="currentColor" />
        </svg>
    );
}

const GEAR_PATH =
    "M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41h-3.84c-0.24,0-0.43,0.17-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22-0.08-0.47,0-0.59,0.22L2.74,8.87C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.82,11.69,4.82,12s0.02,0.64,0.07,0.94l-2.03,1.58c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.47-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96c0.22,0.08,0.47,0,0.59-0.22l1.92-3.32c0.12-0.22,0.07-0.47-0.12-0.61L19.14,12.94z M12,15.6c-1.98,0-3.6-1.62-3.6-3.6s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z";

/** The §11.1 settings gear glyph. */
export function GearIcon() {
    return (
        <svg viewBox="0 0 24 24" aria-hidden="true">
            <path fill="currentColor" d={GEAR_PATH} />
        </svg>
    );
}
