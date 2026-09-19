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
    val deviceIdentity: DeviceIdentity = DeviceIdentity.create(application)
    val verifiedPeers = VerifiedPeers(application)

    /** Private storage for files the user moved out of shared storage. */
    val vaultStore = VaultStore(application)

    /** The finished-transfer log. App-private: it names devices and files, so it is not shared storage. */
    val transferLogFile = TransferLogFile(application.filesDir)

    // Explicit types on both the engine and the controller: the engine asks the controller
    // for fallback routes and the controller holds the engine, and letting Kotlin infer the
    // pair makes it try to resolve the cycle at compile time.
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
        alternativeRoutes = { deviceId: String -> meshController.alternativeRoutes(deviceId) },
    )

    /**
     * Free space on the volume incoming files land on, read at call time.
     *
     * Falls back to [VolumeSpace.UNKNOWN] rather than 0: a platform that will not answer is
     * not evidence the disk is full, and reporting 0 would refuse every transfer.
     */
    private fun storageVolume(): VolumeSpace = runCatching {
        val path = application.getExternalFilesDir(null) ?: application.filesDir
        StatFs(path.absolutePath).let { VolumeSpace(it.availableBytes, it.totalBytes) }
    }.getOrElse { VolumeSpace.UNKNOWN }

    /**
     * Every way we know how to find a peer.
     *
     * mDNS is the primary mechanism; the UDP beacon is the fallback for networks where mDNS
     * is filtered. Wi-Fi Direct and Bluetooth are registered but disabled: they exist so the
     * rest of the app already handles them.
     */
    val discoveryStrategies: List<DiscoveryStrategy> = listOf(
        NsdDiscoveryStrategy(application, applicationScope),
        UdpBeaconDiscoveryStrategy(applicationScope),
        WifiDirectDiscoveryStrategy(application, applicationScope),
        BluetoothDiscoveryStrategy(application, applicationScope),
    )

    val discoveryCoordinator = DiscoveryCoordinator(discoveryStrategies, applicationScope)

    val meshController = MeshController(
        preferences = userPreferences,
        discovery = discoveryCoordinator,
        engine = transferEngine,
        scope = applicationScope,
    )
}
