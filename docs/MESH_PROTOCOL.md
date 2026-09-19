# Native links, signed mesh delivery and durable recovery

## Native link framing

RFCOMM and USB AOA carry `MESHDROP-LINK/1\n`, followed by frames:
`type:u8 | stream:i32BE | length:i32BE | payload`. OPEN=1, DATA=2, CLOSE=3, CREDIT=4.
Initiator stream IDs are odd; responder IDs are even; IDs increase and are never reused.
DATA is at most 32 KiB. Each direction starts with four frame credits; consuming a complete
frame grants one credit. Eight channels bound per-link buffering. Excess credit/data,
invalid headers or prefaces terminate the physical link. A blocked physical write has a
30-second watchdog; channel reads default to 120 seconds.

Each channel is a `DuplexConnection` and carries the same HTTP framing as TCP. An identity
ping over the link bootstraps the peer without DNS or an IP address. BLE discovery and USB
tethering are independent IP mechanisms, not substitutes for these native adapters.

Android AOA host mode uses GET_PROTOCOL(51), SEND_STRING(52), START(53), then explicitly
opens the re-enumerated Google's accessory VID/PID bulk interface. Accessory mode opens
Android's accessory descriptor. OS permission and user selection are required.

## Signed immutable manifest

Mesh RPC is POST-only under `/meshdrop/mesh/{identity,offer,status,chunk,complete}`.
Signed JSON has `payload`, base64 X.509 `publicKey`, and base64 ECDSA `signature`.
Canonical payload encoding sorts object keys, preserves array order, uses JSON quoting
and integer numbers, and signs UTF-8 `meshdrop-signed-v1\n` plus that encoding.

A manifest binds version, batch ID, sender ID/name, **full destination public-key SHA-256**,
expiry, chunk size, and each file's ID/name/type/length/whole hash/ordered chunk hashes.
Bounds: 1 MiB chunks, at most 8,192 total chunks, 1,024 files, unique file IDs, 24-hour lifetime.
Empty files have no chunks and the SHA-256 of an empty byte sequence.

Fresh signed ephemeral ECDH material accompanies each offer, separately from the immutable
manifest. The sender must already trust the final destination's full identity pin. The
receiver verifies both signatures belong to the manifest owner and asks for file approval.
The destination signs acceptance, binding the manifest hash and a fresh in-memory session
capability. Neither that capability nor AES keys are restored from disk.

## Chunk assignment, reassembly and receipts

Each encrypted chunk binds `meshdrop-chunk-v1\n<manifest hash>\n<file ID>\n<index>` as
AES-256-GCM AAD and uses a fresh random 96-bit nonce. Helpers see ciphertext and metadata,
not the ECDH content key. A receiver authenticates/decrypts, checks the signed chunk hash,
and fsyncs a private chunk before acknowledging. Duplicate received chunks are idempotent.

A source schedules at most four assignments concurrently across up to four approved paths.
The first helper stages the signed assignment on bounded private disk and then dispatches
it down its approved route. Distinct helpers are actual encrypted-chunk sources to the
final receiver. Failed assignments can be reassigned to another approved path. Staging is
temporary; it is not a distributed archival store or a process-resumable helper permission.

The destination signs chunk receipts with batch/manifest/file/index/digest, and completion
receipts with file digests/lengths. The sender verifies against the pinned destination key;
a helper's claimed success is insufficient. Ordered reassembly verifies the whole-file hash
before publication. Existing verification settings gate network acceptance and recovered
assembly, including zero-byte files.

## Consented relay, not a general proxy

`/meshdrop/relay/{offer,forward,stage,dispatch}` uses source-signed requests with UUID and
fresh timestamp. Helper mode is off by default. A human approves each grant, bound to:

- Full source signing-key hash, ordered route IDs and this helper's position.
- One fixed known next peer, never a source-supplied URL/host/port/CONNECT destination.
- At most three helpers; unique IDs and a strictly advancing signed hop position.
- At most 30 minutes and 1 GiB wire-byte budget (requests **and** replies).

At intermediate hops only signed next-hop offer/forward operations are permitted; the last
helper only forwards the destination mesh allowlist. Replies are bounded at 4 MiB, reserved
against quota before forwarding, with unused reservation refunded. Cache is capped at 32 MiB
and 16 staged assignments; two forwards run concurrently. At most 16 grants and eight pending
consents are kept. Recent assignment IDs reject replay, with a bounded 32,768-entry window.

Revocation clears grants/staged assignments and closes active outgoing RPCs; already
forwarded/committed data cannot be recalled. Process restart always needs fresh grants.
The receiver/relay RPC admission semaphore limits concurrent decoded bodies to two.
These are explicit routes, not automatic mesh topology discovery or arbitrary routing.

## Durable recovery and trust

Direct encrypted transfers persist their immutable file metadata, verified full sender key
fingerprint, private partial basenames and completion receipts. Atomic JSON journals contain
checksummed payloads; writes fsync and replace the previous file. Partial basenames/canonical
parents are validated. Actual file length and SHA-256 are recomputed rather than trusting
saved offsets. Client and server both verify a resumed prefix. Plaintext/unauthenticated
batches do not gain cross-process resume privileges.

Mesh destinations persist signed immutable manifests, chunk files and publication receipts.
A fresh offer must match the same manifest and signing key. Restored chunks are rehashed,
and fresh receiver approval/ECDH/verification is required. No saved "accepted" flag is used.
Sender snapshots allow efficient random chunk reads and are checked on retry.

Outgoing queues preserve original URIs/manifests/routes; Android asks the SAF provider for
persistable read grants. Restored jobs are failed/interrupted cards requiring explicit Retry.
Missing/revoked URI access fails rather than auto-selecting other content. Journals/cache have
lazy one-day expiry; direct receiver journals are capped at 64 and outgoing queue records at 256.

Atomic journals detect torn/corrupt metadata, not a hostile actor who already controls the
application's private filesystem. Publication plus receipt persistence is not one OS-wide
transaction: death between the two can produce a duplicate on retry. No exactly-once claim
is made for this window. Hardware keystore-backed identities are Android-specific; desktop
identities rely on private filesystem access controls.
