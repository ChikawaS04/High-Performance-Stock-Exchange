import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";

import { FieldMenu } from "../src/components/FieldMenu";
import type { MenuOption } from "../src/components/FieldMenu";

// No vitest globals, so RTL's auto-cleanup never registers. Wire it explicitly.
afterEach(cleanup);

const OPTIONS: readonly MenuOption[] = [
    { value: "A", label: "ALPHA" },
    { value: "B", label: "BRAVO" },
];

describe("FieldMenu single-select (button trigger)", () => {
    it("is closed until the trigger is clicked, then lists every option", () => {
        render(<FieldMenu testId="fm" ariaLabel="Pick" options={OPTIONS} value="A" onSelect={() => {}} />);
        expect(screen.queryByRole("listbox")).toBeNull();
        expect(screen.getByTestId("fm-trigger").getAttribute("aria-expanded")).toBe("false");

        fireEvent.click(screen.getByTestId("fm-trigger"));
        expect(screen.getByRole("listbox", { name: "Pick options" })).not.toBeNull();
        expect(screen.getAllByRole("option")).toHaveLength(2);
        expect(screen.getByTestId("fm-trigger").getAttribute("aria-expanded")).toBe("true");
    });

    it("selects on mouse down and closes", () => {
        const onSelect = vi.fn();
        render(<FieldMenu testId="fm" ariaLabel="Pick" options={OPTIONS} value="A" onSelect={onSelect} />);
        fireEvent.click(screen.getByTestId("fm-trigger"));
        fireEvent.mouseDown(screen.getByTestId("fm-opt-B"));
        expect(onSelect).toHaveBeenCalledTimes(1);
        expect(onSelect).toHaveBeenCalledWith("B");
        expect(screen.queryByRole("listbox")).toBeNull();
    });

    it("marks the current value aria-selected", () => {
        render(<FieldMenu testId="fm" ariaLabel="Pick" options={OPTIONS} value="B" onSelect={() => {}} />);
        fireEvent.click(screen.getByTestId("fm-trigger"));
        expect(screen.getByTestId("fm-opt-A").getAttribute("aria-selected")).toBe("false");
        expect(screen.getByTestId("fm-opt-B").getAttribute("aria-selected")).toBe("true");
    });

    it("closes on Escape and on blur without selecting", () => {
        const onSelect = vi.fn();
        render(<FieldMenu testId="fm" ariaLabel="Pick" options={OPTIONS} value="A" onSelect={onSelect} />);
        const trigger = screen.getByTestId("fm-trigger");

        fireEvent.click(trigger);
        fireEvent.keyDown(trigger, { key: "Escape" });
        expect(screen.queryByRole("listbox")).toBeNull();

        fireEvent.click(trigger);
        fireEvent.blur(trigger);
        expect(screen.queryByRole("listbox")).toBeNull();
        expect(onSelect).not.toHaveBeenCalled();
    });

    it("shows the trigger label, carries the shared caret class, and never opens when disabled", () => {
        const { container } = render(
            <FieldMenu
                testId="fm"
                ariaLabel="Pick"
                options={OPTIONS}
                value="A"
                triggerLabel="ALPHA"
                disabled
                onSelect={() => {}}
            />,
        );
        expect(screen.getByTestId("fm-trigger").textContent).toBe("ALPHA");
        expect(container.querySelector(".field-menu__caret.term-caret")).not.toBeNull();
        fireEvent.click(screen.getByTestId("fm-trigger"));
        expect(screen.queryByRole("listbox")).toBeNull();
    });

    it("uses only the neutral field-menu classes, never the order-entry scope", () => {
        const { container } = render(
            <FieldMenu testId="fm" ariaLabel="Pick" options={OPTIONS} value="A" className="x-extra" onSelect={() => {}} />,
        );
        fireEvent.click(screen.getByTestId("fm-trigger"));
        expect(container.querySelector(".field-menu.x-extra")).not.toBeNull();
        expect(container.querySelector('[class*="order-entry__"]')).toBeNull();
    });
});

describe("FieldMenu editable (combobox)", () => {
    function Harness() {
        const [text, setText] = useState("");
        return (
            <FieldMenu
                testId="qty"
                ariaLabel="Quantity"
                options={[{ value: "100", label: "100" }]}
                value={text}
                editable
                numeric
                onSelect={setText}
                onInput={setText}
            />
        );
    }

    it("opens on focus and accepts typing", () => {
        render(<Harness />);
        const input = screen.getByTestId("qty-input") as HTMLInputElement;
        fireEvent.focus(input);
        expect(screen.getByRole("listbox")).not.toBeNull();
        fireEvent.change(input, { target: { value: "37" } });
        expect(input.value).toBe("37");
    });

    it("fills the input from an option, and the numeric option is styled numeric", () => {
        render(<Harness />);
        const input = screen.getByTestId("qty-input") as HTMLInputElement;
        fireEvent.click(input);
        const opt = screen.getByTestId("qty-opt-100");
        expect(opt.className).toContain("field-menu__opt--num");
        fireEvent.mouseDown(opt);
        expect(input.value).toBe("100");
        expect(screen.queryByRole("listbox")).toBeNull();
    });
});
