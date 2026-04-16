package com.pedro.common.socket.ktor

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ReadWriteSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.tls.tls
import kotlinx.coroutines.Dispatchers
import java.security.SecureRandom
import javax.net.ssl.TrustManager

class TcpStreamSocketKtor(
    private val host: String,
    private val port: Int,
    private val secured: Boolean,
    private val certificate: TrustManager? = null
): TcpStreamSocketKtorBase(host, port) {

    override suspend fun onConnectSocket(timeout: Long): ReadWriteSocket {
        val context = Dispatchers.IO + kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            android.util.Log.e("TcpStreamSocketKtor", "Ktor/TLS error: ${throwable.message}")
        }
        selectorManager = SelectorManager(context)
        val builder = aSocket(selectorManager).tcp().connect(
            remoteAddress = InetSocketAddress(host, port),
            configure = { if (!secured) socketTimeout = timeout }
        )
        return if (secured) {
            builder.tls(context) {
                trustManager = certificate
                random = SecureRandom()
            }
        } else builder
    }
}