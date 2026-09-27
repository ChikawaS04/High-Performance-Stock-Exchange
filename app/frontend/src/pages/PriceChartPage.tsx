/**
 * Price chart page (P10-4 scaffold, P12-3 fill).
 *
 * A two-region layout, the chart region (left) and the side-panel region (right).
 * P12-3 fills the chart region with <PriceChart>, fed from the shared reducer
 * state: state.tape is the session's trade-print series, and state.sessionOpenCents
 * is the engine's first-trade anchor (the same value the header Chg uses). The
 * optional openCents is the Alpaca market open (P11), threaded from App and drawn
 * only when it falls inside the plotted price domain. The side-panel region stays a
 * placeholder for P13; P12 touches only the chart region.
 */

import type { AppState } from "../state/reducer";
import { PriceChart } from "../components/PriceChart";

export interface PriceChartPageProps {
    readonly state: AppState;
    readonly openCents?: number;
}

export function PriceChartPage({ state, openCents }: PriceChartPageProps) {
    return (
        <main className="chart-page" aria-label="Price chart">
            <section className="chart-page__region chart-page__region--chart" aria-label="Chart">
                <h2 className="panel__title">Price chart</h2>
                <PriceChart
                    tape={state.tape}
                    sessionOpenCents={state.sessionOpenCents}
                    openCents={openCents}
                />
            </section>

            <section className="chart-page__region chart-page__region--panel" aria-label="Side panel">
                <h2 className="panel__title">Session</h2>
                <div className="chart-page__placeholder">Side panel arrives in P13.</div>
            </section>
        </main>
    );
}
