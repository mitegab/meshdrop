package com.meshdrop.transfer

import com.meshdrop.core.model.EndpointRole
import com.meshdrop.core.model.EncryptionMode
import com.meshdrop.core.model.IncomingRequest
import com.meshdrop.core.model.PeerDevice
import com.meshdrop.core.model.TransferDirection
import com.meshdrop.core.model.TransferFile
import com.meshdrop.core.model.TransferJob
import com.meshdrop.core.model.SecurityState
import com.meshdrop.core.model.TransferStatus
import com.meshdrop.core.storage.SpaceGuard
import com.meshdrop.core.storage.VolumeSpace
import com.meshdrop.core.storage.SpaceVerdict
import com.meshdrop.core.util.MeshLog
import com.meshdrop.data.DocumentRepository
import com.meshdrop.data.FileSpooler
import com.meshdrop.data.ReceivedFileStore
import com.meshdrop.transfer.client.SendOutcome
import com.meshdrop.transfer.client.TransferClient
import com.meshdrop.transfer.crypto.BatchCrypto
import com.meshdrop.transfer.crypto.DeviceIdentity
import com.meshdrop.transfer.crypto.SecurityOffer
import com.meshdrop.transfer.crypto.SessionKeys
import com.meshdrop.transfer.crypto.SessionNegotiator
import com.meshdrop.transfer.crypto.TrustedPeers
import com.meshdrop.transfer.server.IncomingTransferHandler
import com.meshdrop.transfer.server.TransferServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-scoped brain of MeshDrop.
 *
 * Owns the long-lived state that must survive screen rotations and navigation:
 *  * the [TransferServer] (and therefore the port we advertise)
 *  * the list of active/recent [jobs] and pending [incoming] requests
 *  * the decision bookkeeping that lets the UI accept or reject a batch
 *
 * Everything below runs on the injected [scope], which is a `SupervisorJob` scope owned by
 * the Application: a failed transfer can never take the whole app down.
 */
