/**
 * Who sent an order, by its id. Pure, so it is unit tested without a DOM.
 *
 * The venue uses each client's ClOrdID as the order id, and the simulation bots
 * (backend `participant/Bots.java`) draw theirs from disjoint blocks: bot k owns
 * [k * 1e14, (k + 1) * 1e14). Every bot id is below 6e14, well under 2^53, so it is
 * exact as a JavaScript number. This table mirrors Bots.java; the two must stay in
 * step. This terminal seeds its own ids from Date.now() (about 1.8e12), below the
 * first block, so its ids never look like a bot's.
 *
 * "YOU" is decided by the caller (an id this terminal sent), never from the id
 * alone: another terminal's ids look exactly like ours.
 */

export type BotOwner = "MAKER" | "TREND" | "REVERT" | "NOISE" | "INST";
export type Owner = "YOU" | BotOwner | "OTHER";

/** Width of one bot's id block. */
export const BOT_BLOCK = 100_000_000_000_000;

/** Bots in block order: index i owns block i + 1. */
export const BOT_OWNERS: readonly BotOwner[] = ["MAKER", "TREND", "REVERT", "NOISE", "INST"];

/** Display label per owner, all caps (DESIGN_SYSTEM.md §3). */
export const OWNER_LABELS: Readonly<Record<Owner, string>> = {
    YOU: "YOU",
    MAKER: "MAKER",
    TREND: "TREND",
    REVERT: "REVERT",
    NOISE: "NOISE",
    INST: "INST",
    OTHER: "OTHER",
};

/** The bot that owns `id`, or null for an id outside every bot block. */
export function botOwnerOf(id: number): BotOwner | null {
    if (!Number.isSafeInteger(id) || id < BOT_BLOCK) return null;
    const block = Math.floor(id / BOT_BLOCK);
    return BOT_OWNERS[block - 1] ?? null;
}

/** YOU when `mine`, else the owning bot, else OTHER. */
export function ownerOf(id: number, mine: boolean): Owner {
    if (mine) return "YOU";
    return botOwnerOf(id) ?? "OTHER";
}
