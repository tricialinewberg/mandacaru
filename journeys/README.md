# Agentic journey tests

This directory holds **journey** tests for Mandacaru: XML files of natural-language
`<action>` steps that an AI agent (via the `android-cli` skill) executes and verifies
against the running app on a device.

A journey passes only if every `<action>` succeeds and the app never crashes, freezes,
or exits. Steps that start with "verify" / "check" assert on the current screen state;
all other steps are interactions.

## Prerequisites

- One ARM64 (`arm64-v8a`) device or emulator connected — the bundled Rust `.so` is
  ARM64-only.
- A debug build installed: `./gradlew installDebug`.
- The `android-cli` skill available to the agent (`android layout`, `android screen`,
  `adb shell input`).

## Running a journey

Hand a journey file to the agent and ask it to evaluate it, e.g. "run
`journeys/navigate_tabs.xml`". The agent drives the app with `adb shell input`, inspects
state with `android layout`, and reports a per-action PASSED/FAILED/SKIPPED summary.

Inspect the live UI tree (and confirm tags resolve to ids) with:

```bash
android layout --pretty
```

Note: `android layout` reports the tag under the `resource-id` key (e.g.
`"resource-id":"nav_settings"`).

## Cold-start splash

On a cold start the app shows a ~4-second splash before the Node screen appears. A
"Launch the app" step must wait for the splash to dismiss (poll `android layout` until a
known tag such as `nav_node` appears) before asserting on screen content.

## Identifying the current screen

Navigation is a `HorizontalPager`, not a `NavController`, and adjacent pages stay
composed — so content from the neighbouring screen can be present (off-screen) in the
tree. The reliable signal for "which screen is active" is the **selected nav item**: the
active destination's nav element carries `"state":["selected"]` in the layout dump
(e.g. `nav_wallet` is selected on the Wallet screen). Combine that with a
screen-unique element (see tables below) when an extra check is wanted.

Container/screen-root tags are intentionally not used: a Compose layout node carrying
only a `testTag` is not important-for-accessibility and does not surface in
`android layout`. Only nodes that already emit semantics (interactive controls, text,
nav items) appear.

## testTag contract

Compose `testTag`s surface under the `resource-id` key in `android layout` output
because the root sets `testTagsAsResourceId = true` (see `MainActivity.MandacaruRoot`).
Agents should prefer targeting these stable ids over localized `text` or raw `bounds`.

Tags are inline string literals at each call site (no shared constants file, to avoid
merge conflicts across branches). This README is the canonical list — keep it in sync
when adding or renaming a tag.

### Navigation (`MainActivity.kt`)

| resource-id        | element                                  |
|-------------------|------------------------------------------|
| `nav_node`        | Node Info bottom-nav / rail item         |
| `nav_wallet`      | Wallet nav item                          |
| `nav_coinjoin`    | CoinJoin nav item                        |
| `nav_settings`    | Settings nav item                        |

### Node screen (`node/ScreenNode.kt`)

| resource-id              | element                         |
|-------------------------|---------------------------------|
| `node_sync_percentage`  | sync progress percentage        |
| `node_network`          | network value                   |
| `node_peer_count`       | number-of-peers value           |
| `node_difficulty`       | difficulty value                |
| `node_disconnect_peer`  | per-peer disconnect button      |

#### Utreexo paste sheet (`node/UtreexoPasteSheet.kt`)

| resource-id              | element                                  |
|-------------------------|------------------------------------------|
| `input_utreexo_payload` | snapshot payload text field              |
| `button_paste_clipboard`| "Paste from clipboard" button            |
| `button_import_payload` | "Import" submit button                   |

These ids are declared for the instrumented Compose tests. The paste sheet is a
`ModalBottomSheet`, so (per the popup caveat below) its tags do **not** surface as
`resource-id` in `android layout` — in journeys, target its controls by **text**
("Paste from clipboard", "Import") instead.

When a valid accumulator for the current network is on the clipboard, the Node screen
shows a clipboard-import **snackbar** ("Accumulator found on clipboard" + an "Import"
action) on open. The snackbar is part of the Scaffold subtree, so its text and action
**are** targetable by text in `android layout`.

### Wallet screen (`wallet/ScreenWallet.kt`)

| resource-id                | element                                        |
|----------------------------|-------------------------------------------------|
| `wallet_balance_card`      | balance card container                          |
| `wallet_balance`           | balance value (sats)                            |
| `button_new_address`       | "Generate address" / "New address"              |
| `wallet_receive_address`   | the currently displayed receive address text    |
| `button_reveal_seed`       | "Reveal seed phrase" (opens the backup dialog)  |

`button_reveal_seed` opens an `AlertDialog` with the mnemonic words
(`wallet_seed_phrase`). Per the popup caveat below, dialog content does not
surface as `resource-id` - assert on it by text (the numbered word list), and
dismiss via the "Done" button by text.

### CoinJoin screen (`coinjoin/ScreenCoinjoin.kt`)

| resource-id                     | element                                     |
|----------------------------------|----------------------------------------------|
| `button_create_pool`            | FAB that opens the create-pool dialog        |
| `coinjoin_pool_list`            | the discovered-pools list container          |
| `coinjoin_active_status`        | status text once this device has joined/created a round |
| `pool_item_<id>`                | a discovered pool's card (id varies per pool)|
| `button_join_<id>`              | "Join" button on a given pool's card         |

