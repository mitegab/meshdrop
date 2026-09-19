# Remaining-feature iterations

Baseline: `438e8fc`. This checklist distinguishes implementation, automated validation and
physical-device validation. The original README's limitations were stale: persistent
history, queueing, whole-file SHA-256, batch retry and lane monitoring already existed.

## Iteration 1 — reliable transfers
- [x] Close live sending/receiving sockets on cancellation and server stop.
- [x] Preserve failed batches; require completion acknowledgement before reporting success.
- [x] Reserve scheduler slots before launching workers; pump after freeing a slot.
- [x] Retry/failover with the original full manifest and fresh session keys.
- [x] Keep GCM streaming nonces unique across files; bound frame/header/control allocations.
- [x] Derive trusted fingerprints from the signing key, not the peer's claimed fingerprint.

## Iteration 2 — partial-file resume
- [x] Stage received bytes privately and publish only complete files.
- [x] Report offsets and SHA-256 prefix checksums; verify prefixes before appending.
- [x] Retain disconnected partials for same-process retries; discard on cancellation or failed tags.
- [x] Retain completed receipts for idempotent completion and lost-ack retries.
- [x] Bound retained batches, expire idle partials on handshake and clean process-death orphans.
- [x] Add real socket/disk tests with a platform-independent receive-storage boundary.

## Iteration 3 — discovery and connection setup
- [x] Replace Wi-Fi Direct placeholder with DNS-SD discovery and explicit group connection.
- [x] Probe the selected group owner's identity; wait for the client's beacon when locally owning the group.
- [x] Replace Bluetooth placeholder with compact BLE-assisted IP discovery.
- [x] Handle optional Bluetooth permissions independently of LAN requirements.
- [x] Classify connected P2P/USB-tethered subnets separately from discovery mechanisms.
- [x] Show live transport status in Settings; prevent duplicate sends during connection setup.
- [ ] Validate radio behavior on physical devices; see [DEVICE_TESTS.md](DEVICE_TESTS.md).

## Iteration 4 — enforce trust settings
- [x] Wire authenticated remembered-device auto-accept into receiver approval.
- [x] Wire required safety-code verification into both transfer directions.
- [x] Negotiate a receiver readiness gate so new clients wait before uploading.
- [x] Expose pairing-code export/import with explicit fingerprint confirmation in Settings.
- [x] Reject malformed manifests, duplicate file IDs, negative sizes and size-sum overflow.

## Iteration 5 — native no-IP byte transports

- [x] Shared duplex-connection seam, bounded stream multiplexing and no-IP identity ping.
- [x] Secure RFCOMM listener/client, explicit paired-device selection and connect timeout.
- [x] Android AOA host negotiation (requests 51/52/53), accessory descriptors, bulk streams,
  explicit permission/selection and detach cleanup. Not USB tethering.
- [x] Settings controls and unified discovery/status/transport routing.
- [x] Socket-backed framing tests: bidirectional channels, large streams, credits,
  cancellation, stream caps and malicious credit violations.
- [ ] Physical radios/cables/OEM validation.

## Iteration 6 — durable recovery

- [x] Checksummed, fsync'd atomic journals, hashed batch paths and safe partial-file reopening.
- [x] Actual signing-key ownership binds encrypted receiver journals, not claimed fingerprints.
- [x] Durable manifests/receipt metadata; rehash actual prefixes/chunks on recovery.
- [x] SAF read-grant retention and outgoing queue restoration as review/Retry, not silent sending.
- [x] New approval and fresh session keys; no persisted AES keys or accepted decisions.
- [x] Lazy retention/caps, corrupt-journal rejection, explicit cancellation cleanup.
- [x] CLI subprocess test cuts a real encrypted upload and kills/restarts the receiver.
- [ ] Exactly-once publication across the OS-publish/journal-write crash window is not promised.

## Iteration 7 — actual helper data delivery

- [x] Canonically signed immutable manifests bind sender, full destination identity, expiry,
  file hashes, chunk hashes and bounds.
