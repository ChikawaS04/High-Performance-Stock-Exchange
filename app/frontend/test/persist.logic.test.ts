import { afterEach, describe, expect, it, vi } from "vitest";

import {
    LEGACY_HEADER_LABEL_KEY,
    SETTING_KEYS,
    isBoolean,
    migrateLegacySettings,
    oneOf,
    purgeStaleSettings,
    readSetting,
    removeSetting,
    settingKey,
    writeSetting,
} from "../src/settings/persist";

afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
});

const KEY = settingKey("tape", "levels");
const isLevel = oneOf(["all", "gt200", "gt500"] as const);

describe("settingKey (D10)", () => {
    it("namespaces and versions every key", () => {
        expect(KEY).toBe("hpse.v1.tape.levels");
        for (const k of Object.values(SETTING_KEYS)) {
            expect(k).toMatch(/^hpse\.v1\.[A-Za-z]+\.[A-Za-z]+$/);
        }
    });
});

describe("readSetting / writeSetting (D10)", () => {
    it("round-trips a value as JSON", () => {
        expect(writeSetting(KEY, "gt500")).toBe(true);
        expect(localStorage.getItem(KEY)).toBe('"gt500"');
        expect(readSetting(KEY, "all", isLevel)).toBe("gt500");

        writeSetting("hpse.v1.x.flag", false);
        expect(readSetting("hpse.v1.x.flag", true, isBoolean)).toBe(false);
        writeSetting("hpse.v1.x.depth", 14);
        expect(readSetting("hpse.v1.x.depth", 10, oneOf([8, 10, 14]))).toBe(14);
    });

    it("returns the default when nothing is stored", () => {
        expect(readSetting(KEY, "all", isLevel)).toBe("all");
    });

    it("falls back and removes an invalid value", () => {
        localStorage.setItem(KEY, '"gt9000"');
        expect(readSetting(KEY, "all", isLevel)).toBe("all");
        expect(localStorage.getItem(KEY)).toBeNull();
    });

    it("falls back and removes unparsable JSON", () => {
        localStorage.setItem(KEY, "{not json");
        expect(readSetting(KEY, "all", isLevel)).toBe("all");
        expect(localStorage.getItem(KEY)).toBeNull();
    });

    it("uses the default when localStorage throws on read", () => {
        vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
            throw new Error("SecurityError");
        });
        expect(readSetting(KEY, "all", isLevel)).toBe("all");
    });

    it("reports failure, without throwing, when localStorage throws on write", () => {
        vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
            throw new Error("QuotaExceededError");
        });
        expect(writeSetting(KEY, "gt200")).toBe(false);
    });

    it("never throws when removal fails", () => {
        vi.spyOn(Storage.prototype, "removeItem").mockImplementation(() => {
            throw new Error("SecurityError");
        });
        expect(() => removeSetting(KEY)).not.toThrow();
        localStorage.setItem(KEY, "{bad");
        expect(readSetting(KEY, "all", isLevel)).toBe("all");
    });
});

describe("purgeStaleSettings (D10 unknown version)", () => {
    it("removes namespaced keys from any other version and keeps everything else", () => {
        localStorage.setItem("hpse.v0.tape.levels", '"gt200"');
        localStorage.setItem("hpse.v2.tape.levels", '"gt200"');
        localStorage.setItem(KEY, '"gt500"');
        localStorage.setItem("someone.else", "x");

        expect(purgeStaleSettings()).toBe(2);
        expect(localStorage.getItem("hpse.v0.tape.levels")).toBeNull();
        expect(localStorage.getItem("hpse.v2.tape.levels")).toBeNull();
        expect(localStorage.getItem(KEY)).toBe('"gt500"');
        expect(localStorage.getItem("someone.else")).toBe("x");
    });

    it("returns 0 when storage is unavailable", () => {
        vi.spyOn(Storage.prototype, "key").mockImplementation(() => {
            throw new Error("SecurityError");
        });
        localStorage.setItem("hpse.v0.a.b", "1");
        expect(purgeStaleSettings()).toBe(0);
    });
});

describe("migrateLegacySettings", () => {
    it("moves a valid legacy header label value into the namespace", () => {
        localStorage.setItem(LEGACY_HEADER_LABEL_KEY, "faint");
        migrateLegacySettings();
        expect(localStorage.getItem(SETTING_KEYS.headerLabelMode)).toBe('"faint"');
        expect(localStorage.getItem(LEGACY_HEADER_LABEL_KEY)).toBeNull();
    });

    it("drops an invalid legacy value and never overwrites a namespaced one", () => {
        localStorage.setItem(LEGACY_HEADER_LABEL_KEY, "purple");
        migrateLegacySettings();
        expect(localStorage.getItem(SETTING_KEYS.headerLabelMode)).toBeNull();
        expect(localStorage.getItem(LEGACY_HEADER_LABEL_KEY)).toBeNull();

        localStorage.setItem(SETTING_KEYS.headerLabelMode, '"white"');
        localStorage.setItem(LEGACY_HEADER_LABEL_KEY, "faint");
        migrateLegacySettings();
        expect(localStorage.getItem(SETTING_KEYS.headerLabelMode)).toBe('"white"');
    });
});
