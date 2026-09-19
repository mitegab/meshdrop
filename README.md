# MeshDrop — a cross-platform peer-to-peer file-sharing system that discovers nearby devices and transfers files directly without requiring internet, cloud storage, or a central server.

MeshDrop is an Android app and a JVM desktop CLI for local, device-to-device file transfer.
It supports LAN/Wi-Fi Direct, native Bluetooth RFCOMM and raw USB AOA byte streams, plus
consented encrypted helper delivery. **No accounts, no cloud, no central server.**
Radio/USB implementations still require physical-device validation; see the test matrix below.

```
┌───────────────────────────── same Wi-Fi network ─────────────────────────────┐
│                                                                              │
│   ┌──────────────────────┐                        ┌──────────────────────┐   │
│   │  Phone A  (sender)   │                        │  Phone B (receiver)  │   │
│   │                      │   1. mDNS / UDP beacon │                      │   │
│   │  discover peers  ◄───┼────────────────────────┼──►  advertise :port  │   │
│   │                      │                        │                      │   │
│   │  POST /handshake ────┼────────────────────────┼──►  "4 files, 12 MB?" │   │
│   │       (long poll)    │                        │      user accepts     │   │
│   │                      │   2. accept / reject   │                      │   │
│   │  PUT /upload/<id> ───┼══════ raw file bytes ══┼──►  Downloads/MeshDrop│   │
│   │  POST /complete  ────┼────────────────────────┼──►                   │   │
│   └──────────────────────┘                        └──────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────┘
```

## Feature status

| Capability | State |
| --- | --- |
| Discover devices on the same Wi-Fi | ✅ mDNS/NSD + UDP broadcast beacon fallback |
| Pick files and send device-to-device | ✅ SAF picker → TCP upload, any file type, any size |
| Nearby device list | ✅ live, merged across transports, expires stale peers |
| Progress, speed, ETA, success/failure | ✅ per file and per batch |
| Receiver accepts/rejects | ✅ in-app card **and** a notification with Accept/Reject |
| End-to-end encryption | ✅ ECDH-P256 → HKDF → AES-256-GCM, one tag per 64 KB chunk |
| Peer verification | ✅ six digit safety code (SAS) + remembered identity fingerprints |
| Vault for received files | ✅ app-private storage, gated by the device lock |
| Live transfer telemetry | ✅ smoothed speed/ETA, steadiness, Canvas throughput graph — one tap away in *Details* |
| Runs without internet/cloud/accounts | Local IP or native byte streams; identity keys stay on the device |
| Wi-Fi Direct | Implemented: DNS-SD service discovery, explicit group connection, endpoint identity probe; physical-device validation pending |
| Bluetooth | BLE-assisted IP discovery **and** separate secure RFCOMM control/payload transport with no-IP identity bootstrap |
| USB | Raw Android Open Accessory host/accessory streams; USB tethering remains a separate IP option |
| Resume interrupted files | Durable encrypted-batch manifests, rehashed private prefixes/chunks and fresh keys/approval after restart |
| Persistent activity and queued sends | Private history and durable outgoing queue; recovered sends wait for explicit Retry |
| Swarm | Signed 1 MiB chunk assignments, independent helper sources, destination reassembly and signed receipts |
| Multi-hop relay | Explicit per-route consent, fixed next peer, at most 3 helpers, replay/loop checks and byte/cache/concurrency limits |
| Desktop/CLI | JDK 17 client/receiver/helper sharing the Android protocol and crypto; Linux serial/RFCOMM endpoint adapter |
| Trust controls and pairing codes | Verification gate, authenticated trusted-device auto-accept, explicit pairing-code import/export |

## Screens

The interface is three surfaces, not five screens: one where a drop happens, one where drops
are remembered, one where the promises are checked. The reasoning, the tokens and the
per-state specs live in **[design/DESIGN.md](design/DESIGN.md)**, with a clickable prototype at
**[design/prototype.html](design/prototype.html)** that implements the same tokens and motion
rules as the Compose code.

| Surface | What it does |
| --- | --- |
| **Drop** | The whole app in one page: who this phone is, what is happening right now, the tray, who is nearby, what moved recently. Send and Receive are the same act seen from two sides — the file picker, the incoming offer and the connection details are sheets raised over it, never destinations of their own. |
| **Activity** | Everything sent and received: search over files and devices, four filters that say what they filter, day groupings, and a detail sheet with the provenance of a batch (security, transport, batch id) and the actions — open, share, move to vault, delete. |
| **Settings** | Privacy and trust stated as consequences ("Off. Anything you send can be read by anyone on this Wi-Fi network."), the no-server statement in prose, this phone, devices you trust, and transports folded away at the bottom. |

