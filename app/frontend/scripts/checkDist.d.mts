/** Types for checkDist.mjs, the postbuild check (P16-4). */

export declare const FORBIDDEN: readonly string[];

export declare function findForbidden(text: string): string[];

export declare function scanDist(distDir: string): string[];
