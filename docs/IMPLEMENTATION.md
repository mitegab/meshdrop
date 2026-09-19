# Remaining-feature iterations

Baseline: `438e8fc`. This checklist distinguishes shipped code from designs and hardware validation.
The README's old limitations are not an implementation inventory: history persistence,
queueing, SHA-256, batch retry and lane monitoring already exist.

## Iteration 1 — reliable transfers
- [ ] Close live sending/receiving sockets on cancellation and stop.
- [ ] Do not report incomplete batches as successful or discard resumable state on failure.
- [ ] Reserve scheduler slots before launching workers; preserve whole manifests on retry.
- [ ] Never reuse GCM nonces across files; bound encrypted frame allocations.

## Iteration 2 — partial-file resume
- [ ] Stage received bytes privately and publish only complete files.
- [ ] Report offsets and SHA-256 prefix checksums; verify prefixes before appending.
- [ ] Retain interrupted bytes for same-process retries; discard them on cancellation.
- [ ] Test framing, staging and protocol edge cases.

## Iteration 3 — connection setup
- [ ] Replace Wi-Fi Direct placeholder with DNS-SD discovery and explicit group connection.
- [ ] Replace Bluetooth placeholder with BLE service discovery.
- [ ] Expose connection errors and permission requirements without breaking LAN discovery.

## Not covered by the existing transport protocol
True multi-hop relay, multi-source swarm reassembly, raw USB accessory transfers,
Bluetooth RFCOMM payload transfer, and cross-process resumable sessions require additional
protocol and consent design. They must not be advertised as implemented by a discovery
adapter or a planning model. USB tethering is an IP network, not a raw USB transport.

## Validation
This sandbox initially has neither a JDK nor an Android SDK. Attempts to install them
failed because Debian, Google and Maven endpoints were unreachable. Record tests added
separately from tests actually run. Android CI is configured to assemble and run JVM tests;
radio behavior additionally requires physical devices.
