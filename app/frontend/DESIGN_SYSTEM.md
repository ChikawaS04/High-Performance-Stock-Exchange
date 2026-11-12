# Terminal Design System — TWS Charcoal

The visual blueprint for the High Performance Stock Exchange front end. Every panel
we build or rework from here follows this document. It was established during the
order-entry rework and is the reference the rest of the terminal is brought in line
with, panel by panel. The terminal's brand mark (directROUTE), its title bar, the
favicon and the browser tab title are specified in §15.

---

## 1. Thesis

Model the terminal on **Interactive Brokers TWS**: a dense, instrument-like trading
surface, not a flat web form. The single biggest rule, and the thing the old build
got wrong, is **depth**. Controls are physical: input areas are sunken wells, action
controls are raised with a bevel and a top glint, and sections are separated by
hairline rules. Flat fills on a single surface read as "web app" and are the look we
are moving away from.

Supporting principles:

- **Dense and quiet.** Small type, tight spacing, tabular numbers that lock into
  columns. Information, not decoration.
- **Charcoal, neutral.** A true dark grey base with no blue/green tint. Panels are a
  two-tone: a dark-grey header band over a near-black body.
- **Semantic colour only.** Colour means something (side, bid/ask, P&L). It is never
  used as ornament.
- **Squared off.** Minimal corner rounding, like a native desktop app.

---

## 2. Colour

Current values are defined as the `--oe-*` tokens scoped to the order-entry ticket.
As each panel is reworked, these get promoted to terminal-wide tokens on `:root`
and the old blue-grey `--surface` palette is retired.

### Charcoal neutrals
| Token | Hex | Use |
|---|---|---|
| `--body` | `#1b1b1b` | Panel body / working surface |
| `--head` | `#2c2c2c` | Panel header band (dark grey) |
| `--well` | `#0d0d0d` | Sunken input and quote wells |
| `--raise-top` | `#343434` | Raised control, top of bevel |
| `--raise-bot` | `#262626` | Raised control, bottom of bevel |
| `--line` | `#333333` | Hairline divider / border |
| `--line-strong` | `#444444` | Stronger edge on raised controls |
| `--text` | `#d8d8d8` | Primary text |
| `--dim` | `#8f8f8f` | Secondary / inactive labels |
| `--faint` | `#5e5e5e` | Placeholders, muted labels |
| label white | `#ffffff` | Field labels (TIF, TYPE, PRICE, QTY, DISPLAY) and panel title |

**Token names in code.** In `terminal.css` the charcoal hairlines above are
`--hair` (`#333`) and `--hair-strong` (`#444`); the names `--line` and
`--line-strong` there still hold the legacy slate values until the slate palette is
retired (§7). New charcoal work uses `--hair` / `--hair-strong`. There is no global
`--dim` yet; rules use `var(--dim, #8f8f8f)`.

### Semantic colour
| Token | Hex | Use |
|---|---|---|
| `--buy` | `#0000ff` | BUY segment and Submit-BUY fill |
| `--sell` | `#ff0000` | SELL segment and Submit-SELL fill |
| `--bid` | `#5a96ff` | Bid quote value (legible blue on black) |
| `--ask` | `#ff6a5a` | Ask quote value (legible red on black) |
| `--focus` | `#5aa6ff` | Focus ring |

**Convention:** buy/bid read blue, sell/ask read red. The action buttons use pure
`#0000ff` / `#ff0000`; quote text uses the lighter, legible variants so prices stay
readable on the charcoal.

**Bid is blue terminal-wide.** Every bid value, bid curve and BUY cell uses the bid
blue (`--quote-bid`, `#5a96ff`, the `--bid` value above); the header BID moved in
the UI polish pass, and the blue-or-green question is closed. Green and red remain
only as direction colours (a tape tick up or down, a positive or negative change),
never for a bid or an ask. The legacy slate `--bid` token in `terminal.css` is still
green and still feeds the header's "up" change colour, so a bid must never use it.

### Table, popup and control tokens
| Token | Value | Use |
|---|---|---|
| `--zebra` | `#262626` | Alternate table row on the `--well` (§9) |
| `--row-hover` | `#303030` | Table row hover, always lighter than the stripe (§9) |
| `--pop-head` | `#333333` | Popup header band (§10) |
| `--pop-body` | `#434343` | Popup body and toast ground (§10, §12) |
| `--exit-x` | `#af0000` | The popup exit mark (§10) |
| `--silver-top` / `--silver-bot` / `--silver-edge` | `#d6d6d6` / `#9e9e9e` / `#7d7d7d` | Silver primary action (§10) |
| `--quote-bid-wash` / `--quote-ask-wash` | 16% bid / ask | Side cell wash, translucent so the stripe shows through (§9) |
| `--caret-size` | `20px` | Every dropdown caret (§11.6) |

### Chart tokens (§16)
| Token | Black ground | White ground | Use |
|---|---|---|---|
| `--candle-up` | `#00ff00` | `#1f9d3a` | Up candle (close >= open), its volume column, and the candle-mode last-price level and tag |
| `--candle-down` | `#ff0000` | `#d40000` | Down candle, its volume column, and the candle-mode last-price level and tag |
| `--vwap` | `#b4a7d6` | `#6a5a9a` | Session VWAP line and its axis tag |

The black-ground values are the trade tape's `--tick-up` / `--tick-down`, so direction
reads as one colour family across the terminal. `#00ff00` cannot be read on white, so
the white chart ground overrides all three tokens under `.price-chart--white` (the
same per-ground pattern as `.depth-overlay__body--light`). **Candles use direction
colours and are never blue:** blue stays reserved for bid and side. The VWAP violet
is distinct from the amber price line, the candle colours, blue and red, and is
neither a §2 semantic colour nor the brand amber.

