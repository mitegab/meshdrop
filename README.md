# MeshDrop — a cross-platform peer-to-peer file-sharing system that discovers nearby devices and transfers files directly without requiring internet, cloud storage, or a central server.

MeshDrop is an Android MVP of a SHAREit-style file drop: two phones on the same Wi-Fi
network find each other, one picks files, the other accepts, and the bytes move
device-to-device over a plain TCP socket. **No accounts, no cloud, no server.**

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

## Feature status — MVP

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
| Live transfer telemetry | ✅ smoothed speed/ETA, lane strip, Canvas throughput graph |
| Runs without internet/cloud/accounts | ✅ LAN only; identity is a random local device id |
| Wi-Fi Direct / Bluetooth / USB-C transport | 🚧 seams in place, see [Adding a transport](#adding-a-transport) |

## Screens

| Screen | What it does |
| --- | --- |
| **Home** | Two giant Send / Receive zones, a live *Nearby devices* strip, and a radar pulse that means "scanning" without a line of text. Tapping a device jumps straight into the file picker. |
| **Send** | Category picker (photos / videos / any file, multi-select) → one-line trust indicator → layered progress → summary. |
| **Receive** | Visible-as card, incoming accept/reject cards (with the safety code shown *before* you decide), download progress, landed files. |
| **History** | Day-grouped feed with thumbnails, provenance (who sent it, verified badge, integrity tick) and swipe to re-share, move to vault or delete. |
| **Settings** | Encryption, verification, vault lock and the no-telemetry statement at the top; transport backends in a collapsed *Transports* section. |

### Design: progressive disclosure

The core rule is that 90% of people want "send this file to that phone, fast" and should never
have to look at a state machine diagram. So:

* **Motion replaces text.** The radar says "I am looking"; the lane strip says "bytes are going
  through *here*"; the assembly bar says "this file is nearly whole". No chart library — the
  throughput graph and route topology are `Canvas` code.
* **One sealed state drives the transfer screen.** `TransferSessionState` (Idle → Discovering →
  AwaitingApproval / IncomingOffer → Active → Finished) is the only thing the UI reads, so the
  screen is a pure function of state.
* **Readouts are smoothed before they reach a composable.** `TransferTelemetry` (domain) applies
  an EMA, quantises to a step ladder and only accepts a new ETA when it moved by more than 1 s
  *and* 15%, so speed and ETA never flicker.
* **Power-user detail is behind one tap.** Swarm peer chips, retransmits, protocol version and
  cipher live in a collapsed *Network details* panel on the transfer card.
* **Security is one line until you ask for more.** "Verified" / "Unverified" / "Not encrypted",
  tap to expand into plain language plus the safety code. No security vocabulary in the default
  path.
* **Dark first.** This is a systems tool used at night next to a router, not a productivity app.

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
* **`WifiDirectDiscoveryStrategy` / `BluetoothDiscoveryStrategy`** — registered but
  `isAvailable = false`. They exist so the UI, the coordinator and the merge logic already
  understand them.

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

Also: `GET /meshdrop/ping` for a liveness probe. Errors are JSON (`{"error": "…"}`) with real
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
   chunk; the nonce is `salt ‖ counter`, so no nonce is ever reused.

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
│   ├── wifidirect/              stub + implementation notes
│   └── bluetooth/               stub + implementation notes
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
./gradlew testDebugUnitTest      # JVM unit tests
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

## Adding a transport (Wi-Fi Direct, Bluetooth, …)

Everything needed is already in place; a new transport is one class and one line:

```kotlin
class WifiDirectDiscoveryStrategy(context: Context, scope: CoroutineScope) : DiscoveryStrategy {
    override val kind = DiscoveryKind.WIFI_DIRECT
    override val peers: StateFlow<List<PeerDevice>> = …   // WifiP2pManager.requestPeers()
    override suspend fun startScan() { … }                // WifiP2pManager.discoverPeers()
}
```

then register it in `AppContainer.discoveryStrategies`. `requestGroupInfo` gives you the
group-owner address: run the **same** `TransferServer` on it and report the port through
`LocalIdentity` — the coordinator, the engine, the protocol and every screen are
unchanged. The same applies to Bluetooth (BLE advertising for discovery, RFCOMM only for
the "let's set up a group" control message, files still over TCP).

## Known limitations of the MVP

* **Encryption without a PKI.** There is no certificate authority here: trust comes from humans
  comparing six digits once. Skip the comparison and you are encrypted but technically open to
  an active man-in-the-middle on the LAN — which is exactly why the UI says *Unverified* and
  never shows a padlock until you do.
* **No resume.** A dropped transfer restarts from zero; per-chunk Merkle verification (so a
  partial transfer could be resumed and re-checked) is the natural next step after integrity
  is already per-chunk.
* Single batch at a time per peer; concurrent senders queue on the server's accept loop.
* Swarm splitting and relay routing are modelled (`PeerContribution`, lane failover, route
  topology) but not yet driven by the transport — the UI is ready, the scheduler is not.
* The transfer log that History renders lives in memory for the session; vaulted files,
  verified peers and preferences are persisted.
* Cancelling a *receiving* transfer stops new files; the file already in flight finishes.

## Why Compose + coroutines + zero network dependencies

The app is Kotlin + Jetpack Compose with `StateFlow` all the way down, and the entire
networking layer is `java.net` sockets plus `org.json`. That keeps the APK small, makes the
protocol trivially portable to a future desktop/CLI client, and means the interesting code
is MeshDrop's own — not a framework's.