### Design: what the interface believes

* **A drop is one act, seen from two sides.** There is no Send screen and no Receive screen to
  choose between before you know what you are doing. Staging files and accepting an offer are
  the same event on the same surface — which is what the protocol already believed.
* **One accent.** Clay means "tap this" or "these are the bytes you are waiting for". Nothing
  decorative is ever clay, and no state is carried by colour alone: every coloured state also
  has a word and a glyph.
* **One loud thing per screen.** Exactly one filled button, and it names its consequence
  (*Send to Ana's Pixel*). Motion answers "is anything happening" and nothing else — a
  breathing dot while waiting, a progress rule while moving, both of which stop when nothing
  is happening. There is no animation that has to be decoded.
* **Chrome is a hairline, never a shadow.** Grouping comes from type and space, so the screens
  read as a page rather than a stack of panels.
* **Readouts are smoothed before they reach a composable.** `TransferTelemetry` (domain)
  applies an EMA, quantises to a step ladder, and only accepts a new ETA when it moved by more
  than 1 s *and* 15%, so speed and ETA never flicker. The speed line and the retransmit count
  live behind one tap, in the transfer's *Details* disclosure.
* **Security is one line until you ask for more.** "Verified" / "Unverified" / "Not encrypted",
  tap to expand into plain language plus the safety code. No security vocabulary in the
  default path.
* **Nothing is hidden behind a gesture.** Actions live in the detail sheet, in words. The old
  swipe-to-act history is gone, because an action you have to already know about is not an
  action.
* **Paper by default, ink on request.** This is a phone handed between two people in a kitchen,
  in daylight. The dark theme is designed rather than inverted, and follows the system.

## How it works

### 1. Discovery

Discovery is a **strategy list**, not a hard-coded mechanism:

```kotlin
interface DiscoveryStrategy {
    val kind: DiscoveryKind
    val status: StateFlow<StrategyStatus>
    val peers: StateFlow<List<PeerDevice>>
    suspend fun startAdvertising(identity: LocalIdentity)
    suspend fun startScan()
    suspend fun stopScan()
}
```

* **`NsdDiscoveryStrategy`** — mDNS/DNS-SD via `NsdManager`, service type `_meshdrop._tcp.`
  (the same family of mechanism printers and Chromecasts use). Resolutions are queued
  because `NsdManager` handles one at a time, a multicast lock is held so replies are not
  filtered, and known services are re-resolved periodically so peers do not go stale.
* **`UdpBeaconDiscoveryStrategy`** — a one-packet `MESHDROP/1|<id>|<port>|<name>` broadcast
  every 3 s on UDP 45821. It is the fallback for routers, guest networks and OEM skins that
  break mDNS, and it costs a few bytes per second.
* **`WifiDirectDiscoveryStrategy`** — advertises the same identity through Wi-Fi P2P
  DNS-SD. Selecting a peer forms a group through Android's approval flow. The owner endpoint
  is identity-probed; when this phone is the owner, discovery waits for the peer's IP beacon.
* **`BluetoothDiscoveryStrategy`** — advertises a seven-byte IP endpoint in BLE service
  data and resolves identity with the existing TCP ping. It is a LAN discovery fallback,
  not an RFCOMM file transport, and requires a reachable local IP network.
* Discovery and payload transport are distinct: P2P and USB-tethered IP interfaces are
  identified by their connected subnet so Activity does not call a cable transfer Wi-Fi.

`DiscoveryCoordinator` merges everything into one list: duplicates (the same physical device
found by two transports) are collapsed by device id preferring the entry that can actually be
connected to, and anything silent for 20 s is dropped.

### 2. Transfer

A deliberately tiny HTTP/1.1 dialect over TCP — `Http.kt` is ~200 lines, has **no
dependencies**, and is readable with `nc`:

| Step | Request | Notes |
| --- | --- | --- |
| 1 | `POST /meshdrop/handshake` | JSON batch description; the receiver **long-polls** up to 120 s until a human taps Accept or Reject |
| 2 | `PUT /meshdrop/upload/<batchId>/<fileId>` | one connection per file, raw bytes, `Content-Length` framing |
| 3 | `POST /meshdrop/complete?batchId=…` | tidy-up, reports how many files landed |

Also: `GET /meshdrop/ping` for a liveness probe, `GET /meshdrop/status?batchId=…`
for completed files and verified resume prefixes, and `GET /meshdrop/ready?batchId=…`
for an optional receiver verification gate. The gate is advertised in the acceptance
response so new senders wait before uploading. Older senders are still gated at the receiver.

Interrupted payloads are staged in app-private durable storage, never published to Downloads. A retry
keeps the original manifest, negotiates fresh keys, skips complete files and sends only the
suffix whose prefix checksum matches both devices. `Range: bytes=<offset>-` is accompanied
by `X-MeshDrop-Prefix-SHA256`; invalid offsets or prefixes are rejected. Incomplete batches
are not finalized. Receiver cancellation closes sockets and deletes partials; completed
receipts are retained for idempotent completion retries. Encrypted, signature-validated batches
have checksummed atomic journals; restoring never restores acceptance or session keys.
Idle receiver journals expire lazily after 24 hours and are limited to 64; admission limits
also bound open connections. Unsigned transfers remain process-local.

Errors are JSON (`{"error": "…"}`) with real
status codes (`403` rejected, `408` no answer, `426` protocol too new).

Throughput is measured with a windowed average, UI updates are throttled to ~10/s so a
64 KB chunk loop does not thrash recomposition, and cancellation is polled between chunks so
a tap on *Cancel* stops within one buffer.

### 3. Storage, done by the book

* **Sending** uses the Storage Access Framework (`OpenMultipleDocuments`): content Uris, so
  no `READ_EXTERNAL_STORAGE` / `READ_MEDIA_*` permission at all, and it works for any
  document provider the user has installed.
* **Receiving** writes to `Downloads/MeshDrop` through **MediaStore** on Android 10+ (no
  permission), with a legacy public-Downloads path guarded by `WRITE_EXTERNAL_STORAGE` on
  Android 8–9, and an app-private fallback if public storage is unavailable. Files are
  inserted with `IS_PENDING = 1` and only published once the last byte has landed, so a
  failed transfer never leaves a truncated file behind.
* Sharing/opening a received file goes through `FileProvider` (no `file://` Uris).

### 4. Security

Every transfer negotiates an ephemeral session before a single byte moves:

1. Each device holds a **long-term P-256 identity key** in the Android keystore
   (`DeviceIdentity`, alias `meshdrop_identity_v1`). Its fingerprint is the first six bytes of
   `SHA-256(pubkey)`, printed as `4f:2a:91:0c:7d:e3`.
2. The sender offers a **fresh ephemeral P-256 key** in the handshake, signed with the identity
   key. The receiver does the same when it accepts.
3. Both sides run ECDH and derive three keys with **HKDF-SHA256** (`meshdrop/v1/send`,
   `/receive`, `/sas`): a send key, a receive key, and the **safety code** — six digits the two
   humans compare out loud.
4. The payload is framed as `length ‖ nonce ‖ ciphertext+tag` with **AES-256-GCM** per 64 KB
   chunk; the nonce is `salt ‖ counter`. Streaming counters are allocated across all files,
   not reset per file. Frame sizes are bounded before allocation.
5. Remembered trust uses the fingerprint **derived from the signing key**, never a claimed
   fingerprint alone. Auto-accept requires encryption, a valid signature and remembered
   identity. With verification required, compare codes from the active transfer before bytes
   move; an unverified session times out rather than bypassing the setting.

What the user sees of that:

| State | Shown as | Meaning |
| --- | --- | --- |
| Safety code compared (or peer remembered) | **Verified** | Nobody else could have derived these keys |
| Encrypted, first contact | **Unverified · encrypted** | Encrypted, but nothing proves who is on the other end yet |
| No key material offered | **Not encrypted** | Older MeshDrop peer, or encryption switched off |

Integrity is not a promise: a tampered chunk fails its GCM tag, the file is aborted, and the
receiver is told *"Integrity check failed: the bytes were modified in transit"*. A completed
file whose every tag checked out is marked **integrity checked** in History.

Encryption has a real kill-switch (Settings → *End-to-end encryption*), verified peers can be
forgotten, and there is nothing to switch off under **No telemetry** — MeshDrop has no server,
no analytics and no crash reporting to send anything to.

### 5. Permissions

| Permission | Needed on | Why |
| --- | --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | all | any socket traffic, LAN included |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | all | multicast lock so mDNS replies survive |
| `NEARBY_WIFI_DEVICES` (`neverForLocation`) | 13+ | nearby-device discovery without location |
| `ACCESS_FINE_LOCATION` | ≤ 32 | on those versions local discovery lives in the location group |
| `POST_NOTIFICATIONS` | 13+ | accept a transfer or follow progress from anywhere |
| `WRITE_EXTERNAL_STORAGE` | ≤ 28 | legacy save-to-Downloads |
| `FOREGROUND_SERVICE(_DATA_SYNC)` | all | keep the receiver alive while the screen is off |

The version-dependent set lives in a single place (`AppPermissions`) and is requested once
when the app starts; the Home screen shows a banner with a plain-language rationale if
anything critical is still missing.

## Project layout

```
app/src/main/kotlin/com/meshdrop/
├── MainActivity.kt              single-Activity host
├── MeshDropApplication.kt       process entry point
├── di/                          AppContainer (graph) + MeshController (the API the UI uses)
├── core/
│   ├── model/                   PeerDevice, TransferJob, IncomingRequest, SecurityState,
│   │                            TransferSessionState (the sealed state machine the UI renders)
│   ├── prefs/                   local identity, encryption/verification/vault preferences
│   └── util/                    formatting, smoothing + telemetry (pure Kotlin)
├── discovery/
│   ├── api/                     DiscoveryStrategy + DiscoveryCoordinator  ← extension point
│   ├── nsd/                     mDNS implementation
│   ├── udp/                     broadcast beacon fallback
│   ├── wifidirect/              Wi-Fi P2P DNS-SD and group setup
│   └── bluetooth/               BLE-assisted IP discovery
├── transfer/
│   ├── protocol/                Http.kt (framing) + TransferProtocol.kt (messages, v2)
│   ├── server/                  TransferServer (receive side)
│   ├── client/                  TransferClient (send side)
│   ├── crypto/                  DeviceIdentity, SessionNegotiator, SecureFrames, VerifiedPeers
│   ├── state/                   TransferSessionState + the mapper from jobs to sessions
│   └── TransferEngine.kt        application-scoped state: jobs, decisions, speed, keys
├── data/                        document reading, saving, spooling, vault
├── net/                         local address / broadcast helpers
├── notifications/ + service/    channels, incoming-request notification, foreground service
└── ui/
    ├── MeshDropRoot.kt          NavHost + permission gate
    ├── home/ send/ receive/     the flow: hub, picker + progress, accept + download
    ├── history/ settings/       day-grouped feed; security-first settings
    ├── components/              motion (radar, lanes, assembly bar), Canvas graphs,
    │                            trust indicator, swipe rows, transport icon set
    └── theme/                   Material 3 theme — dark by default
```

State that must survive rotation and navigation (the server, the job list, pending
decisions) lives in the **application scope**; screens are thin and only render flows.

## Building

```bash
./gradlew assembleDebug          # debug APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest :cli:test  # Android-core and desktop JVM tests
./gradlew :cli:installDist       # cli/build/install/meshdrop/{bin,lib}
python3 scripts/cli_smoke.py     # real subprocess encrypted transfer + killed-receiver recovery
```

Requirements: JDK 17 and the Android SDK (AGP downloads the platform/build-tools for you).
`compileSdk 35`, `minSdk 26`, `targetSdk 35`. Android Studio Hedgehog or newer opens the
project directly.

Toolchain: Kotlin 2.0.21 · AGP 8.5.2 · Gradle 8.7 · Compose BOM 2024.10.01 ·
Navigation 2.8.3 · Material 3. Dependencies are centralised in
`gradle/libs.versions.toml`.

> The checked-in `gradle-wrapper.jar` is an older bootstrap that downloads Gradle 8.7.
> Run `gradle wrapper --gradle-version 8.7` once with a local Gradle to refresh it if you
> prefer a wrapper built by 8.7 itself.

## Trying it out

1. Install on two devices on the same Wi-Fi (two emulators on the same virtual network
   work too).
2. Grant nearby-device (or location, before Android 13) and notification permissions.
3. On both: open MeshDrop. Each phone appears in the other's **Nearby devices** list within
   a few seconds.
4. On the sender: **Send → pick a device → choose files → Send**.
5. On the receiver: **Accept** (either in the app or from the notification). Files land in
   **Downloads → MeshDrop**.
6. Tap the trust line on either side and compare the six digits out loud. If they match, tap
   **Codes match** — that device is *Verified* from then on and no longer needs comparing.

`adb logcat -s MeshDrop:*` shows the whole conversation, including the HTTP requests the
peer is making.

## Adding a transport

Implement `DiscoveryStrategy`, register it in `AppContainer`, and override `connect(peer)`
when discovery does not initially provide an IP endpoint. `DiscoveryCoordinator.connect`
resolves a selected device before the engine queues its files. A `PeerDevice.transferLane`
records the payload route independently of how the peer was discovered. Do not mark a
Bluetooth advertisement or a USB cable as a usable file transport without a working endpoint.
`DuplexConnection`/`ConnectionFactory` decouple control and payloads from IP. A native
`FramedLink` is a flow-controlled stream multiplexer; `LinkRegistry` probes identity directly
over it. Android's `BluetoothTransport` and `UsbTransport` are registered in `AppContainer`.

## Swarm, relay and desktop use

1. Exchange **current pairing codes** with the final destination using a trusted channel.
   Helper delivery requires a full SHA-256 identity pin; old short-fingerprint codes alone
   are deliberately insufficient. Comparing a new direct-transfer safety code also saves a full pin.
2. Helpers enable **Settings → Helper mode**. The default is off. Each route still asks
   separately, naming the source fingerprint, next peer and byte quota. Approve on Drop or
   Settings; revoke grants from Settings.
3. On Drop choose **Swarm** for independent helpers or **Relay chain** for ordered hops.
   Select a distinct final destination, pick helpers, then Send. The destination approves
   its own files separately. Helpers handle encrypted assignments, not plaintext files.
4. See [docs/CLI.md](docs/CLI.md) for desktop installation, direct sends, offline route
   directories, helper commands and retry. See [docs/MESH_PROTOCOL.md](docs/MESH_PROTOCOL.md)
   for authenticated wire messages, resource limits and recovery behavior.

## Limitations and validation

* **No PKI or automatic trust.** Discovery names/IDs are not authentication. Exchange full
  pairing codes securely or compare safety codes. Metadata (names, sizes, identities and
  route) is visible to consenting helpers; payloads are encrypted to the final destination.
* **Explicit, bounded routes**, not an automatic mesh-routing/distributed-storage network.
  Four helper paths, three helper hops per path, four source assignments in flight,
  1 MiB chunks, at most 8,192 chunks and 1,024 files per mesh manifest. Helper grants are
  30 minutes, at most 1 GiB **wire traffic** each, with a 32 MiB staging cache and two forwards
  at once. The conservative source quota estimate can reject large batches; split them.
* **Durable recovery is not restored consent.** Encrypted batches recover immutable metadata
  and verified bytes with new approval/keys. Outgoing jobs require explicit Retry. Revoked
  or non-persistable document access fails visibly; the user must select those files again.
  Helper permissions and AES keys never persist. Cache/journal expiration can require a new batch.
* **Publication has a crash window.** Receipts make normal retries idempotent, but a crash
  between OS publication and durable receipt creation can leave a duplicate on retry.
  Exactly-once MediaStore/filesystem publication across process death is not claimed.
* **Extra private storage** is needed for source snapshots, receiver chunks/prefixes and
  final assembly. Local filesystem permissions protect CLI keys; they are not hardware-backed.
* **Hardware validation pending:** RFCOMM permissions/pairing, USB AOA roles/re-enumeration,
  OEM behavior, Wi-Fi Direct, MediaStore, background execution and real detach/reconnect.
  Desktop raw USB/libusb integration is not bundled; AOA host/accessory adapters currently
  live on Android. The CLI supports TCP and OS-configured duplex serial/RFCOMM endpoints.
* Build/test evidence is in [docs/IMPLEMENTATION.md](docs/IMPLEMENTATION.md); the manual
  matrix is [docs/DEVICE_TESTS.md](docs/DEVICE_TESTS.md). Implementation is not hardware validation.

## Why Compose + coroutines + zero network dependencies

The app is Kotlin + Jetpack Compose with `StateFlow` all the way down, and the entire
networking layer is `java.net` sockets plus `org.json`. That keeps the APK small, makes the
protocol shared with the JVM desktop/CLI client, and means the interesting code
is MeshDrop's own — not a framework's.
