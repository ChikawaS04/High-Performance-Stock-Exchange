# Terminal Design System — TWS Charcoal

The visual blueprint for the High Performance Stock Exchange front end. Every panel
we build or rework from here follows this document. It was established during the
order-entry rework and is the reference the rest of the terminal is brought in line
with, panel by panel.

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

> Rollout note: the order-entry ticket uses blue for the bid. The rest of the
> terminal (depth ladder, tape) still uses the legacy green bid. During rollout we
> decide once whether the whole terminal moves to blue-bid or the ticket reverts to
> green, and apply it everywhere at once.

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
  editable pickers against the dark.
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
   legacy blue-grey `--surface` palette and the slate-retheme layer panel by panel,
   and settle the blue-vs-green bid question once for the whole terminal.

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
