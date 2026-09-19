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
5. Kill the receiver process during an upload. Restart must clean cache orphans, not claim
   that the old in-memory session was resumed. Retry requires a new approval.
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
3. A raw USB accessory with no IP link must not be shown as a usable endpoint.

## Storage and lifecycle
Repeat receive/open/share/vault on MediaStore and legacy storage devices, with denied legacy
write permission, low space and background restrictions. Verify atomic publication, visible
failures, foreground notification behavior and persistent Activity after relaunch.