### Brand accent (§15)
| Token | Hex | Use |
|---|---|---|
| `--brand-amber` | `#ffa028` | directROUTE arrow, the icon glyph and the favicon only |
| `--brand-amber-light` | `#c87a14` | The same arrow on light grounds |
| `--titlebar-h` | `24px` | Height of the title bar (§15.3) |

Amber is never used for data, sides or UI state inside panels. Two other ambers in
the terminal are not the brand amber and must not be pointed at `--brand-amber`: the
`--warn` dot on a reconnecting badge, and the price chart's default line colour
"Amber" (`#ffb01f`), which is one of the chart's user-selectable display colours
(§13), not a brand use.

---

## 3. Typography

- **UI / labels:** `IBM Plex Sans`.
- **Numbers:** `IBM Plex Mono` with `font-variant-numeric: tabular-nums` everywhere
  digits line up (prices, sizes, quotes).
- **Field labels** are uppercase, bright white (`#ffffff`), ~9px, letter-spacing
  ~0.08em.
- **Panel title** (header band) is uppercase, ~10px, weight 600, letter-spacing
  ~0.14em.
- **Quote cell labels** (BID/MID/ASK) are tiny (~7px), uppercase, `--faint`.
- Dropdown option text that names an order type is **all caps** (`LIMIT`,
  `MIDPOINT PEG`).
- **Brand mark** is set entirely in `IBM Plex Mono` (§15), never in Plex Sans.

---

## 4. Depth and materials

This is the heart of the look. Apply it consistently.

- **Sunken wells** (text inputs, the quote strip): `background: var(--well)` with an
  inset shadow, e.g. `box-shadow: inset 0 1px 2px rgba(0,0,0,.55)` (use `3px` blur
  for larger wells like the quote strip). 1px `--line` border.
- **Raised controls** (segmented buttons, steppers, presets, submit):
  `background: linear-gradient(var(--raise-top), var(--raise-bot))`, 1px
  `--line-strong` border, and a **top glint**:
  `box-shadow: inset 0 1px 0 rgba(255,255,255,.35)`. On press, invert the gradient.
- **Active side / filled buttons:** gradient from a lighter tint to the pure colour
  (e.g. `linear-gradient(#4a4aff, var(--buy))`), coloured border, keep the glint, add
  a soft outer glow (`0 0 10px rgba(0,0,255,.45)`).
- **Hairline dividers:** 1px `--line` between sections and between cells in a strip.
- **Radius:** `3px` on controls, `4px` on the panel card. Nothing more rounded.

---

## 5. Component patterns

- **Panel = card.** Each panel is a self-contained card: a dark-grey header band
  (`--head`, bright-white uppercase title, bottom hairline) over a charcoal body
  (`--body`), 1px `--line` border, `4px` radius, `overflow: hidden`. The panel owns
  its header; it is not a loose heading above the content.
- **Dropdowns are white.** White background, black text, 1px `#555` border, grey
  (`#ccc`) hover. They are the one deliberately light element, so they read as
  editable pickers against the dark. Every dropdown is the shared `FieldMenu`
  (§11.5).
- **Text inputs** are sunken wells, right-aligned, mono tabular figures.
- **Segmented buttons** (BUY/SELL) are adjoined (shared border, no gap between), the
  active segment filled with its semantic colour.
- **Steppers** (price nudge) are a stacked up/down raised control on the right edge
  of the field, inside the field's border.
- **Quick-fill chips / presets** are small raised buttons.
- **Primary action (Submit)** carries the side colour (blue for buy, red for sell)
  and uses a **fixed width** so BUY and SELL render identically regardless of label
  length.
- **Reference strip (bid/mid/ask)** is a single sunken well split into equal cells by
  hairlines; values coloured by meaning, labels tiny and faint.

---

## 6. Layout and responsiveness

- **Anchored actions.** Side (BUY/SELL) stays top-left; Submit stays bottom-left.
  These never move or centre.
- **Centre on wide, pack on narrow.** The quote strip and the control row centre when
  the panel is wide and left-align, compact, when it is narrow. This is driven by a
  **container query on the panel width** (`container-type: inline-size`, breakpoint
  `~700px`), so it reacts to the actual panel size on each monitor rather than the
  viewport. Narrow panels stay thin to leave room for the panels stacked below.
- **One line.** Related controls stay on a single row; fields are sized small enough
  to fit rather than wrapping. Grouped fields (e.g. Qty + its optional Display) are
  wrapped together so they never split across lines.
- **Fixed, centred strips.** Wide elements like the quote strip take a fixed width
  (e.g. 340px) and centre, rather than stretching across a wide panel.
- Right-align numeric inputs; use tabular figures so columns lock.

---

## 7. Rollout

1. **Order entry — done.** The reference implementation. Tokens currently scoped as
   `--oe-*` in `src/styles/terminal.css`.
2. **Next panels** (depth ladder, trade tape, open-orders blotter, session stats,
   header) are brought in one at a time.
3. During rollout, promote `--oe-*` to terminal-wide `:root` tokens, retire the
   legacy blue-grey `--surface` palette and the slate-retheme layer panel by panel.
   The blue-vs-green bid question is settled: blue, terminal-wide (§2).
