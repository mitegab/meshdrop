package com.meshdrop.transfer.link

import com.meshdrop.core.model.PeerDevice
import com.meshdrop.transfer.protocol.TransferProtocol
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** A request-sized, cancellable byte stream. No IP assumptions above this boundary. */
interface DuplexConnection : Closeable {
    val remoteAddress: String
    var soTimeout: Int
    fun getInputStream(): InputStream
    fun getOutputStream(): OutputStream
}

class TcpConnection(val socket: Socket) : DuplexConnection {
    override val remoteAddress get() = socket.remoteSocketAddress?.toString().orEmpty()
    override var soTimeout: Int
        get() = socket.soTimeout
        set(value) { socket.soTimeout = value }
    override fun getInputStream(): InputStream = socket.getInputStream()
    override fun getOutputStream(): OutputStream = socket.getOutputStream()
    override fun close() = socket.close()
}

fun interface ConnectionFactory {
    /** Registration happens BEFORE a blocking connect, so cancellation cannot miss it. */
    fun open(peer: PeerDevice, register: (DuplexConnection) -> Unit): DuplexConnection

    companion object {
        val TCP = ConnectionFactory { peer, register ->
            val connection = TcpConnection(Socket())
            try {
                register(connection)
                connection.socket.tcpNoDelay = true
                connection.socket.connect(InetSocketAddress(requireNotNull(peer.host), peer.port), TransferProtocol.CONNECT_TIMEOUT_MS)
                connection.soTimeout = TransferProtocol.SOCKET_TIMEOUT_MS
                connection
            } catch (error: Throwable) { connection.close(); throw error }
        }
    }
}
