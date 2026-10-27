# MeshDrop

> A fast, secure, cross-platform peer-to-peer file-sharing tool. Transfer files directly between devices over local networks, Wi-Fi Direct, Bluetooth, or USB—no internet connection, cloud services, or accounts required.

MeshDrop consists of an Android application and a lightweight JVM desktop CLI. Payloads move directly between endpoints with zero-knowledge helper delivery, end-to-end encryption, and no centralized telemetry.

---

```text
┌───────────────────────────── Local Network / Direct Link ────────────────────────────┐
│                                                                                      │
│   ┌──────────────────────┐                                ┌──────────────────────┐   │
│   │  Sender (Phone / PC) │                                │ Receiver (Phone / PC)│   │
│   │                      │    1. mDNS / UDP Discovery     │                      │   │
│   │  Discover peers  ◄───┼────────────────────────────────┼──►  Listen on port   │   │
│   │                      │                                │                      │   │
│   │  POST /handshake ────┼────────────────────────────────┼──►  Session request  │   │
│   │    (ECDH-P256 keys)  │                                │     (User accepts)   │   │
│   │                      │    2. Session Key Derivation   │                      │   │
│   │  PUT /upload/<id> ───┼══════ AES-256-GCM chunks ══════┼──►  Downloads/       │   │
│   │  POST /complete  ────┼────────────────────────────────┼──►  MeshDrop         │   │
│   └──────────────────────┘                                └──────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Features

- **Direct Transfers:** Zero cloud relay. Files stream directly over local Wi-Fi, Wi-Fi Direct, Bluetooth RFCOMM, or Android Open Accessory (AOA) USB connections.
- **End-to-End Encryption:** Ephemeral ECDH (P-256) key agreement with HKDF-SHA256 and chunked AES-256-GCM authenticated encryption.
- **Peer Verification:** 6-digit Short Authentication String (SAS) safety codes and pinned cryptographic identity fingerprints.
- **Atomic File Delivery:** Streams to private staging storage with transactional publication so interrupted transfers never leave corrupted files behind.
- **Resilient Transfers:** Resume interrupted batches with deduplication and chunk manifests.
- **Swarm & Relay Routing:** Optional multi-hop delivery via intermediate helper nodes without exposing file contents.
- **Built-in Vault:** Secure app-private storage on Android gated by biometric or device credentials.
- **Zero Telemetry:** No tracking, no crash-reporting SDKs, and no external calls.

---

## Security Architecture

MeshDrop establishes a mutually authenticated, forward-secret session before transferring data:

1. **Long-Term Device Identity:** Each installation generates an isolated P-256 identity key stored in the platform keystore (Android Keystore or local credential store). Peer fingerprints are derived from `SHA-256(PublicKey)`.
2. **Ephemeral Key Exchange:** For every transfer session, sender and receiver negotiate fresh ephemeral P-256 keypairs signed by their identity keys.
3. **Key Derivation (HKDF-SHA256):** Devices derive distinct transmission keys (`send`, `receive`) and a 6-digit verification code (SAS) for out-of-band visual verification.
4. **Framed Encryption (AES-256-GCM):** Data streams in 64 KB authenticated chunks (`length ‖ nonce ‖ ciphertext ‖ tag`). Tampered or missing chunks immediately fail integrity verification and abort the transfer.
5. **Trust State:**

| Trust Level | State | Description |
| :--- | :--- | :--- |
| **Verified** | Green | SAS code confirmed or identity key previously pinned. |
| **Encrypted (Unverified)** | Yellow | Channel is encrypted, but peer identity has not yet been confirmed. |
| **Unencrypted** | Red | Encryption disabled or communicating with legacy endpoint. |

---

## Getting Started

### Android

1. Download and install the latest APK from Releases.
2. Launch MeshDrop on two devices connected to the same Wi-Fi network (or Wi-Fi Direct).
3. Select files, tap **Send**, choose the target peer, and approve the prompt on the receiving device.
4. Compare safety codes on both devices to verify the cryptographic session.

### Desktop CLI

The desktop client runs on any machine with JDK 17+ installed.

```bash
# Build the CLI distribution
./gradlew :cli:installDist

# Run interactive CLI
./cli/build/install/meshdrop/bin/meshdrop
```

See [docs/CLI.md](docs/CLI.md) for full parameter references, headless helper mode, and serial endpoint configuration.

---

## Android Permissions Reference

| Permission | Scope | Purpose |
| :--- | :--- | :--- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | All | Local socket operations and LAN peer discovery. |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | All | Multicast lock retention for mDNS service discovery. |
| `NEARBY_WIFI_DEVICES` | Android 13+ | Wi-Fi Direct discovery without location access. |
| `ACCESS_FINE_LOCATION` | Android ≤ 12 | Required by legacy Android OS for local Wi-Fi / Bluetooth scans. |
| `POST_NOTIFICATIONS` | Android 13+ | Background transfer status and transfer prompt actions. |
| `FOREGROUND_SERVICE_DATA_SYNC` | All | Prevents process termination during active file transfers. |

---

## Project Structure

```text
app/src/main/kotlin/com/meshdrop/
├── MainActivity.kt              Single-Activity host
├── di/                          Dependency injection and runtime controllers
├── core/
│   ├── model/                   Core state machines, transfer jobs, and security models
│   ├── prefs/                   Encrypted preferences and pinned identities
│   └── util/                    Formatters and transfer telemetry
├── discovery/
│   ├── api/                     Discovery coordinator interfaces
│   ├── nsd/                     mDNS / DNS-SD discovery engine
│   ├── udp/                     Broadcast beacon fallback
│   ├── wifidirect/              Wi-Fi P2P negotiation
│   └── bluetooth/               BLE discovery and RFCOMM transports
├── transfer/
│   ├── protocol/                Framed wire protocol and message schemas
│   ├── server/                  Local receive server
│   ├── client/                  Upload pipeline and connection orchestrator
│   ├── crypto/                  Key exchange, frame encryption, and SAS generators
│   └── TransferEngine.kt        Central transfer queue and job orchestration
├── data/                        Scoped storage, staging buffers, and secure vault
└── ui/                          Jetpack Compose UI (Material 3)
```

---

## Building from Source

### Prerequisites
- JDK 17 or higher
- Android SDK (`compileSdk = 35`, `minSdk = 26`)

```bash
# Compile debug APK
./gradlew assembleDebug

# Run unit tests across Android and CLI modules
./gradlew testDebugUnitTest :cli:test

# Run CLI integration smoke tests
python3 scripts/cli_smoke.py
```

---

## Technical Specifications & Limits

- **Chunk Sizes:** Default 64 KB framing for direct streams; 1 MiB chunk manifests for swarm delivery.
- **Relay Caps:** Maximum of 3 helper hops per relay path, 4 concurrent helper channels, and a 1 GiB per-helper quota limit.
- **Atomic Operations:** Received files are streamed to temporary staging buffers and published via Android's `MediaStore` / `FileProvider` only after all chunk authentication tags validate.
- **Network Stack:** Native `java.net` non-blocking sockets and standard cryptography libraries—minimal external runtime dependencies.

For detailed wire protocol specifications and helper node negotiation, refer to [docs/MESH_PROTOCOL.md](docs/MESH_PROTOCOL.md).

---

## License

MeshDrop is distributed under the Apache 2.0 License. See `LICENSE` for details.