4. **Brand chrome (§15).** Add the title bar (lockup only, no icon) above the
   instrument strip, move the Live badge into it, add the favicon, and set the tab
   title. Decided Nov 2026
   (option B "Anchor title bar" on the mockup canvas
   https://claude.ai/artifact/RMidBYS7BX8dpr1tLJxju7).

---

## 8. Implementation notes

- Tokens live in `src/styles/terminal.css`. Keep new work on tokens, not literals, so
  a colour or metric changes in one place.
- When reworking a panel, give it the card chrome in the component (its own header
  band + body) so it looks identical regardless of which page mounts it.
- Watch the slate-retheme override layer at the foot of `terminal.css`: it can
  re-skin elements after the base rules. Remove a panel's rules from it as you rework
  that panel.
- Preserve behaviour and test ids when restyling; the rework is visual only.

---

## 9. Table panels (LOCKED)

Settled on the open-orders blotter. **Every panel that renders a table of rows uses
this exact colour scheme**, so tables read as one family across the terminal.

- **Panel header band:** `--head` (`#2c2c2c`). Same as every panel, globally. The
  panel header carries no status counters or badges. It holds the title, an optional
  menu caret at the right edge, and at most **one filter single-select** (§11.5,
  compact) just left of the caret (UI polish D6; the blotter's status filter).
- **Column-header row** (the `thead`): sits on the **panel body** colour `--body`
  (`#1b1b1b`), with a 1px `--line` bottom hairline. Labels are uppercase, bright
  white `#ffffff`, **weight 600**, ~9px, letter-spacing ~0.08em. Sticky at the top
  of the scroll.
- **Table body:** the sunken **well** colour `--well` (`#0d0d0d`), with the well's
  inset shadow. The body fills the panel all the way down; empty space below the rows
  is well, not a separate surface.
- **Zebra striping:** alternate rows take `--zebra` (`#262626`), a stripe that reads
  clearly on the well (UI polish D9).
- **Row data text:** bright white `#ffffff`, **normal weight** (not bold like the
  column labels). Numbers, IDs, and timestamps are `IBM Plex Mono`, tabular, numeric
  columns right-aligned.
- **Side cell (BUY / SELL):** the one coloured data cell, per the bid/ask convention
  (§2): BUY reads blue (`--quote-bid` `#5a96ff`), SELL red (`--quote-ask` `#ff6a5a`),
  each on a translucent wash of the same hue (`--quote-bid-wash` / `--quote-ask-wash`).
  Zebra and row hover sit on the row, not the cell, so the stripe shows through the
  tinted Side cell rather than flattening it. The BUY / SELL label is **`IBM Plex
  Sans`, ~11px, NORMAL weight (400)**, not bold. Looking dense is the colour-on-wash,
  not the weight. This is the one-and-only Side treatment; any panel with a side
  column (blotter, tape) copies it exactly, weight included.
- **Row hover** is a charcoal lift, `--row-hover` (`#303030`), sitting on the row,
  always lighter than the stripe and never a blue tint. No blue row markers or
  selection strips anywhere on a table body (see §2). No table uses a literal stripe
  or hover colour.
- **Per-row actions** (e.g. a row Cancel button) are small raised charcoal controls:
  `linear-gradient(var(--raise-top), var(--raise-bot))`, 1px `--line-strong` border,
  3px radius, a subtle top glint, `--text` label at normal weight.
- **Panel-level menu** (e.g. the blotter's cancel menu): a caret in the header band,
  right-aligned, that blends into `--head`; only the `▾` glyph contrasts (`--text`).
  The caret is the standard size (§11.6). It opens the standard white dropdown
  (§11.5) downward. The option list is all caps where it names an action
  (`CANCEL BY ID`). Where a panel instead needs view options (toggles, level
  pickers), use the §11 settings gear, not a caret menu; the one exception is the
  single filter single-select above.
- **Empty states** are one dim line in the body: "No orders yet" with no rows at
  all, and a filter-specific line (for example "No filled orders") when a filter
  matches nothing.

---

## 10. Popups (LOCKED, global blueprint)

Settled on the blotter's "Cancel by Order ID" dialog, modelled on the TWS order-
confirmation window. **Every popup across the terminal looks like this.**

- **Two-tone, lighter than the panels.** Header `--pop-head` (`#333333`) over body
  `--pop-body` (`#434343`). The **header is darker than the body** (the inverse of a
  panel card), and both are lighter shades than the charcoal panels. Modal popups sit
  on the `--scrim`.
- **Light-grey outline.** A 1px light-grey border (`#808080`) around the whole modal,
  so it reads as a raised window lifted off the dark terminal.
- **Thin black divider.** A 1px black (`#000000`) rule separates the header band from
  the body.
- **Sharp edges everywhere.** Radius `0` on the modal, its header, the exit button,
  and all input fields. The **one exception is the primary action button**, which
  keeps its `3px` radius.
- **Exit button, top-right.** Background is the header colour so the button itself
  blends in; the only mark is a large `×` in `--exit-x` (`#af0000`), brightening on
  hover.
- **All popup text** is `#ffffff` at **normal weight** (including the title).
- **Input fields** use the universal well colour `--well` (`#0d0d0d`), sunken, with
  the inset shadow and a 1px `--line` border; mono tabular text.
- **Primary action button** (e.g. "Confirm Cancel"): the **silver control**,
  `linear-gradient(var(--silver-top), var(--silver-bot))` running **lighter grey at
  the top to darker grey at the bottom**, 1px `--silver-edge` border, bold black
  uppercase label (same weight as the order-entry Submit / BUY / SELL buttons), 3px
  radius, and a light lift shadow: `inset 0 1px 0 rgba(255,255,255,.55), 0 2px 8px
  rgba(0,0,0,.5)`. Keep it small, at the bottom right; do not stretch it full width.

### Confirmation popups (UI polish D5)

An action that cannot be undone is confirmed in a popup that states exactly what it
will do.

- **The action happens only on a click of the primary action.** Escape, the `×` and
  a click outside the popup close it without acting.
- **Enter never confirms**, in every confirmation popup, so a keystroke can never
  cancel an order. This includes the text field of Cancel by Order ID.
- **Cancel order** (the blotter row Cancel) shows the order ID, side, remaining
  quantity, price and type, with **Confirm Cancel** as the primary action. If the
  order leaves the active set while the popup is open, the popup says "Order is no
  longer active" and Confirm Cancel is disabled.
- **Cancel by Order ID** takes a typed id and confirms with the same Confirm Cancel.
- **Order Confirmation** (the ticket) shows the resolved order before it is sent.
- A bulk cancel, if one is added, states how many orders it will cancel.

---

## 11. Shared controls (LOCKED, terminal-wide standards)

Settled on the terminal header's Settings mechanism. These controls have **one
canonical look, used everywhere in the terminal regardless of panel.** When a panel
needs a settings entry point, a single-choice picker, a two-state switch, or a
dropdown, it uses these: same markup, same metrics, same tokens. Do not invent a
per-panel variant. The reusable classes live at the foot of `terminal.css`
(`.term-gear`, `.settings-pop*`, `.settings-radio*`, `.settings-toggle*`); the
dropdown is the shared `FieldMenu` component (`src/components/FieldMenu.tsx`,
classes `.field-menu*`, §11.5), and its caret is `.term-caret` (§11.6).

### 11.1 Settings gear: the standard settings entry point

Reference: the header gear (`.header__gear`). A **bare glyph, not a button block.**

- **Transparent** background, **no border**, `3px` padding, `3px` radius.
- Glyph colour `--text` (`#d8d8d8`); hover brightens to `#f0f0f0`. No silver, no
  gradient, no fill: it is deliberately the quietest control on the surface.
- The icon is the standard cog `<svg viewBox="0 0 24 24">` at **14×14px**.
- Right-aligned in the header band (`margin-left: auto`), sitting in the `--head`
  band alongside the panel title.
- **When to use it:** any panel whose view options (filters, toggles, level pickers)
  should be tucked away rather than shown inline. The gear replaces inline control
  rows; clicking it opens the standard settings popup (§10 chrome) anchored just
  below it, top-right. First applied on the trade tape (it absorbed the old
  All / >200 / >500 filter and the ms/ns toggle).

### 11.2 Radio set: single choice

A horizontal row of custom radios for a one-of-N pick (e.g. the tape's block-size
Levels: All / >200 / >500).

- The dot is a **sunken well**: `11px` circle, `background: var(--well)`, 1px
  `--line-strong` border, inset shadow, the same sunken language as every well.
- Selected: border turns `--quote-bid` (`#5a96ff`) and a `5px` `--quote-bid` dot
  fills the centre. Unselected dots are empty wells.
- Label text sits to the right of each dot: `IBM Plex Mono`, tabular, ~10px, white.
- Options laid out inline with an even gap; the whole set is one `role="radiogroup"`
  row, label on the left of the row (`Levels`), the radios on the right.

### 11.3 Toggle: two-state switch

The canonical two-state switch is the **header-labels toggle**
(`.header-settings__choice` / `__opt`). Every binary switch in the terminal is this
control, exactly (e.g. the tape's ms/ns time-units switch).

- A **sunken well holds two options**: `background: var(--well)`, 1px `--line`
  border, `3px` radius, `2px` padding, inset shadow. The two option buttons sit
  inside with a `2px` gap.
- **Inactive option:** transparent, `--dim` (`#8f8f8f`) label, ~10px.
- **Active option** (`aria-pressed="true"`): raised,
  `linear-gradient(var(--raise-top), var(--raise-bot))`, 1px `--line-strong` border,
  white label, top glint `inset 0 1px 0 rgba(255,255,255,.35)`, `2px` radius. Exactly
  the raised-control language from §4.
- Labels are mono+tabular when they name units (`ms` / `ns`), sans otherwise
  (`White` / `Faint grey`).

### 11.4 Settings popup composition

A gear-opened settings popup is the §10 popup chrome filled with these parts:

- **Category header** (e.g. `Display`, `Layout`): a full-width transparent button,
  white 12px sans, with a `▸` caret (`--text`) that rotates to `▾` when expanded;
  hover is a faint `rgba(255,255,255,.05)` wash. This disclosure caret is not a
  dropdown caret and keeps its own size (§11.6).
- **Setting rows** under a category: a flex row, white ~11px sans **name on the
  left**, the control (radio set §11.2 or toggle §11.3) **on the right**.
- **Disabled row:** a row whose setting does nothing in the current state takes
  `settings-pop__row--disabled` (40% opacity, no pointer events, its buttons
  `disabled`), for example Fill color while Area fill is off.
- **Dim note:** `settings-pop__note`, `--dim` 10px sans, either inline after a row
  name (the chart's "Candles only") or as one line under a category's rows
  (`settings-pop__note--line`).
- **Footer:** the silver **Done** button (§10 primary-action silver), bottom-right.
- A transparent full-viewport backdrop catches an outside click to close.
- Every control in a settings popup persists (§13).

**Indicators category (P17, the price chart popup).** One row per indicator the
surface offers (§16.4), the indicator name on the left and the standard §11.3 toggle
(`On`, `Off`) on the right. An indicator the current chart type cannot draw (Volume
in line mode) shows disabled with the inline note "Candles only" and keeps its stored
state. Lower panes are capped per surface; once the enabled panes reach the cap, the
toggles of panes that are off are disabled and the category shows one dim line,
"Pane limit reached (N)", only while the cap is actually holding a pane off.
Overlays are never disabled by the cap.

### 11.5 Dropdown / pick-list: the standard picker

Settled on the order-entry TIF, Type, Qty and Display fields. **Every dropdown,
single-select or editable combobox, across the terminal uses this one list.** The
reference is the shared `FieldMenu` component, `src/components/FieldMenu.tsx`
(classes `.field-menu` / `__trigger` / `__input` / `__caret` / `__menu` / `__opt`).
Order entry, the book reader depth selector and the blotter status filter all use
it, and **no native `<select>` is used anywhere in the terminal.**

- **The list is white.** `#ffffff` background, 1px `#555` border, `3px` radius, a 2px
  inner pad, and a lift shadow (`0 6px 16px rgba(0,0,0,.5)`). It opens just below its
  field (`top: 100% + 2px`), spans the field width, and sits above everything
  (`z-index`). The host card must allow it to escape (`overflow: visible`).
- **No visible scrollbar.** `max-height ~164px` with `overflow-y: auto`, scrollbar
  hidden (`scrollbar-width: none` + `::-webkit-scrollbar { display: none }`). It still
  scrolls by wheel/drag.
- **Options.** `#000` text on a 2px-radius row, grey (`#ccc`) hover and
  `aria-selected` highlight. Text labels are `IBM Plex Sans`, left-aligned; numeric
  options are `IBM Plex Mono`, tabular, right-aligned (the `--num` modifier). Option
  text that names an order type or a status is all caps.
- **Two triggers, one list.** A **single-select** (TIF, order type, book reader depth,
  blotter status filter) uses a **white button trigger** that matches the list:
  white, black text, `#555` border, grey hover, value left, the standard `▾` caret
  (§11.6) right. An **editable combobox** (Qty, Display) uses the **sunken input
  well** (§4) with the same caret, opens the list on focus/click, and still accepts
  manual typing.
- **How it escapes.** `FieldMenu` renders the open list in a portal on the document
  body, fixed to its trigger and kept in place on scroll and resize (opening upward
  when there is no room below), so no host panel's height or overflow can clip it and
  the panel keeps its own scrolling. The panel caret menus (§9) use the same white
  list classes (`.field-menu__menu`, `.field-menu__opt`), anchored under their caret.
- **Compact variant.** `field-menu--compact` is a slimmer trigger for a panel header
  band (the book reader depth, the blotter filter). Its list may be wider than the
  trigger and aligns to the trigger's right edge.
- **No blue focus.** Neither the trigger nor the field shows a focus ring
  (`outline: none`, no blue box-shadow). This is the one deliberate place the terminal
  drops the §2 focus ring, by request, on the order-entry fields.
- **Interaction.** Selection is `onMouseDown` + `preventDefault` so the trigger/input
  keeps focus; blur or `Escape` closes. Options carry a `role="option"` with
  `aria-selected`; the list is a `role="listbox"`.
- **Test ids.** `<id>-trigger`, `<id>-input`, `<id>-opt-<value>`.

### 11.6 Dropdown caret

Every dropdown and header-menu caret is the `▾` glyph at `--caret-size` (`20px`),
through the `.term-caret` class (UI polish D7). The blotter header caret is the
reference size; the TIF, Type, Qty and Display carets, the book reader and blotter
filter carets, and the price chart's view caret all match it. Fields reserve right
padding for it (28px on a trigger, 26px on an editable input) so a value never runs
under the caret, and the order-entry row still fits on one line (§6). The `▸`
category caret in a settings popup is a disclosure control, not a dropdown caret,
and keeps its own size.

### 11.7 Price chart view menu: "View in trading"

On the Charts page the chart's header band carries a view caret (§11.6) just
left of its settings gear. It opens a small white menu with one item, **View in
trading**. Choosing it navigates to the trading page and opens the same price chart
there as a floating window (§14) over the live terminal: no scrim, so the panels
stay usable underneath. The floating chart has its own settings gear and a close
`×` in place of the view caret, and shares the chart's persisted display settings
(§13). It keeps its own chart type, bar size and indicators (§16). Whether the
window is open is held above the routes for the navigation and is not persisted.
Accepted as built.

---

## 12. Notifications

Order notifications are toasts in a fixed stack at the bottom right, above the
footer bar and above floating windows, shown on every page.

| Event (own orders only) | Title | Body |
|---|---|---|
| `ORDER_ACCEPTED` | Order submitted | side, quantity, type, price (or MIDPOINT PEG) |
| `ORDER_PARTIALLY_FILLED` | Partially filled | filled qty @ price, remaining |
| `ORDER_FILLED` | Order filled | filled qty @ price |
| `ORDER_CANCELLED` | Order cancelled | side, remaining qty @ price |
| `ORDER_REJECTED` | Order rejected | side, quantity @ price |
| `ORDER_EXPIRED` | Order expired | side, unfilled qty |

- **Own orders only.** EXEC frames are broadcast, so a toast fires only for an order
  this terminal sent. A passive fill of an own resting order (named by
  `passiveOrderId`) toasts too.
- **Live frames only.** "Submitted" fires on the venue's accept, never on send, so a
  rejected order never shows "submitted" first, and the trailing accept after a
  partial fill raises nothing. State that exists when the stack mounts raises no
  toast, and a report is toasted at most once (deduped by the EXEC `seq`).
- **Behaviour.** Newest at the bottom, at most 4 visible (the oldest is dropped),
  auto-dismiss after 5 s, hovering pauses a toast's timer, the `×` closes it. The
  stack is `role="status"` with `aria-live="polite"` and never takes focus.
- **Look.** The §10 family: `--pop-body` body, `--pop-head` title band, 1px
  `#808080` outline, radius 0, white normal-weight text, the `×` in `--exit-x`. The
  title is sans ~11px; the BUY / SELL word uses the bid and ask text colours; the
  numbers are Plex Mono tabular. Fixed width, 260px.
- **Code.** The pure `src/notices.ts` derives notices and owns the queue (add, cap,
  dismiss, expire); `OrderToasts` owns only the timers.

---

## 13. Persisted settings

Every settings control persists across page switches and app restarts, through one
storage module (`src/settings/persist.ts`) and one hook
(`usePersistentSetting(key, defaultValue, validate)`).

- **Keys** are namespaced and versioned: `hpse.v1.<panel>.<setting>`. Values are
  JSON.
- **What persists:**

  | Panel | Settings |
  |---|---|
  | Header | label colour (`header.labelMode`) |
  | Trade tape | levels, time units |
  | Price chart | background, line colour, gradient, fill (shared by the page and the floating window) |
  | Charts page chart | chart type, bar size, indicators (`priceChart.chartType`, `.barSize`, `.indicators`) |
  | Floating chart window | chart type, bar size, indicators (`priceWindow.chartType`, `.barSize`, `.indicators`) |
  | Depth chart | background |
  | Book reader | depth |
  | Blotter | status filter |
  | Floating windows | size and position of the depth chart and price chart windows (§14) |

- **What never persists:** order-ticket values (side, price, quantity, TIF, type),
  open popups, whether a floating window is open, expanded categories, scroll
  positions, and the FIX inspector's filter and selection. A stale ticket value restored on start could
  send an order the user did not intend.
- **Validation.** Every read is checked against the setting's allowed values;
  anything invalid or unparsable falls back to the default and the bad key is
  removed. The chart's indicator set is a JSON array read leniently (any array of
  strings) and then cleaned: unknown ids and ids the surface does not offer are
  dropped, but an id only unavailable for the current chart type is kept. At start-up, `hpse.` keys from any other version are removed and the
  pre-pass `header.labelMode` key is moved into the namespace.
- **Failure.** Every `localStorage` access is wrapped in try/catch; unavailable or
  full storage means "use the default", never a crash.
- **Timing.** Writes happen on change, not on unmount, so closing the app at any
  moment keeps the last value. Live instances of one key stay in step.
- Settings are per browser, per user profile. Nothing goes to the backend.

---

## 14. Floating windows

The maximized depth chart and the floating price chart are free-floating windows
over the live terminal (no scrim). Both use one shared shell, `FloatingWindow`
(`src/components/FloatingWindow.tsx`), with its geometry rules in the pure
`src/floatingWindow.ts`.

- **The header drags.** Dragging the window's header band moves it. Controls in the
  header (the gear, the `×`, an open settings popup) never start a drag.
- **Edges and corners resize.** All four edges and all four corners resize, each
  with its matching resize cursor (`ns`, `ew`, `nesw`, `nwse`). The handles are thin
  invisible strips (6px on the edges, 10px corner squares) on the window border.
  Resizing from one edge keeps the opposite edge where it was. One gesture is either
  a move or a resize, never both.
- **Grip mark.** A small, subtle grip in the bottom-right corner: two short diagonal
  hairlines in the charcoal `--line-strong` (`#444`), no colour. It is decorative;
  the corner strip under it takes the pointer.
- **Pointer handling.** Pointer events with pointer capture, so a fast drag that
  leaves the window keeps going; nothing on the page selects while a window is moved
  or resized.
- **Minimum size** is 360 x 240px, so the axes and labels stay readable (smaller
  only if the browser window itself is smaller).
- **Viewport clamping.** A window never moves or resizes past the viewport, so its
  header is always on screen and it can always be dragged back. When the browser
  window shrinks, every open window clamps back inside.
- **The chart redraws at the new size.** The chart measures its box (ResizeObserver,
  debounced to one frame) and lays out at it, never stretching or scaling a
  fixed-size drawing: the price chart's axes, gridline counts and last-price tag
  reflow to the window, and the depth chart reflows its plot while text keeps one
  size (the mid label stays at its 13px floor, UI polish D3).
- **Geometry persists** per window (`hpse.v1.priceWindow.geometry`,
  `hpse.v1.depthWindow.geometry`, §13), saved when a gesture ends, validated and
  clamped to the current viewport on restore. A missing or invalid value falls back
  to the default: the nominal size (depth chart 1040 x 560, price chart 760 x 350),
  capped at 94% x 82% of the viewport, centred. Whether a window is open is not
  persisted.

---

## 15. Brand mark, title bar, favicon and tab title (LOCKED)

The DMA terminal's official name is **directROUTE** (*direct* in italic, ROUTE in
caps). Wordmark direction: **Concept 02 "Order Path"** (Nov 2026), from the six
concepts on https://claude.ai/artifact/25NnCKGjFgag6iHosQduBu. Placement in the
terminal: **option B, the anchor title bar** (Nov 2026), from
https://claude.ai/artifact/RMidBYS7BX8dpr1tLJxju7. The terminal connects to the
exchange, **CREDO**, which carries its own separate brand. The Project doc
`BRAND_directROUTE.md` mirrors this section.

### 15.1 Wordmark (large uses: README, splash screen, LinkedIn)

- **Font:** `IBM Plex Mono` throughout.
- **`direct`:** italic, weight 400, `--text` `#d8d8d8`.
- **`ROUTE`:** upright, weight 600, `#ffffff`. No space between the two words.
- **Order-path arrow:** a 2px `--brand-amber` line under the full width of the word,
  ending in a solid triangular arrowhead at the right. Gap from the text ~0.08em;
  arrowhead height ~0.16em of the type size.
- **Ground:** `--body` `#1b1b1b` or `--well` `#0d0d0d`.

### 15.2 Lockup (small uses, ~12 to 13px)

- The underline arrow is dropped at this size (a 2px line blurs into a hairline).
- An amber `→` follows ROUTE after one space instead: `direct` `ROUTE` `→`.
- Same colours and weights as the wordmark.
- In the terminal the lockup lives **only in the title bar** (§15.3), at 12px. It is
  not repeated in the instrument strip, the nav band or any panel.

### 15.3 Title bar: the terminal's top line

Modelled on the TWS Mosaic anchor bar: a thin app-frame band that sits above the
trading data and carries the product identity and the connection, never market data.

- **Position.** The first row of the app, above the instrument strip (`Header`) and
  the nav band, inside `.topbar`, outside `<Routes>`, so it shows on every page.
- **Metrics.** Height `--titlebar-h` (`24px`). Ground `--well` (`#0d0d0d`), the
  darkest surface, so it reads as the frame rather than a panel header. 1px `--line`
  bottom hairline. Padding `0 10px`. No radius, no glint, no gradient.
- **Left:** the §15.2 lockup at 12px, and nothing else. The icon is **not** shown in
  the title bar (decided Nov 2026); it lives in the browser tab as the favicon
  (§15.5), which sits directly above the title bar, so showing it twice would repeat
  the mark.
- **Right, pinned (`margin-left: auto`), `12px` gap between items:**
  - **Venue:** the label `VENUE` in IBM Plex Sans 10px, uppercase, letter-spacing
    0.08em, `--dim`, then the venue name `CREDO` in IBM Plex Mono 10px, `#ffffff`.
  - A 1px `--line-strong` divider, 16px tall.
  - **Connection badge:** the existing `ConnectionBadge` (dot + white sans label,
    no pill), **moved here from the instrument strip**. Connection is app state, not
    instrument data.
- **Nothing else.** No menus, account, clock or decoration until a real feature
  needs the space (the project's no-invented-chrome rule). If one is added later it
  goes right, left of the venue.
- **The instrument strip** keeps its gear at the right edge; only the badge leaves.
  Its header comment ("No product name") is updated to point here.
- **Layout.** `--chrome-h` grows by `--titlebar-h` (170px to 194px), written as
  `calc(170px + var(--titlebar-h))`. No rule reads `--chrome-h` today: the >=901px
  fixed-viewport block fills the workspace with flex, so the title bar's 24px comes
  out of the workspace and the panels still fill to the bottom of the screen.
- **Semantics.** A plain `<div>` band with the lockup as text; the badge keeps
  `role="status"`. The title bar is not a landmark and takes no focus.
- **As built.** `src/components/TitleBar.tsx`, rendered first inside `.topbar` in
  `App.tsx`; test ids `titlebar`, `titlebar-lockup`, `titlebar-venue`. In
  `terminal.css` the §2 charcoal `--line` / `--line-strong` are named `--hair` /
  `--hair-strong` (the bare `--line` names are still the legacy slate), so the
  hairline is `--hair` and the divider `--hair-strong`. The badge styling is the
  `.titlebar .badge` rule. "direct" needs the Plex Mono 400 italic face, imported in
  `main.tsx`.

### 15.4 Icon

- **Tile:** a rounded square on `--well`, 1px `--line-strong` border, radius about
  21% of the side (`12px` at 56px, `4px` at 16px). At 32px and larger it carries the
  top glint `inset 0 1px 0 rgba(255,255,255,.18)`; below that the glint is dropped.
- **Glyph:** a `--text` dot (the order's origin) on the left, an amber line running
  right into a solid amber arrowhead (the venue). No letters.
- **Sizes in use:** 56px (README, splash, LinkedIn), 32px (the favicon source,
  §15.5), 16px (the favicon as the browser draws it in a tab). It is not used inside
  the terminal UI.

### 15.5 Favicon

- One SVG file, `public/favicon.svg`, linked from `index.html` with
  `<link rel="icon" type="image/svg+xml" href="/favicon.svg" />`. A PNG fallback
  (`public/favicon-32.png`, rendered from the same SVG) is linked after it for
  browsers without SVG favicons. As built, the PNG was rasterized with headless
  Chrome (32x32 RGBA, transparent corners).
- It is the §15.4 icon drawn on a 32px grid, with no glint, and the dot and stroke
  thickened slightly so the glyph holds at 16px in a tab:

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
  <rect x="0.5" y="0.5" width="31" height="31" rx="7" fill="#0d0d0d" stroke="#444444"/>
  <circle cx="8" cy="16" r="3.5" fill="#d8d8d8"/>
  <line x1="12" y1="16" x2="21" y2="16" stroke="#ffa028" stroke-width="3"/>
  <polygon points="20,9.5 27.5,16 20,22.5" fill="#ffa028"/>
</svg>
```

- The 56px icon (README, splash) is the same drawing with the glint added, so every
  size matches.

### 15.6 Browser tab title

- **Format:** `<SYMBOL> · <Page> | directROUTE`, most specific first so a narrow tab
  still shows what it is. The separator inside is a middle dot (`·`, U+00B7) with a
  space either side; the brand follows a pipe.
  - Trading page: `ASML · Trading | directROUTE`
  - Charts page: `ASML · Charts | directROUTE` (renamed from Price Chart at P17-1;
    the old `/chart` route redirects to `/charts`)
  - The page name is the nav tab label, so the two never disagree.
- **Static fallback.** `index.html` carries `<title>directROUTE</title>`, which is
  what shows before the app mounts. The legacy `OMS — ASML Order Book` title is
  retired (the project is not an OMS).
- **No live prices in the title.** The last price is not written to the tab, so the
  title does not churn on every print and never shows a stale price in a background
  tab.
- **Implementation.** One small hook (for example `useDocumentTitle(page)`) called by
  each page, built from the same `SYMBOL` constant the header uses. It sets
  `document.title` on mount and route change; it is a pure string builder plus one
  effect, tested without a DOM for the string and with one render test per page.
- **As built.** The builder and the page names are `documentTitle()` and
  `PAGE_NAMES` in `src/documentTitle.ts` (the Navbar renders `PAGE_NAMES` as its
  labels); the hook is `src/useDocumentTitle.ts`; `SYMBOL` is the one exported from
  `src/protocol/messages.ts`, which `Header` also imports.

### 15.7 On light grounds

- `direct` `#3a3a3a`, `ROUTE` `#1b1b1b`, arrow `--brand-amber-light` (`#c87a14`) so it
  holds contrast on `#f2f2f2`. The icon tile stays dark on any ground.

### 15.8 Rules

- Amber is the brand accent only (§2). It is never used for data, sides, or UI state
  inside panels; blue and red stay semantic. The price chart's selectable "Amber"
  line colour (`#ffb01f`) is display config, not a brand use, and stays separate.
- Do not recolour the arrow blue or red, do not set the mark in Plex Sans, and do not
  add a space or separator between `direct` and `ROUTE`.
- The mark appears once on screen: in the title bar. Panels, the instrument strip,
  popups and toasts never carry it.
- In running text write "directROUTE". `directRoute` is accepted informally; the mark
  itself always uses caps ROUTE.

---

## 16. Charts

Phase 17 makes the price chart a candlestick chart and sets the chart standard that
Phases 18 to 20 extend. Everything is hand-rolled SVG with no charting dependency.

### 16.1 Surfaces and placement

The chart runs on two surfaces: the **Charts page** (`/charts`) and the **floating
window** on the Trading page (§11.7, §14). The panel title stays "Price chart". Each
surface keeps its own chart type, bar size and indicators (§13); the display colours
are shared.

| Feature | Floating window (Trading page) | Charts page |
|---|---|---|
| Line or candles, volume, crosshair | Yes | Yes |
| VWAP, moving averages, Bollinger bands | Yes | Yes |
| Fill markers, order lines, drag to reprice | Yes | Yes |
| Bid/ask lines | Yes | Yes |
| Volume profile, CVD, footprint | No | Yes |
| Liquidity heatmap | No | Yes, as a chart type |
| Execution quality (TCA) | Executions tab in the blotter panel | No |

### 16.2 Chart types and bar sizes

- **Candles** (default) and **Line** (the trade-print line, as built in P12). Phase 20
  adds a heatmap type on the page only.
- **Bar sizes:** 5s, 15s (default), 1m, 5m. The reducer folds every fill into all four,
  so switching size is instant and each keeps its own history (500 closed bars each).
- **Bucketing is clock-aligned:** `bucket = floor(t / size)` on the epoch-nanosecond
  EXEC timestamp. A 15s bar opens at :00, :15, :30 and :45, a 1m bar on the minute.
  Empty buckets are honest gaps, never synthetic flat bars. Bars start when the tab
  connects; there is no backfill.
- **Time axis** reads clock time `HH:MM:SS` in both modes, ticked at round clock steps.

### 16.3 Candle and volume geometry

- **Slots.** The chart shows the newest bars that fit at a 5px minimum slot, from the
  oldest visible bucket to the open bar; a gap keeps its slot. The slot width is the
  plot width over the slot count, capped at 16px, and the slots are right-aligned so
  the newest bar sits by the price axis.
- **Candles.** Wick 1px, centred. Body 70% of the slot, at least 1px, filled. A doji
  (open equals close) is a 1px body. Up and down take `--candle-up` / `--candle-down`.
- **Price axis.** The y domain covers the visible highs and lows padded 5% each side,
  snapped to round levels on the right-hand axis. Reference lines (session open,
  market open) and VWAP never widen it.
- **Last price.** In candle mode the dashed level and the axis tag take the open bar's
  direction colour (tag ink by luminance). Line mode keeps the line colour.
- **Display rows.** Line color, Area fill and Fill color draw nothing in candle mode
  and show disabled there.
- **Volume pane.** Columns aligned exactly to the candle bodies, in the candle tokens at
  60% opacity, on a small right-hand axis labelled at the visible maximum. Candles only.

### 16.4 Indicators and panes

- An indicator registry (`src/indicators/registry.ts`) describes each indicator: id,
  label, placement (`overlay` on the price area or a lower `pane`), the surfaces that
  offer it, and the chart types that can draw it. Phase 17 registers Volume (pane,
  candles only) and VWAP (overlay, both types).
- **Overlays** are unlimited. **Lower panes** are capped: 1 on the window, 3 on the
  Charts page. The popup enforces the cap (§11.4).
- **Pane layout.** For k panes each takes `min(20%, 40% / k)` of the plot height and the
  price area keeps the rest, never less than 60%. Panes tile below the price area with
  a 1px separator and share the x axis.

### 16.5 VWAP

- Session VWAP from the reducer's notional accumulator (BigInt), rounded half up to a
  $0.0001 unit. It is a statistic, never an order price.
- Candle mode: a 1px `--vwap` line through each visible bar's VWAP at the slot centre.
  Line mode: a step line from the 5s bars at their bucket start on the tape's time
  domain. The line is clipped to the price area.
- A right-axis tag, the last-price tag's shape in `--vwap`, pinned to the axis edge
  when VWAP is off-scale and moved a tag height away when it would cover the
  last-price tag.
- The Charts side panel shows a **VWAP** row between Low and Trades.

### 16.6 Crosshair and readout

- On pointer move over the plot: dashed 1px vertical and horizontal hairlines in the
  chart's ground-aware dim ink (not `--dim`, because the chart has a black and a white
  ground), a price tag on the right axis at the pointer, and a time tag on the x axis.
  The vertical line snaps to the hovered slot centre (candles) or the nearest print
  (line). Over a lower pane only the vertical line and time tag show.
- **Readout strip** at the top left of the price area, `IBM Plex Mono` tabular, 10px:
  candles `O H L C V` plus `VWAP` when on; line `price x size @ HH:MM:SS`. Keys take the
  dim ink, values the text ink. With no pointer it shows the newest bar or print, so
  it always reads something.
- Pointer state is local and never persisted. The plot never starts a floating-window
  drag: the band is the only drag handle (§14).
