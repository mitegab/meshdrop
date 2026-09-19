package com.meshdrop.di

import android.app.Application
import android.os.StatFs
import com.meshdrop.core.prefs.UserPreferences
import com.meshdrop.core.storage.VolumeSpace
import com.meshdrop.data.DocumentRepository
import com.meshdrop.data.TransferLogFile
import com.meshdrop.data.VaultStore
import com.meshdrop.data.FileSpooler
import com.meshdrop.data.ReceivedFileStore
import com.meshdrop.discovery.api.DiscoveryCoordinator
import com.meshdrop.discovery.api.DiscoveryStrategy
import com.meshdrop.discovery.bluetooth.BluetoothDiscoveryStrategy
import com.meshdrop.discovery.nsd.NsdDiscoveryStrategy
import com.meshdrop.discovery.udp.UdpBeaconDiscoveryStrategy
import com.meshdrop.discovery.wifidirect.WifiDirectDiscoveryStrategy
import com.meshdrop.transfer.TransferEngine
import com.meshdrop.transfer.crypto.DeviceIdentity
import com.meshdrop.transfer.crypto.VerifiedPeers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled dependency graph.
 *
 * The MVP is small enough that a service locator is clearer than a DI framework, and it
 * keeps the "add a transport" story simple: [discoveryStrategies] is the only place a new
 * [DiscoveryStrategy] has to be registered.
 */
class AppContainer(val application: Application) {

    /** Application lifetime scope: survives configuration changes and navigation. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val userPreferences = UserPreferences(application)
    val documentRepository = DocumentRepository(application)
    val fileSpooler = FileSpooler(application)
    val receivedFileStore = ReceivedFileStore(application)

    /** Long-term device identity used to authenticate peers (Android Keystore). */
    val deviceIdentity: DeviceIdentity = com.meshdrop.transfer.crypto.AndroidDeviceIdentity.create(application)
    val verifiedPeers = VerifiedPeers(application)

    /** Private storage for files the user moved out of shared storage. */
    val vaultStore = VaultStore(application)

    /** The finished-transfer log. App-private: it names devices and files, so it is not shared storage. */
    val transferLogFile = TransferLogFile(application.filesDir)

    // Explicit types on both the engine and the controller: the engine asks the controller
    // for fallback routes and the controller holds the engine, and letting Kotlin infer the
    // pair makes it try to resolve the cycle at compile time.
    val linkRegistry: com.meshdrop.transfer.link.LinkRegistry = com.meshdrop.transfer.link.LinkRegistry(applicationScope) { transferEngine.acceptLink(it) }
    val bluetoothTransport = com.meshdrop.transport.BluetoothTransport(application, applicationScope, linkRegistry)
    val usbTransport = com.meshdrop.transport.UsbTransport(application, applicationScope, linkRegistry)

    val transferEngine: TransferEngine = TransferEngine(
        scope = applicationScope,
        documentRepository = documentRepository,
        fileSpooler = fileSpooler,
        fileStore = receivedFileStore,
        identity = deviceIdentity,
        trustedPeers = verifiedPeers,
        localDeviceId = userPreferences.deviceId,
        localDeviceName = { userPreferences.deviceName.value },
        encryptionEnabled = { userPreferences.encryptionEnabled.value },
        requireVerification = { userPreferences.requireVerification.value },
        autoAcceptVerified = { userPreferences.autoAcceptVerified.value },
        storageVolume = { storageVolume() },
        logSink = transferLogFile,
        sendJournal = com.meshdrop.core.persistence.SendJournal(application.filesDir),
        connectionFactory = linkRegistry,
        alternativeRoutes = { deviceId: String -> meshController.alternativeRoutes(deviceId) },
    )

    /**
     * Free space on the volume incoming files land on, read at call time.
     *
     * Falls back to [VolumeSpace.UNKNOWN] rather than 0: a platform that will not answer is
     * not evidence the disk is full, and reporting 0 would refuse every transfer.
     */
    private fun storageVolume(): VolumeSpace = runCatching {
        val paths = listOfNotNull(application.filesDir, application.getExternalFilesDir(null))
        paths.map { StatFs(it.absolutePath) }.minBy { it.availableBytes }.let { VolumeSpace(it.availableBytes, it.totalBytes) }
    }.getOrElse { VolumeSpace.UNKNOWN }

    /**
     * Every way we know how to find a peer.
     *
     * mDNS is the primary mechanism; the UDP beacon is the fallback for networks where mDNS
     * is filtered. Wi-Fi Direct forms a user-approved group; Bluetooth advertises IP
     * endpoints and does not carry file payloads itself.
     */
    val discoveryStrategies: List<DiscoveryStrategy> = listOf(
        NsdDiscoveryStrategy(application, applicationScope),
        UdpBeaconDiscoveryStrategy(applicationScope),
        WifiDirectDiscoveryStrategy(application, applicationScope),
        BluetoothDiscoveryStrategy(application, applicationScope),
        linkRegistry.strategy(com.meshdrop.core.model.DiscoveryKind.BLUETOOTH),
        linkRegistry.strategy(com.meshdrop.core.model.DiscoveryKind.USB),
    )

    val discoveryCoordinator = DiscoveryCoordinator(discoveryStrategies, applicationScope)

    val meshService: com.meshdrop.transfer.mesh.MeshService = com.meshdrop.transfer.mesh.MeshService(
        com.meshdrop.transfer.mesh.MeshReceiver(java.io.File(application.filesDir, "mesh-incoming"),
            userPreferences.deviceId, { userPreferences.deviceName.value }, deviceIdentity, receivedFileStore, transferEngine),
        com.meshdrop.transfer.mesh.RelayService(userPreferences.deviceId, java.io.File(application.cacheDir, "relay"),
            { discoveryCoordinator.peers.value }, com.meshdrop.transfer.mesh.MeshRpc(linkRegistry), { userPreferences.helperMode.value }),
    )
    val meshSender = com.meshdrop.transfer.mesh.MeshSender(java.io.File(application.filesDir, "mesh-outgoing"),
        com.meshdrop.transfer.client.AndroidPayloadSource(documentRepository, fileSpooler), deviceIdentity, verifiedPeers,
        linkRegistry, { discoveryCoordinator.peers.value })
    init { transferEngine.meshSender = meshSender; transferEngine.configureMesh(meshService) }

    val meshController = MeshController(
        preferences = userPreferences,
        discovery = discoveryCoordinator,
        engine = transferEngine,
        scope = applicationScope,
        startLinks = { linkRegistry.start(); bluetoothTransport.start(); usbTransport.start() },
        stopLinks = { bluetoothTransport.stop(); usbTransport.stop(); linkRegistry.close() },
    )
}
