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

## Still not implemented

These are NOT checked off by the discovery adapters, a UI label or the existing domain models:

- [ ] Multi-source swarm data transfer and reassembly: requires authenticated chunk manifests,
  assignment/receipt messages and a final destination distinct from helper peers.
- [ ] Multi-hop relay: requires explicit relay consent, routing/loop limits, resource quotas
  and end-to-end authentication through a helper. An unrestricted TCP proxy is not a safe substitute.
- [ ] Raw USB accessory/host protocol. USB tethering is an OS-managed IP link, not accessory mode.
- [ ] Bluetooth RFCOMM payload/control transport and no-IP bootstrap. Current BLE discovery needs
  a shared reachable IP network; offline connection setup is Wi-Fi Direct.
- [ ] Cross-process transfer resume/queue recovery: requires durable manifests and content-URI
  grants, partial-file journal validation and approval/session recovery. Current resume is session-scoped.
- [ ] Desktop/CLI client. The protocol is portable, but this repository still ships an Android app.

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