class TransferEngine(
    private val scope: CoroutineScope,
    documentRepository: DocumentRepository,
    fileSpooler: FileSpooler,
    fileStore: ReceivedFileStore,
    identity: DeviceIdentity,
    trustedPeers: TrustedPeers,
    private val localDeviceId: String,
    private val localDeviceName: () -> String,
    /** Read live from settings: switching encryption off affects the next transfer. */
    private val encryptionEnabled: () -> Boolean = { true },
    /**
     * The volume incoming files land on. Read per transfer rather than once at startup: free
     * space changes while the app is open, and a stale reading is how a check ends up
     * approving something that no longer fits.
     */
    private val storageVolume: () -> VolumeSpace = { VolumeSpace.UNKNOWN },
    private val now: () -> Long = { System.currentTimeMillis() },
) : IncomingTransferHandler {

    private val client = TransferClient(documentRepository, fileSpooler)

    /** Ephemeral ECDH + AES-256-GCM sessions, one per batch. */
    private val negotiator = SessionNegotiator(identity, trustedPeers)
    private val batchCrypto = ConcurrentHashMap<String, BatchCrypto>()

    private val _security = MutableStateFlow<Map<String, SecurityState>>(emptyMap())

    /** Live security state per batch: what the trust indicator and the padlock render. */
    val security: StateFlow<Map<String, SecurityState>> = _security.asStateFlow()

    fun securityFor(batchId: String): SecurityState = _security.value[batchId] ?: SecurityState.UNKNOWN

    private val server = TransferServer(
        fileStore = fileStore,
        handler = this,
        scope = scope,
        deviceId = { localDeviceId },
        deviceName = { localDeviceName() },
    )

    private val _jobs = MutableStateFlow<List<TransferJob>>(emptyList())
    val jobs: StateFlow<List<TransferJob>> = _jobs.asStateFlow()

    private val _incoming = MutableStateFlow<List<IncomingRequest>>(emptyList())
    val incoming: StateFlow<List<IncomingRequest>> = _incoming.asStateFlow()

    /** batchId → the deferred the handshake is suspended on. */
    private val pendingDecisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Job ids the user cancelled. */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    private val speedTracker = SpeedTracker()
    private val lastUiUpdate = ConcurrentHashMap<String, Long>()

    /** Port our server listens on (0 while stopped). Discovery advertises this. */
    val localPort: Int get() = server.port

    // region server lifecycle -------------------------------------------------------------

    /** Starts the receiver and returns the port it is listening on (0 on failure). */
    fun startReceiving(): Int {
        val port = server.start()
        MeshLog.i(TAG, "startReceiving() → port $port")
        return port
    }

    fun stopReceiving() = server.stop()

    // endregion

    // region sending ----------------------------------------------------------------------

    /**
     * Pushes [files] to [peer]. Returns immediately with the job id: the transfer itself
     * runs in the background and reports through [jobs].
     */
    suspend fun send(peer: PeerDevice, files: List<TransferFile>): String = withContext(Dispatchers.Default) {
        val jobId = UUID.randomUUID().toString()
        val job = TransferJob(
            id = jobId,
            direction = TransferDirection.SEND,
            peerName = peer.name,
            peerId = peer.id,
            files = files.map { it.copy(status = TransferStatus.WAITING) },
            totalBytes = files.sumOf { it.sizeBytes.coerceAtLeast(0L) },
            status = TransferStatus.WAITING,
            startedAtMillis = now(),
        )
        _jobs.update { list -> list + job }

        // Files with an unknown size get spooled to our own cache first, which needs room for
        // a second copy. Checking before the first byte beats explaining a half-sent video.
        val spools = files.any { it.sizeBytes <= 0L }
        val space = SpaceGuard.forVolume(job.totalBytes, storageVolume(), spooled = spools)
        if (!space.fits) {
            MeshLog.w(TAG, "Job $jobId refused: ${space.message}")
            finishJob(jobId, TransferStatus.FAILED, space.message)
            return@withContext jobId
        }

        // No crypto object at all when encryption is switched off: the client then sends a
        // handshake with no security block, and the peer shows "Not encrypted".
        if (encryptionEnabled()) {
            batchCrypto[jobId] = BatchCrypto(negotiator, EndpointRole.SENDER)
        }
        MeshLog.i(TAG, "Queued send job $jobId (${job.totalBytes} bytes → ${peer.name})")

        scope.launch(Dispatchers.IO) { runSendJob(jobId, peer, files) }
        jobId
    }

    private suspend fun runSendJob(jobId: String, peer: PeerDevice, files: List<TransferFile>) {
        updateJob(jobId, force = true) { it.copy(status = TransferStatus.IN_PROGRESS) }

        val outcome: SendOutcome = try {
            client.send(
                peer = peer,
                batchId = jobId,
                senderId = localDeviceId,
                senderName = localDeviceName(),
                files = files,
                crypto = batchCrypto[jobId],
                isCancelled = { jobId in cancelled },
                onFileStarted = { fileId -> setFileStatus(jobId, fileId, TransferStatus.IN_PROGRESS) },
                onFileProgress = { fileId, bytesSent, sizeBytes, bytesPerSecond ->
                    reportSendProgress(jobId, fileId, bytesSent, sizeBytes, bytesPerSecond)
                },
                onFileFinished = { fileId, success, message ->
                    setFileStatus(
                        jobId = jobId,
                        fileId = fileId,
                        status = if (success) TransferStatus.COMPLETED else TransferStatus.FAILED,
                        message = message,
                    )
                },
            )
        } catch (error: Throwable) {
            MeshLog.e(TAG, "Send job $jobId crashed", error)
            finishJob(jobId, TransferStatus.FAILED, error.message)
            return
        }

        recordSecurity(jobId)

        when {
            !outcome.accepted -> finishJob(jobId, TransferStatus.REJECTED, outcome.message)
            jobId in cancelled -> finishJob(jobId, TransferStatus.CANCELLED, "Cancelled")
            outcome.outcomes.any { !it.success } -> {
                val failed = outcome.outcomes.firstOrNull { !it.success }
                finishJob(jobId, TransferStatus.FAILED, failed?.message)
            }

            outcome.report?.integrityFailed == true ->
                finishJob(jobId, TransferStatus.FAILED, outcome.report?.message)

            else -> finishJob(jobId, TransferStatus.COMPLETED, null)
        }
    }

    private fun reportSendProgress(
        jobId: String,
        fileId: String,
        bytesSent: Long,
        sizeBytes: Long,
        bytesPerSecond: Long,
    ) {
        updateJob(jobId) { job ->
            val files = job.files.map { file ->
                if (file.id == fileId) file.copy(bytesTransferred = bytesSent, status = TransferStatus.IN_PROGRESS) else file
            }
            val transferred = files.sumOf { it.bytesTransferred }
            job.copy(
                files = files,
                bytesTransferred = transferred,
                totalBytes = job.totalBytes.coerceAtLeast(sizeBytes),
                bytesPerSecond = bytesPerSecond,
                status = TransferStatus.IN_PROGRESS,
            )
        }
    }

    // endregion

    // region receiving --------------------------------------------------------------------

    override suspend fun requestApproval(request: IncomingRequest): Boolean {
        // Receiving is where a full disk actually bites: the sender has already spent the
        // time and the only honest outcome is a truncated file. Spooled = true because a
        // file of unknown size is written to a scratch directory before it is published.
        val space = SpaceGuard.forVolume(request.totalBytes, storageVolume(), spooled = true)
        if (space.verdict == SpaceVerdict.NOT_ENOUGH) {
            val reason = space.message ?: "Not enough space"
            MeshLog.w(TAG, "Declining ${request.batchId}: $reason")
            _jobs.update { list ->
                list + TransferJob(
                    id = request.batchId,
                    direction = TransferDirection.RECEIVE,
                    peerName = request.senderName,
                    peerId = request.senderId,
                    files = request.files.map { it.copy(status = TransferStatus.FAILED) },
                    totalBytes = request.totalBytes,
                    status = TransferStatus.FAILED,
                    message = reason,
                    startedAtMillis = now(),
                    finishedAtMillis = now(),
                )
            }
            return false
        }

        val decision = CompletableDeferred<Boolean>()
        pendingDecisions[request.batchId] = decision
        _incoming.update { list ->
            list.filterNot { it.batchId == request.batchId } +
                if (space.verdict == SpaceVerdict.TIGHT) request.copy(spaceWarning = space.message) else request
        }
        MeshLog.i(TAG, "Incoming batch ${request.batchId} from ${request.senderName}")

        val accepted = try {
            decision.await()
        } finally {
            pendingDecisions.remove(request.batchId)
            _incoming.update { list -> list.filterNot { it.batchId == request.batchId } }
        }

        if (accepted) {
            _jobs.update { list ->
                list + TransferJob(
                    id = request.batchId,
                    direction = TransferDirection.RECEIVE,
                    peerName = request.senderName,
                    peerId = request.senderId,
                    files = request.files.map { it.copy(status = TransferStatus.WAITING) },
                    totalBytes = request.totalBytes,
                    status = TransferStatus.IN_PROGRESS,
                    startedAtMillis = now(),
                )
            }
        }
        return accepted
    }

    /** User tapped Accept. */
    fun approve(batchId: String) {
        MeshLog.i(TAG, "User accepted $batchId")
        pendingDecisions[batchId]?.complete(true)
    }

    /** User tapped Reject. */
    fun reject(batchId: String) {
        MeshLog.i(TAG, "User rejected $batchId")
        pendingDecisions[batchId]?.complete(false)
        pendingDecisions.remove(batchId)?.cancel()
        _incoming.update { list -> list.filterNot { it.batchId == batchId } }
    }

    override fun negotiateKeys(batchId: String, peerMaterial: SecurityOffer?): SessionKeys? {
        if (!encryptionEnabled()) {
            MeshLog.i(TAG, "Batch $batchId: plaintext (encryption switched off in settings)")
            recordSecurity(batchId)
            return null
        }
        val crypto = batchCrypto.getOrPut(batchId) { BatchCrypto(negotiator, EndpointRole.RECEIVER) }
        val keys = crypto.establish(peerMaterial)
        recordSecurity(batchId)
        MeshLog.i(TAG, "Batch $batchId: ${if (keys != null) "encrypted, code ${keys.safetyCode}" else "plaintext"}")
        return keys
    }

    override fun localMaterialFor(batchId: String): SecurityOffer? =
        batchCrypto[batchId]?.localMaterial()

    override fun sessionKeyFor(batchId: String): ByteArray? =
        batchCrypto[batchId]?.sessionKeys()?.receiveKey

    /**
     * The user compared the safety codes on both devices and they matched: remember this
     * peer so every future transfer with it says "Verified" instead of "Unverified".
     */
    fun confirmSafetyCode(batchId: String, peerLabel: String) {
        val keys = batchCrypto[batchId]?.sessionKeys() ?: return
        negotiator.trustPeer(keys.peerFingerprint, peerLabel)
        updateSecurity(batchId) { it.copy(codeCompared = true, peerRemembered = true) }
        MeshLog.i(TAG, "User verified $peerLabel (${keys.peerFingerprint})")
    }

    override fun onFileStarted(batchId: String, fileId: String) {
        setFileStatus(batchId, fileId, TransferStatus.IN_PROGRESS)
    }

    override fun onFileProgress(batchId: String, fileId: String, bytesReceived: Long, sizeBytes: Long) {
        updateJob(batchId) { job ->
            val files = job.files.map { file ->
                when {
                    file.id != fileId -> file
                    else -> file.copy(
                        bytesTransferred = bytesReceived,
                        sizeBytes = file.sizeBytes.takeIf { it > 0L } ?: sizeBytes,
                        status = TransferStatus.IN_PROGRESS,
                    )
                }
            }
            val transferred = files.sumOf { it.bytesTransferred }
            job.copy(
                files = files,
                bytesTransferred = transferred,
                bytesPerSecond = speedTracker.sample(batchId, transferred, now()),
                status = TransferStatus.IN_PROGRESS,
            )
        }
    }

    override fun onFileCompleted(batchId: String, fileId: String, uri: String?, landedName: String?) {
        setFileStatus(batchId, fileId, TransferStatus.COMPLETED, localUri = uri, name = landedName)
        if (batchCrypto[batchId]?.sessionKeys() != null) {
            // Every GCM tag checked out, otherwise decryptStream would have thrown.
            updateSecurity(batchId) { it.copy(integrityConfirmed = true) }
        }
        completeJobIfDone(batchId)
    }

    override fun onBatchIntegrityFailed(batchId: String, fileIds: List<String>) {
        val message = when (fileIds.size) {
            1 -> "Integrity check failed on ${fileIds.first()}"
            else -> "Integrity check failed on ${fileIds.size} files"
        }
        MeshLog.w(TAG, "Batch $batchId: $message")
        fileIds.forEach { setFileStatus(batchId, it, TransferStatus.FAILED, message = message) }
        finishJob(batchId, TransferStatus.FAILED, message)
    }

    override fun onFileFailed(batchId: String, fileId: String, reason: String) {
        setFileStatus(batchId, fileId, TransferStatus.FAILED, message = reason)
        finishJob(batchId, TransferStatus.FAILED, reason)
    }

    // endregion

    // region shared state management ------------------------------------------------------

    fun cancel(jobId: String) {
        MeshLog.i(TAG, "Cancelling $jobId")
        cancelled += jobId
        server.forget(jobId)
        reject(jobId)
        finishJob(jobId, TransferStatus.CANCELLED, "Cancelled")
    }

    /** Removes finished jobs from the visible list (they stay in memory for one session). */
    fun clearFinished() {
        val activeIds = _jobs.value.filter { it.isActive }.map { it.id }.toSet()
        _jobs.update { list -> list.filter { it.isActive } }
        _security.update { map -> map.filterKeys { it in activeIds } }
        batchCrypto.keys.retainAll(activeIds)
    }

    fun job(jobId: String): TransferJob? = _jobs.value.firstOrNull { it.id == jobId }

    /** Copies the agreed session into the UI-facing [SecurityState] for this batch. */
    private fun recordSecurity(batchId: String) {
        val keys = batchCrypto[batchId]?.sessionKeys()
        updateSecurity(batchId) { current ->
            current.copy(
                mode = if (keys != null) EncryptionMode.E2E_AES_256_GCM else EncryptionMode.NONE,
                safetyCode = keys?.safetyCode,
                peerRemembered = keys?.peerRemembered ?: false,
                peerFingerprint = keys?.peerFingerprint,
            )
        }
    }

    private fun updateSecurity(batchId: String, transform: (SecurityState) -> SecurityState) {
        _security.update { map -> map + (batchId to transform(map[batchId] ?: SecurityState.UNKNOWN)) }
    }

    private fun setFileStatus(
        jobId: String,
        fileId: String,
        status: TransferStatus,
        message: String? = null,
        localUri: String? = null,
        name: String? = null,
    ) {
        updateJob(jobId, force = status.isFinished) { job ->
            job.copy(
                files = job.files.map { file ->
                    if (file.id != fileId) file
                    else file.copy(
                        status = status,
                        localUri = localUri ?: file.localUri,
                        name = name?.takeIf { it.isNotBlank() } ?: file.name,
                    )
                },
                message = message ?: job.message,
            )
        }
    }

    private fun completeJobIfDone(jobId: String) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        if (job.files.isNotEmpty() && job.files.all { it.status.isFinished }) {
            val failed = job.files.firstOrNull { it.status == TransferStatus.FAILED }
            finishJob(
                jobId,
                if (failed != null) TransferStatus.FAILED else TransferStatus.COMPLETED,
                failed?.let { "Some files failed" },
            )
        }
    }

    private fun finishJob(jobId: String, status: TransferStatus, message: String?) {
        speedTracker.forget(jobId)
        updateJob(jobId, force = true) { job ->
            job.copy(
                status = status,
                message = message,
                bytesPerSecond = if (status.isFinished) 0L else job.bytesPerSecond,
                finishedAtMillis = now(),
            )
        }
        MeshLog.i(TAG, "Job $jobId finished: $status ${message ?: ""}")
    }

    /**
     * Applies [transform] to one job, throttling emissions while a transfer is streaming so
     * the UI recomposes at a human friendly rate instead of once per 64 KB chunk.
     */
    private fun updateJob(jobId: String, force: Boolean = false, transform: (TransferJob) -> TransferJob) {
        val timestamp = now()
        if (!force) {
            val previous = lastUiUpdate.putIfAbsent(jobId, timestamp) ?: 0L
            if (previous != 0L && timestamp - previous < UI_UPDATE_INTERVAL_MS) return
            lastUiUpdate[jobId] = timestamp
        } else {
            lastUiUpdate.remove(jobId)
        }
        _jobs.update { list ->
            list.map { job -> if (job.id == jobId) transform(job) else job }
        }
    }

    /** Exponential moving average of throughput, sampled every few hundred milliseconds. */
    private class SpeedTracker {
        private data class Sample(var bytes: Long, var timestamp: Long, var bytesPerSecond: Long)

        private val samples = ConcurrentHashMap<String, Sample>()

        fun sample(key: String, totalBytes: Long, timestamp: Long): Long {
            val sample = samples.getOrPut(key) { Sample(totalBytes, timestamp, 0L) }
            val elapsed = timestamp - sample.timestamp
            if (elapsed >= SAMPLE_WINDOW_MS && elapsed > 0L) {
                val instant = ((totalBytes - sample.bytes) * 1000L) / elapsed
                sample.bytesPerSecond = if (sample.bytesPerSecond == 0L) {
                    instant
                } else {
                    (sample.bytesPerSecond / 2) + (instant / 2)
                }
                sample.bytes = totalBytes
                sample.timestamp = timestamp
            }
            return sample.bytesPerSecond.coerceAtLeast(0L)
        }

        fun forget(key: String) = samples.remove(key)
    }

    companion object {
        const val TAG = "TransferEngine"
        private const val UI_UPDATE_INTERVAL_MS = 100L
        private const val SAMPLE_WINDOW_MS = 400L
    }
}
