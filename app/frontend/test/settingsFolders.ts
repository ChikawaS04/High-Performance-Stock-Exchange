import { fireEvent, screen } from "@testing-library/react";

import { folderTestId } from "../src/components/ChartSettingsPopup";

/** The chart settings view's tree button for a folder path ("display/candles"). */
export function folderButton(path: string): HTMLElement {
    return screen.getByTestId(path === "indicators" ? "pc-settings-indicators-cat" : `pc-folder-${folderTestId(path)}`);
}

/** Open a folder in the chart settings view by its tree button. */
export function openFolder(path: string): void {
    fireEvent.click(folderButton(path));
}

/** The folder the view's content pane is showing. */
export function shownFolder(): string | null {
    return screen.getByTestId("pc-settings-content").getAttribute("data-folder");
}

/** The indicator folders, in tree order. */
export const INDICATOR_FOLDERS = ["indicators/price", "indicators/volume", "indicators/trading"] as const;