- [x] AES-GCM AAD binds manifest/file/index; destination-signed chunk and completion receipts.
- [x] Helpers stage encrypted assignments and forward them as independent data sources;
  the distinct final recipient verifies/reassembles. Failed assignments can move to another path.
- [x] Explicit helper-mode switch plus per-route consent, expiry/revocation, fixed known
  next-hop IDs, signature/replay/loop checks, request+response byte budgets and cache/worker caps.
- [x] Ordered multi-hop grant setup and allowlisted MeshDrop RPCs; no arbitrary TCP proxy.
- [x] Android route/helper controls, approval cards, actual helper progress and queue integration.
- [x] Real-socket tests for two helper sources, two hops, restored chunks, tampering,
  stolen grants, arbitrary endpoint rejection, replay, revocation and quotas.

## Iteration 8 — shared JVM desktop CLI

- [x] `:cli` compiles shared protocol/client/server/crypto/storage code (not a protocol reimplementation).
- [x] Persistent P-256 identity/trust, safe filesystem sinks, identity/pairing export, send/receive,
  manual peer directories, swarm/multi-hop helpers, durable queue/retry and serial-stream adapter.
- [x] Helper consent is always interactive; `--accept-verified` applies only to incoming files.
- [x] JDK 17 distribution and Android APK uploaded by CI; desktop storage tests and smoke script.
- [ ] Windows/macOS runtime and native desktop USB/Bluetooth SDK integration need separate validation.

## Validation record

- Local `./gradlew testDebugUnitTest` cannot start: no JDK/Android SDK in the sandbox. Debian,
  Google, Maven and GitHub release-asset toolchain downloads were unavailable.
- GitHub CI **does** work. Initial run `35462379720` caught three Kotlin `ConcurrentHashMap`
  membership compile errors; explicit `containsKey` calls fixed them.
- Commit `8b7521a`: [Android CI run 35462619306](https://github.com/mitegab/meshdrop/actions/runs/35462619306)
  passed **assembleDebug** and **testDebugUnitTest**, and uploaded the debug APK.
- The encrypted-resume regression exposed a reply-before-writer-release race in run
  `35462963784`. Upload replies are now published only after private writer state is settled.
- Final code commit `88e491d`: [Android CI run 35463663448](https://github.com/mitegab/meshdrop/actions/runs/35463663448)
  passed **assembleDebug** and **testDebugUnitTest**, and uploaded the debug APK.
- The final suite covers encrypted prefix resume with fresh keys, in-flight receive cancellation,
  pending-approval cancellation on stop, strict manifest parsing, readiness negotiation, malformed
  HTTP, BLE packet encoding/local-address limits, pairing, spoofed remembered fingerprints and
  IP-route classification. The suite contains 292 JVM test methods across 36 test files.
- `git diff --check` passed and the Android manifest was parsed successfully as XML locally.

Automated success is not a claim that Wi-Fi P2P, BLE, USB tethering or Android MediaStore have
been exercised on physical hardware in this environment.

### New transport/mesh/CLI iterations

- `b1f92f4`: [CI 35464770484](https://github.com/mitegab/meshdrop/actions/runs/35464770484)
  passed the Android build and existing JVM tests after native transport extraction.
- `06e3cc2`: the first combined build found a missing shared `Format` dependency in `:cli`.
  The source-set boundary was corrected rather than duplicating protocol code.
- `55efc35`: [CI 35465726560](https://github.com/mitegab/meshdrop/actions/runs/35465726560)
  passed Android APK + CLI distribution builds, Android JVM tests, CLI smoke commands and
  both artifact uploads. This includes real-socket swarm/two-hop tests and framing/recovery tests.
- Subsequent iterations add actual subprocess kill/retry, desktop storage tests, stronger
  cancellation/expiry checks, and verification-gated recovery assembly. Their final CI
  result is recorded below once complete.

These checks run on Linux JVMs. They do not certify USB AOA or Bluetooth hardware behavior.
