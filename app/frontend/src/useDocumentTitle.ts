/**
 * Sets document.title for the mounted page (DESIGN_SYSTEM.md §15.6). Each routed
 * page calls it with its PAGE_NAMES entry; the symbol is the shared protocol
 * SYMBOL the header shows. No cleanup restore: the next page sets its own title,
 * and index.html's static "directROUTE" covers the moment before the app mounts.
 */

import { useEffect } from "react";

import { SYMBOL } from "./protocol/messages";
import { documentTitle } from "./documentTitle";
import type { PageName } from "./documentTitle";

export function useDocumentTitle(page: PageName): void {
    useEffect(() => {
        document.title = documentTitle(SYMBOL, page);
    }, [page]);
}
