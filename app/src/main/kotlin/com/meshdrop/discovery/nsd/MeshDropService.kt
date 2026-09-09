package com.meshdrop.discovery.nsd

import com.meshdrop.discovery.api.LocalIdentity

/**
 * The mDNS (DNS-SD) service descriptor MeshDrop advertises.
 *
 * Keeping the service type, attribute keys and protocol version in one object means a
 * future protocol break is a one-line change: bump [PROTOCOL_VERSION] and reject peers
 * that do not match it during the transfer handshake.
 */
object MeshDropService {

    /** DNS-SD service type. The trailing dots are required by the platform. */
    const val SERVICE_TYPE = "_meshdrop._tcp."

    const val ATTR_ID = "id"
    const val ATTR_NAME = "name"
    const val ATTR_PORT = "port"
    const val ATTR_VERSION = "v"

    /** Wire protocol version spoken by this build. */
    const val PROTOCOL_VERSION = 1

    /** Service names must be unique on the link; the device id guarantees that. */
    fun serviceName(identity: LocalIdentity): String = "MeshDrop-${identity.shortId}"
}
