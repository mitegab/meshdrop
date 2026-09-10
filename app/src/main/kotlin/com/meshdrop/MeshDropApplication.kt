package com.meshdrop

import android.app.Application
import com.meshdrop.core.util.MeshLog
import com.meshdrop.di.AppContainer
import com.meshdrop.notifications.Notifications

/**
 * Application entry point. Owns the dependency graph and the notification channels, and
 * makes sure the receiver is never left running after the process is killed.
 */
class MeshDropApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.createChannels(this)
        MeshLog.i(TAG, "MeshDrop started (device ${container.userPreferences.shortId()})")
    }

    companion object {
        const val TAG = "App"

        @Volatile
        private var instance: MeshDropApplication? = null

        /** Convenience accessor for components that only have a Context (service, receiver). */
        fun container(): AppContainer? = instance?.container
    }
}
