# Physical-device acceptance matrix

Use Android 8/9, 12, and 13–15 devices where available. The JVM tests cover protocol/storage
logic; they do not substitute for these radio and MediaStore checks.

## Transfer and resume
1. Accept a multi-file batch, including a zero-byte file, an unknown-size SAF document,
   duplicate filenames and a file larger than 64 KB. Verify the destination hashes.
2. Interrupt Wi-Fi halfway through the second file. Confirm no truncated file is visible
   in Downloads. Restore the link and Retry: first file is skipped; second starts at its
   verified offset. Decline the retry and confirm no bytes are uploaded.
3. Change the source prefix before Retry. It must restart that file instead of appending.
4. Cancel an encrypted receive during a blocked read and an encrypted send during the
   acceptance prompt. Both should stop immediately. No private partial survives receiver
   cancellation. Completed files remain available.
5. Kill the receiver process during an encrypted upload. Restart must restore its original
   manifest and validated prefix, but no acceptance or AES key. Retry asks for fresh approval
   and uses fresh keys. Change private partial bytes and verify the sender rejects the prefix.
   Repeat after killing the sender: its queue must show a manual Retry with retained SAF access.
   Revoke that grant and confirm a visible failure, not a silently skipped file.
6. Queue three batches, two addressed to the same peer. At most two sends run concurrently;
   the second send to a busy peer waits and starts after that peer's first batch finishes.

## Trust
1. First contact is Unverified. Compare the code after accepting the offer; both must match.
2. Enable code verification on sender only, receiver only, and both. No upload should start
   until required local verification completes. Reject/timeout must not bypass the gate.
3. Enable auto-accept: an authenticated remembered key is accepted; a new key, plaintext
   peer or invalid signature is not. Forget the key and repeat.
4. Exchange pairing codes over a trusted channel. Explicitly confirm the other device's
   fingerprint. Subsequent transfers are Verified only for the matching signing key.
5. Disable encryption while code verification is required. The transfer must fail rather
   than call a plaintext session verified.

## Wi-Fi Direct
1. On two P2P-capable devices without a shared router, select the DNS-SD-discovered device.
   Approve Android's connection prompt. Test both group-owner roles and both send directions.
2. Confirm the selected ID is the one answering at the advertised TCP port, not another
   device sharing a group. Resume after a group disconnect/reconnect.
3. Deny nearby/location access, disable Wi-Fi, turn location services off on older Android,
   and test unsupported hardware. Settings must report a useful error; LAN must not crash.
4. Pause receiving, refresh discovery, rotate the screen and rename the device. No duplicate
   services, stale receiver or unsolicited group connection should remain.

## Bluetooth-assisted discovery
1. Grant optional Bluetooth permissions and enable Bluetooth on both phones. With a shared
   reachable LAN, BLE endpoints should resolve to the same stable IDs as mDNS/beacons.
2. Without a shared IP path, BLE must not pretend to be an RFCOMM payload transport.
3. Deny Bluetooth permissions or disable Bluetooth. LAN discovery/transfers must continue.
   Refresh after re-enabling it. Pause receiving and confirm advertisements stop.

## USB tethering
1. Enable Android USB tethering to a host with a compatible MeshDrop IP peer. Discovery and
   transfers should use the tethered subnet and be labelled USB on the sender.
2. Connect both Wi-Fi and tethering; ensure subnet-based labels select the actual connected
   route rather than treating every endpoint as USB because a cable is attached.
3. An unconnected raw USB accessory must not be labelled as tethering; a successfully opened
   AOA link appears separately and must work with all IP radios disabled.

## Storage and lifecycle
Repeat receive/open/share/vault on MediaStore and legacy storage devices, with denied legacy
write permission, low space and background restrictions. Verify atomic publication, visible
failures, foreground notification behavior and persistent Activity after relaunch.

## Native RFCOMM (no IP)
1. Disable Wi-Fi/mobile data. Enable Bluetooth and pair through Android. Start receiving on
   both phones; select the paired device in Settings. Confirm the linked peer resolves with
   no IP/port and encrypted payloads travel over RFCOMM in both directions.
2. Deny/regrant Bluetooth permission, toggle the radio, disconnect during a large file and
   reconnect/Retry. Compare hashes, check cancellation and test simultaneous sends.
3. Fill the multiplexed stream window with a slow receiver. Identity probing must not tear
   down a healthy busy link. Pause receiving and verify listener/link descriptors close.

## Raw USB AOA (not tethering)
1. Use a data-capable cable and compatible USB host/OTG roles. Select the attached Android
   device on the host; approve USB permission. It switches to AOA and re-enumerates.
2. Refresh Settings and select the new AOA device. On the accessory side approve opening
   MeshDrop. No tethering/network setup should be needed; disable radios and verify transfer.
3. Test both host/accessory directions, reconnect after detach, change USB role, deny access,
   and test a charge-only cable/unsupported device. Failure must be visible and bounded.
4. Verify control and payload progress while another channel stalls; no leaked descriptors
   or busy-loop CPU after detach. Record VID/PID, Android versions, cable and role configuration.

## Swarm and multi-hop
1. Pair sender/final recipient with full fingerprints. Helpers enable helper mode. A helper
   still prompts for each route; declining or turning off/revoking helper mode blocks use.
2. Use two helpers for a multi-chunk file plus an empty file. Confirm both contribute actual
   bytes, only the final destination publishes files, and all whole-file hashes match.
3. Use two/three ordered helpers, with the final destination unreachable directly from the
   source. Configure each next-hop directory (CLI can supply IDs/addresses explicitly).
4. Remove one independent helper after approval. Missing assignments should use another
   approved route; successful completion must have destination-signed receipts.
5. Kill sender and destination between chunks. Recovery must preserve immutable manifests,
   verify private chunks and re-approve/rekey. Helpers always need new grants after their restart.
6. Expire a grant, exhaust a quota, replay an assignment, alter a signed route or ciphertext,
   and substitute the destination identity. None may produce a false successful receipt.

## Desktop
Run `scripts/cli_smoke.py` after `:cli:installDist`, then test Android ↔ desktop in both
roles, pairing, cancellation, retry after Android force-stop, unknown-size documents and
non-ASCII/duplicate filenames. Linux CI does not replace Windows/macOS manual validation.
