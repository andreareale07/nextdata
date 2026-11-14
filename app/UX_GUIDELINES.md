# NextData UX guidelines

Sources: Material 3 Expressive (https://design.google/library/design-notes-material-3-expressive-liam-spradlin), WCAG 2.2 (2.4.7 focus visible, 2.4.11 focus not obscured, 2.5.8 target size, 1.4.3/1.4.11 contrast), Fluent 2 layout (https://fluent2.microsoft.design/layout), data table guide (https://www.setproduct.com/blog/data-table-ui-design).

Principle: expressive only where it matters (primary action, security moments, empty states); lists, tables and forms stay sober and dense.

## Tokens (`ui/Theme.kt`)
- Palette: ink-slate neutrals + teal. Light and dark `ColorScheme` complete (surface and 5 surfaceContainer levels, outline, error). Tonal elevation, no heavy shadows.
- Semantic colours `MaterialTheme.nd`: success, warning, info (+container, on-colours). Status is always icon + label + colour.
- Type: full M3 scale, sp units (follow system font up to 200%), titles semi-bold, tabular sizes via `Formatters`.
- Grid 4/8 dp (`Space`), radii 4/8/12/16/28 (`Shapes`), motion 120/160/200 ms (`Motion`, `motionMs`), 0 ms when the OS removes animations (`LocalReduceMotion`).
- Density (`DensitySpec`): comfortable (row 60 dp, min target 48 dp) for touch, compact (row ~36 dp, min target 32 dp >= 24 px) for pointer; automatic by platform, user choice in Settings (persisted in `settings.set`).
- `ContrastTest` enforces AA (4.5:1 text, 3:1 UI) for both themes.

## Layout
- Wide (>= 600 dp: desktop, tablet, phone landscape): navigation pane (profiles, favorites, recent; resizable on desktop, width persisted) | content (command bar, address row with clickable/editable breadcrumb and quick filter, table or grid, optional details pane, status bar) | transfers panel.
- Compact (< 600 dp): collapsing Medium top app bar, extended FAB that collapses on scroll, bottom sheets for actions, contextual action bar for selection, pull-to-refresh, predictive back (manifest opt-in), screen transitions.
- Edge-to-edge: surface under the bars, top bars own the status-bar inset, sides/bottom/IME padded at the root.

## Components and patterns
- `NdIconButton` (tooltip + content description), `StatusBanner` (info/warning/error/success, live region), `Skeleton*` (shimmer, static with reduce motion), `FingerprintBlocks` (monospace blocks, differing blocks highlighted), `EmptyState`/`ErrorState` (cause + recovery action, "Reconnect" when the connection is lost).
- File views: list/table (sortable and resizable columns Name/Size/Modified/Type), grid; click / Ctrl / Shift selection, keyboard cursor, right-click menu, long-press selection with haptic.
- Shortcuts: F2 rename, Del delete, F5 refresh, Ctrl+A, Ctrl+N new folder, Ctrl+F filter, Alt+Up / Backspace up, Enter open, arrows/PgUp/PgDn/Home/End (Shift extends), Alt+Enter details, Esc clear filter/selection, Ctrl+Shift+N new profile.
- Destructive actions: dialog with count and names, recursive warning in a danger box, danger button, focus on Cancel, server-side deletion cannot be undone so no undo snackbar.
- Security: trust dialogs with state label (NEW SERVER / KEY CHANGED / UNVERIFIED CERTIFICATE), fingerprint in blocks with copy; changed key: safe action prominent and focused, "replace" separate and gated by a checkbox; status bar chip shows protocol and encryption (amber "Not encrypted" for http); details dialog shows fingerprint when confirmed in this session.
- Onboarding: first-run screen, 3-step profile guide (protocol cards, server with live validation and "Use the host only" fix, access with show/hide secret) and "Test connection".
- Feedback: skeleton while loading, non-blocking banner for errors with data on screen, snackbar with "Show" after a download, transfers with speed, ETA, cancel, retry, pause queue; copy is short, action-verb based, IT/EN in `composeResources` (parity test).

## Accessibility rules adopted
Semantics (role, selected, state description, live regions for status/progress), tooltips on every icon, visible focus (Material indication + focus ring on the keyboard cursor), logical focus order with first field focused in the guide, targets >= 48 dp touch / >= 24 px desktop, content scrolls instead of clipping at 200% font, no information by colour alone, reduce-motion honoured.
