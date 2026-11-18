/**
 * Postbuild check (P16-4, D10): fails the build if dist/ contains a hard-coded backend host or any
 * Alpaca credential name. The terminal must talk only to same-origin paths, and no key may ever
 * reach the bundle.
 *
 * Every file under dist/ is scanned, read as latin1 so binary assets scan safely. Runs as the
 * `postbuild` npm script, so `npm run build` fails on a hit. Plain JavaScript, so it runs on any
 * supported Node without a TypeScript loader.
 */

import { readdirSync, readFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

/** Strings that must never appear in a built terminal. */
export const FORBIDDEN = ["localhost:8080", "ALPACA_", "APCA-"];

/** The forbidden strings found in a file's text, in FORBIDDEN order. */
export function findForbidden(text) {
    return FORBIDDEN.filter((needle) => text.includes(needle));
}

function* files(dir) {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const path = join(dir, entry.name);
        if (entry.isDirectory()) yield* files(path);
        else if (entry.isFile()) yield path;
    }
}

/** Every hit under distDir as "file: string" lines. */
export function scanDist(distDir) {
    const hits = [];
    for (const path of files(distDir)) {
        for (const needle of findForbidden(readFileSync(path, "latin1"))) {
            hits.push(`${relative(distDir, path)}: ${needle}`);
        }
    }
    return hits;
}

const invokedDirectly = process.argv[1] !== undefined && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;

if (invokedDirectly) {
    const distDir = resolve(fileURLToPath(new URL("..", import.meta.url)), "dist");
    const hits = scanDist(distDir);
    if (hits.length > 0) {
        console.error("dist/ check failed: the build contains a backend host or an Alpaca key name:");
        for (const hit of hits) console.error(`  ${hit}`);
        process.exit(1);
    }
    console.log("dist/ check passed: no localhost:8080, ALPACA_ or APCA- in the build");
}