`button_create_pool` opens an `AlertDialog` (`input_denomination`,
`button_confirm_create_pool`) - per the popup caveat, target its field/button by
text/hint rather than `resource-id` while the dialog is open.

CoinJoin rounds need real relay connectivity and a UTXO that exactly matches a
pool's denomination to progress past "waiting for peers" - journeys against a
single device should assert on the create/join UI responding, not on a
completed round.

### Settings screen (`settings/ScreenSettings.kt`)

| resource-id                  | element                                |
|-----------------------------|----------------------------------------|
| `input_descriptor`          | wallet descriptor field                |
| `button_update_descriptor`  | "Update descriptor"                    |
| `button_scan_descriptor`    | "Scan QR" (opens the descriptor scanner)|
| `button_copy_descriptor`    | a loaded descriptor row — tap to copy the full descriptor to the clipboard |
| `input_network`             | network selector field                 |
| `toggle_mobile_data`        | "Also use mobile data" switch          |
| `toggle_advanced_features`  | "Advanced features" switch (gates the Developer Tools section) |
| `button_view_logs`          | "View logs" (opens the full-screen log viewer) |
| `button_export_logs`        | "Export" (share the full debug.log) — inside Developer Tools |
| `toggle_tor`                | "Route CoinJoin over Tor" switch (inside the Tor section) |
| `input_tor_socks_host`      | Tor SOCKS proxy host field — only rendered while `toggle_tor` is on |
| `input_tor_socks_port`      | Tor SOCKS proxy port field — only rendered while `toggle_tor` is on |
| `relay_item_<url>`          | a configured relay row inside the "Nostr relays" section, tag suffixed with its URL |
| `button_remove_relay_<url>` | that row's remove button — disabled (and dimmed) when it's the last remaining relay |
| `input_nostr_relay`         | new-relay URL field (must be `wss://…` to be accepted) |
| `button_add_relay`          | "Add relay" — disabled once the list reaches the configured max |

`button_copy_descriptor` is applied to each loaded descriptor row, so the tag repeats once
per descriptor — target the first when more than one is present. Tapping a row copies the
**full** descriptor (not the truncated two-line display) and shows a
"Descriptor copied to clipboard" snackbar.

`button_scan_descriptor` opens `DescriptorScanSheet` (a `ModalBottomSheet`) and, on a
successful scan, `DescriptorScanConfirmDialog` (an `AlertDialog`). Both render in a
separate window (per the popup caveat below), so their controls — "Paste instead",
"Decode", "Load", "Cancel", the decoded descriptor and its script type — are targeted by
**text**, not `resource-id`.

The Data usage section's `toggle_mobile_data` switch is off by default (Wi-Fi only);
turning it on persists the preference and restarts the app. Expand the "Data usage"
section by text before asserting on it.

`toggle_tor` is off by default and both text fields are blank (they only show
"127.0.0.1"/"9050" as **placeholder** text, matching Orbot's default SOCKS port — the
stored value stays empty until the user actually types something). Expand the "Tor"
section by text before asserting on it. When on, CoinJoin's create/join actions
probe the configured host:port before proceeding and block with a snackbar if nothing
is listening there — so a journey that turns this on without a running proxy should
expect CoinJoin actions to fail, not hang.

The "Nostr relays" section ships pre-populated with the three default relays (no empty
state), since at least one relay is always required. `button_remove_relay_<url>` is
disabled on the last remaining row — removing down to zero is not possible from the UI.
`button_add_relay` validates the `input_nostr_relay` field client-side (`wss://` scheme,
no duplicates, capped list size) before persisting; a rejected entry surfaces its reason
as the text field's error/supporting text, not a snackbar.

`toggle_advanced_features` is off by default. The **Developer Tools** section (and its
`button_view_logs` / `button_export_logs`) only renders once the toggle is on. Expand
"Developer Tools" by text, then tap `button_view_logs` to open `ScreenDeveloperLogs` — a
full-screen Nav3 destination in the root subtree, so its tags surface normally (the popup
caveat does **not** apply). There: `button_back_logs` returns to Settings, `button_copy_logs`
copies the displayed tail, and the share action reuses `button_export_logs`. Each log line
is colored by level (ERROR/WARN/INFO/DEBUG/TRACE).

Tapping `input_network` opens the network dropdown. Its options are **targeted by text**
(`BITCOIN`, `SIGNET`, `TESTNET`, `REGTEST`, `TESTNET4`) — see the popup caveat below.

## Popups, dropdowns, and dialogs

`testTagsAsResourceId` is set on the app's root composable, but Compose renders dropdowns
(`ExposedDropdownMenu`), dialogs, and bottom sheets in a **separate window** outside that
subtree, so their `testTag`s do **not** surface as `resource-id`. Their `text` and
`content-desc` still appear in `android layout`. Target items inside these popups by their
visible text (e.g. tap the `SIGNET` menu item by text).

## Cross-checking node state

Several journeys verify sync/peer state shown on screen. To confirm independently,
forward the RPC port and query the daemon directly:

```bash
adb forward tcp:8332 tcp:8332
curl -s -X POST http://127.0.0.1:8332 \
  -d '{"jsonrpc":"2.0","method":"getblockchaininfo","params":[],"id":1}'
```

(Port is per-network: 8332 mainnet, 38332 signet, 18332 testnet, 18443 regtest.)
