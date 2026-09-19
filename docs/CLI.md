# Desktop CLI

Build with JDK 17 and the repository Gradle configuration (the full build also configures
Android and needs the Android SDK):

```sh
./gradlew :cli:installDist :cli:test
cli/build/install/meshdrop/bin/meshdrop --help
```

The `meshdrop-cli` CI artifact contains launchers in `bin/` and jars in `lib/`; keep both.
Use `bin/meshdrop` on Linux/macOS (`chmod +x` after artifact extraction if needed), or
`bin\meshdrop.bat` on Windows. Only Linux runtime is exercised by CI.

## Identity, trust and direct transfer

```sh
meshdrop identity --name Laptop
meshdrop trust 'MD1-...the other devices CURRENT pairing code...'
meshdrop receive --out ./received --port 45821
meshdrop send --host 192.168.1.23 --port 45821 ./photo.jpg ./video.mp4
```

A receiver prints its device ID, listening port and **full** SHA-256 fingerprint. Pairing
codes can be imported in Android Settings. Verify them through an already trusted channel.
Without a pin, direct transfer asks for an interactive safety-code comparison; EOF is a
rejection. `--pin FULL_SHA256` explicitly pins a destination from a trusted source.

`receive --accept-verified` accepts encrypted, signature-validated file offers from saved
identities. It never enables plaintext acceptance or approves helper routes.

State defaults to `~/.meshdrop`. `--home DIR` selects a separate identity/state directory.
One process at a time may own it (file lock); independent helper instances use separate homes.
The private PKCS#8 identity is permission-restricted, not hardware-backed or password-encrypted.
Protect/back up the directory appropriately, especially on platforms with different ACL behavior.

## Durable queue and retries

```sh
meshdrop queue
meshdrop retry BATCH_UUID --host 192.168.1.23 --port CURRENT_PORT
meshdrop forget BATCH_UUID
```

A failed send retains the batch ID, original manifest and file URIs. Retry negotiates new
keys and receiver approval. Override stale IP/port values after the receiver restarts.
`forget` removes the outgoing record and mesh source snapshot, not a receiver's saved files.
Changed/missing sources and expired manifests fail visibly; start a new batch in that case.

## Independent helper sources (swarm)

Each helper needs a directory entry for its fixed next destination:

```sh
# On helper A; B uses its own identity/home and port.
meshdrop receive --home ./helper-a --out ./received-a --port 45822 --helper \
  --peer DESTINATION_ID@192.168.1.23:45821

# On the sender (destination fingerprint must have been verified out of band).
meshdrop send --destination DESTINATION_ID --pin DESTINATION_FULL_SHA256 \
  --route HELPER_A_ID --route HELPER_B_ID \
  --peer HELPER_A_ID@192.168.1.24:45822 \
  --peer HELPER_B_ID@192.168.1.25:45823 ./large-file.bin
```

Helpers prompt for each source/route/quota. The destination separately approves the files.
The source snapshots/hashes files, assigns chunks, stages encrypted bytes at helpers and
asks them to deliver. Only a destination-signed receipt counts as progress/success.

## Ordered multi-hop

```sh
meshdrop send --destination DESTINATION_ID --pin DESTINATION_FULL_SHA256 \
  --route FIRST_ID,SECOND_ID \
  --peer FIRST_ID@192.168.1.24:45822 ./large-file.bin
```

FIRST must know SECOND through its own `--peer` entry; SECOND must know DESTINATION.
The sender only needs FIRST's address. Consent for later hops is requested through earlier
approved grants. IDs cannot repeat; up to three helpers per route and four routes per send.
All forwarding is restricted to MeshDrop mesh/relay operations, not arbitrary TCP or URLs.

## Native stream adapter

`--link /dev/rfcomm0` uses an already configured duplex OS character device and speaks
MeshDrop framing directly. Android listens on secure RFCOMM service UUID
`bb906069-6cd4-40c1-9395-31c79a32a349`. Pair/configure the RFCOMM endpoint using the host OS's
Bluetooth tools; the CLI does not manage BlueZ/macOS/Windows pairing or bundle libusb.
AOA host/accessory adapters are currently Android-specific.

## Automated subprocess check

```sh
python3 scripts/cli_smoke.py
```

This creates independent identity directories, transfers through a deliberately interrupting
loopback fixture, kills the receiving JVM, starts a new one, retries the saved batch and checks
exact bytes/queue cleanup. The fixture proxy is **test-only**, not the production relay feature.
