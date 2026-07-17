# IPÊ 🌸

A privacy-focused, self-sovereign CoinJoin wallet for Android — powered by [Utreexo](https://dci.mit.edu/utreexo), [Floresta](https://github.com/vinteumorg/Floresta), and coordinated over [Nostr](https://nostr.com/).

Cardo runs its own lightweight, validating Bitcoin node on your phone (no trusted server, no watch-tower, no third-party wallet backend), holds a local signing wallet, and lets you join coordinator-less CoinJoin rounds — "Joinstr" — with other peers discovered over Nostr relays. Named after the thistle: a modest, prickly plant, in the same spirit as Mandacaru (the cactus this project grew out of).

## Features

- 🤝 **Coordinator-less CoinJoin (Joinstr)**: Discover, create, and join fixed-denomination CoinJoin pools coordinated over Nostr — no central server holding your privacy hostage
- 🔑 **Local signing wallet**: BIP39/BIP84 wallet with keys held on-device (Android Keystore-encrypted), needed to sign your own input live during a round
- ⚡ **Lightweight node**: Uses Utreexo to dramatically reduce storage requirements
- 🔒 **Self-sovereign**: Validates Bitcoin transactions and broadcasts your own coinjoin transactions directly from your device — no external broadcaster
- 🌐 **Multi-network**: Bitcoin Mainnet, Testnet, Testnet4, Signet, and Regtest
- 👥 **P2P networking**: Connect, disconnect, and ping Bitcoin peers
- 📊 **Real-time sync**: Monitor blockchain synchronization progress
- 🩺 **Diagnostics**: Monitor node uptime and memory usage
- 🎨 **Modern UI**: Material Design 3 interface with light/dark themes, phone and tablet layouts

> **Nostr relay connections can optionally be routed over Tor** via a local SOCKS proxy (e.g. [Orbot](https://orbot.app/)) - opt-in and off by default, configurable in Settings. When enabled, CoinJoin refuses to start rather than silently falling back to clearnet if the proxy isn't reachable. This covers the Nostr relay traffic only; the local node's own P2P and JSON-RPC connections are unaffected (RPC is loopback-only, and Bitcoin P2P Tor routing is a separate, not-yet-wired gap - Floresta's own `proxy` config field exists but isn't set from the app yet).

## Screenshots

Screenshots below are placeholders carried over from the pre-rebrand app and need to be retaken against the current Node/Wallet/CoinJoin/Settings screens on a device.

<div align="center">

### Node Information
<table>
  <tr>
    <td><img src="screenshots/node_light.png" alt="Node Screen Light" width="250"/></td>
    <td><img src="screenshots/node_dark.png" alt="Node Screen Dark" width="250"/></td>
  </tr>
  <tr>
    <td align="center"><em>Light Theme</em></td>
    <td align="center"><em>Dark Theme</em></td>
  </tr>
</table>

### Wallet
<table>
  <tr>
    <td><img src="screenshots/wallet_light.png" alt="Wallet Screen Light" width="250"/></td>
    <td><img src="screenshots/wallet_dark.png" alt="Wallet Screen Dark" width="250"/></td>
  </tr>
  <tr>
    <td align="center"><em>Light Theme</em></td>
    <td align="center"><em>Dark Theme</em></td>
  </tr>
</table>

### CoinJoin
<table>
  <tr>
    <td><img src="screenshots/coinjoin_light.png" alt="CoinJoin Screen Light" width="250"/></td>
    <td><img src="screenshots/coinjoin_dark.png" alt="CoinJoin Screen Dark" width="250"/></td>
  </tr>
  <tr>
    <td align="center"><em>Light Theme</em></td>
    <td align="center"><em>Dark Theme</em></td>
  </tr>
</table>

### Settings & Configuration
<table>
  <tr>
    <td><img src="screenshots/settings_light.png" alt="Settings Screen Light" width="250"/></td>
    <td><img src="screenshots/settings_dark.png" alt="Settings Screen Dark" width="250"/></td>
  </tr>
  <tr>
    <td align="center"><em>Light Theme</em></td>
    <td align="center"><em>Dark Theme</em></td>
  </tr>
</table>

</div>

## How CoinJoin works here (Joinstr)

Cardo implements a **coordinator-less** CoinJoin round, adapted from the [Joinstr](https://github.com/1440000bytes/floresta_wallet) protocol design:

1. A peer **creates a pool**: picks a fixed denomination, generates an ephemeral Nostr identity for the round, and announces the pool (kind `2022`) on a relay.
2. Other peers **discover and join** the pool, each registering one UTXO that exactly matches the denomination, and a fresh output address — sent as an encrypted direct message (NIP-04, kind `4`) to the pool's ephemeral identity.
3. Once every seat is filled, the final output list (everyone's destination, order-independent of who registered when) is fanned back out to all participants.
4. Each peer independently signs **only their own input**, with `SIGHASH_ALL | ANYONECANPAY` — a signature that commits to every output but leaves every other peer's input free to be added later.
5. Whichever peer collects every signed input merges them into one transaction and broadcasts it through the local node. Because of how `ANYONECANPAY` works, no peer ever needs another peer's private key, and no peer needs to be trusted to act honestly for the transaction to be valid — they either produce a fully valid joined transaction, or nothing broadcasts at all.

No coordinator ever holds funds, sees the whole picture ahead of time, or can steal a peer's coin — the worst a malicious "creator" can do is stall a round, not take anyone's money.

## What is Utreexo?

Utreexo is a dynamic hash-based accumulator that allows Bitcoin nodes to validate the blockchain without storing the full UTXO set. This reduces storage requirements from tens of gigabytes to just a few megabytes, making it practical to run a validating node on mobile devices.

## A note on validation: `assumeutreexo` is enabled by default

Cardo ships with `assumeutreexo` turned on. At startup the node trusts a hardcoded Utreexo state snapshot (the accumulator roots at a specific block height) and begins full validation from that point forward — verifying every new block, transaction, signature, and consensus rule from the snapshot height onward.

What this means in practice:
- **Fast startup**: the node skips re-validating ancient history and is usable in minutes instead of days.
- **Full validation going forward**: from the snapshot height on, Cardo is a fully validating Bitcoin node — no trusted third party for new blocks.
- **A trust assumption about pre-snapshot history**: you are trusting that the bundled snapshot matches Bitcoin's true historical chain state. This is the same trade-off as Bitcoin Core's `assumeutxo` and `assumevalid`.

### Even with `assumeutreexo`, a bad accumulator is detectable

Under Utreexo, every transaction input in every new block must come with an inclusion proof against the current accumulator state. If the bundled snapshot were wrong — wrong roots, wrong height, doctored to hide or invent UTXOs — those proofs simply would not verify as honest peers relay real blocks: signatures would check out but inclusion proofs would fail, and Cardo would reject the chain rather than follow it.

In other words, a bad snapshot doesn't silently corrupt the node's view of Bitcoin; it makes the node unable to follow the real chain at all. The worst plausible failure mode is a stuck or refusing node, not a node that quietly accepts invalid history. This is what makes the `assumeutreexo` trade-off reasonable in practice: you accept faster startup in exchange for a trust assumption that is self-checking against the live network.

If you want zero trust assumptions, a from-genesis IBD (initial block download) without `assumeutreexo` is not currently exposed in the UI.

## Installation

### Requirements
- Android 10 (API 29) or higher
- ARM64 device (`arm64-v8a`) or x86_64 (`x86_64`, e.g. emulators)
- Internet connection

### Releases
Download the latest APK from [GitHub Releases](https://github.com/tricialinewberg/IPE/releases).

> ℹ️ The APK ships native libraries for **`arm64-v8a`** (physical devices) and **`x86_64`** (emulators/CI). 32-bit ABIs (`x86`, `armeabi-v7a`) are not supported.

### Obtainium
[Obtainium](https://github.com/ImranR98/Obtainium) installs and auto-updates apps directly from their GitHub releases — no Play Store or F-Droid required.

**One tap:** with Obtainium installed, open this link on your device to pre-fill the app:

```
obtainium://app/{"id":"com.github.jvsena42.mandacaru","url":"https://github.com/tricialinewberg/IPE","author":"tricialinewberg","name":"Cardo"}
```

**Manual:** in Obtainium, tap **Add App**, paste `https://github.com/tricialinewberg/IPE` into the *App Source URL* field, then tap **Add**.

### From Source
1. Clone the repository:
```bash
git clone https://github.com/tricialinewberg/IPE.git
cd IPE
```

2. Build the project:
```bash
./gradlew assembleDebug
```

3. Install on your device:
```bash
./gradlew installDebug
```

## Usage

### Getting Started
1. Launch the app and enable notifications when prompted. A local wallet (seed phrase) is created automatically on first launch — back it up from **Wallet > Reveal seed phrase**.
2. Let the node sync (Node tab).
3. Fund the wallet with the **Wallet** tab's receive address.
4. Browse or create a CoinJoin pool from the **CoinJoin** tab once you hold a UTXO matching a pool's denomination.

### Node Info Screen
Monitor your node's status:
- **Sync Progress**: Current blockchain synchronization percentage
- **Network Info**: Connected network, peer count, and difficulty
- **Peers** (expandable): View connected peers, connect to new nodes, disconnect, or ping
- **Diagnostics** (expandable): Node uptime and memory usage

### Wallet Screen
- **Balance**: Spendable balance tracked by the node via your wallet's descriptors
- **Receive**: Generate a new BIP84 address, shown with its QR code
- **Backup**: Reveal the seed phrase — this wallet holds real keys on-device, so back it up

### CoinJoin Screen
- **Discover pools**: Announced pools appear as they're seen on connected relays
- **Create a pool**: Pick a denomination; the app finds a matching UTXO from your wallet automatically
- **Join a pool**: Registers a matching UTXO and a fresh output address, then waits for the round to fill and finalize

### Settings Screen
Configure your node:
- **Electrum Address**: Copy the local Electrum server address to pair with your wallet
- **Descriptors**: Add or list wallet descriptors to track your addresses
- **Network**: Switch between Bitcoin networks (requires app restart)
- **Node**: Connect directly to specific Bitcoin nodes
- **About**: App version and project information
- **Donate**: Support the project via Lightning

## Architecture

Built with modern Android development practices:
- **Kotlin**: 100% Kotlin codebase
- **Jetpack Compose**: Declarative UI framework
- **Material Design 3**: Modern, adaptive design system
- **HorizontalPager + BottomNavigationBar**: Swipeable screen navigation
- **MVVM Architecture**: Clean separation of concerns
- **Coroutines & Flow**: Async operations and reactive streams
- **DataStore**: Persistent preferences
- **Koin**: Lightweight dependency injection
- **OkHttp**: Network communication, including Nostr relay WebSocket connections (optionally proxied over Tor/SOCKS)
- **JSON-RPC**: Bitcoin Core compatible RPC interface to the local node
- **BDK (bitcoindevkit)**: BIP39/BIP32 key derivation for the local signing wallet
- **secp256k1-kmp**: ECDSA (coinjoin input signing) and BIP340 Schnorr (Nostr event signing)
- **Hand-rolled BIP143/bech32**: Segwit sighash and address encoding, kept dependency-free and auditable

The local signing wallet is a deliberate departure from the rest of the app's watch-only/airgapped-signing model — Joinstr rounds require each peer to sign live during the round, which isn't possible from an external signer. UTXO data and broadcast still go through the local Floresta node's own RPC, not a separate Electrum client, keeping the "no external server" property intact.

## Related Projects

- [Joinstr (floresta_wallet)](https://github.com/1440000bytes/floresta_wallet) - The coordinator-less CoinJoin design this port is based on
- [Floresta Wallet](https://github.com/jvsena42/floresta_app) - A wallet client running Floresta
- [Floresta Core](https://github.com/vinteumorg/Floresta) - The underlying Floresta implementation
- [Mandacaru](https://github.com/jvsena42/mandacaru) - The watch-only Bitcoin node app this project forked from

## Support the Project

If you find this project useful, consider supporting development:

**Lightning**: `tricia@evento.cash`

## License

This project is open source. Please check the repository for license details.

## Acknowledgments

- Built on top of [Floresta](https://github.com/vinteumorg/Floresta)
- Implements [Utreexo](https://dci.mit.edu/utreexo) accumulator design
- CoinJoin design based on the [Joinstr](https://github.com/1440000bytes/floresta_wallet) protocol
- Coordinated over the [Nostr](https://nostr.com/) protocol
- Inspired by the Bitcoin community's commitment to decentralization and privacy

---

Made with 🌸 for the Bitcoin network
