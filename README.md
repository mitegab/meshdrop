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
| Runs without internet/cloud/accounts | ✅ LAN only; identity is a random local device id |
| Wi-Fi Direct / Bluetooth transport | 🚧 seams in place, see [Adding a transport](#adding-a-transport) |

## Screens

| Home | Send | Receive |
| --- | --- | --- |
| Who you are, Send/Receive tiles, live nearby devices, recent activity | Pick files → pick a device → watch progress (speed, ETA, cancel) | Visible-as card, incoming accept/reject cards, download progress, open received files |

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

### 4. Permissions

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
│   ├── model/                   PeerDevice, TransferJob, IncomingRequest, …
│   ├── prefs/                   local identity (random id + display name)
│   └── util/                    formatting (pure Kotlin) + logging
├── discovery/
│   ├── api/                     DiscoveryStrategy + DiscoveryCoordinator  ← extension point
│   ├── nsd/                     mDNS implementation
│   ├── udp/                     broadcast beacon fallback
│   ├── wifidirect/              stub + implementation notes
│   └── bluetooth/               stub + implementation notes
├── transfer/
│   ├── protocol/                Http.kt (framing) + TransferProtocol.kt (messages)
│   ├── server/                  TransferServer (receive side)
│   ├── client/                  TransferClient (send side)
│   └── TransferEngine.kt        application-scoped state: jobs, decisions, speed
├── data/                        document reading, saving, spooling
├── net/                         local address / broadcast helpers
├── notifications/ + service/    channels, incoming-request notification, foreground service
└── ui/
    ├── MeshDropRoot.kt          NavHost + permission gate
    ├── home/ send/ receive/     three screens, each with a ViewModel
    ├── components/              shared cards, rows, chips
    └── theme/                   Material 3 theme (brand palette, Material You on 12+)
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
4. On the sender: **Send → Choose files → pick a device → Send**.
5. On the receiver: **Accept** (either in the app or from the notification). Files land in
   **Downloads → MeshDrop**.

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
`LocalIdentity` — the coordinator, the engine, the protocol and all three screens are
unchanged. The same applies to Bluetooth (BLE advertising for discovery, RFCOMM only for
the "let's set up a group" control message, files still over TCP).

## Known limitations of the MVP

* **No encryption or pairing yet.** Any device on the LAN can offer you files, which is why
  every transfer requires an explicit accept. TLS with a pinned/verified peer certificate
  (or at least a SAS-style code comparison) is the natural next step.
* **No resume.** A dropped transfer restarts from zero.
* Single batch at a time per peer; concurrent senders queue on the server's accept loop.
* Transfer history lives in memory for the session (it is not persisted across restarts).
* Cancelling a *receiving* transfer stops new files; the file already in flight finishes.

## Why Compose + coroutines + zero network dependencies

The app is Kotlin + Jetpack Compose with `StateFlow` all the way down, and the entire
networking layer is `java.net` sockets plus `org.json`. That keeps the APK small, makes the
protocol trivially portable to a future desktop/CLI client, and means the interesting code
is MeshDrop's own — not a framework's.
