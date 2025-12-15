package com.example.sopee

import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class ConnectionPool {
    data class PooledSocket(
        val socket: Socket,
        val lastUsed: Long,
        val isIdle: Boolean = false
    )
    
    // Map: "destIp:destPort" -> List of available sockets
    private val socketPool = ConcurrentHashMap<String, MutableList<PooledSocket>>()
    
    fun getSocket(destIp: String, destPort: Int): Socket? {
        val key = "$destIp:$destPort"
        
        synchronized(socketPool) {
            val sockets = socketPool[key] ?: return null
            
            // Cari socket yang idle dan masih connected
            for (i in sockets.indices) {
                val pooled = sockets[i]
                if (pooled.isIdle && pooled.socket.isConnected && !pooled.socket.isClosed) {
                    // Mark as active dan update last used
                    sockets[i] = pooled.copy(isIdle = false, lastUsed = System.currentTimeMillis())
                    return pooled.socket
                }
            }
        }
        return null
    }
    
    fun returnSocket(destIp: String, destPort: Int, socket: Socket) {
        if (socket.isClosed) return
        
        val key = "$destIp:$destPort"
        
        synchronized(socketPool) {
            val sockets = socketPool.getOrPut(key) { mutableListOf() }
            
            // Check connection limit per endpoint
            val maxConnections = min(
                EndpointConfig.MAX_CONNECTIONS_PER_ENDPOINT,
                sockets.size + 1
            )
            
            if (sockets.size < maxConnections) {
                sockets.add(PooledSocket(socket, System.currentTimeMillis(), isIdle = true))
            } else {
                // Tutup socket yang paling lama idle
                val oldest = sockets.minByOrNull { it.lastUsed }
                oldest?.let {
                    try {
                        it.socket.close()
                    } catch (_: Exception) { }
                    sockets.remove(it)
                    sockets.add(PooledSocket(socket, System.currentTimeMillis(), isIdle = true))
                }
            }
        }
    }
    
    fun cleanupIdleConnections() {
        val now = System.currentTimeMillis()
        
        synchronized(socketPool) {
            socketPool.forEach { (key, sockets) ->
                val iterator = sockets.iterator()
                while (iterator.hasNext()) {
                    val pooled = iterator.next()
                    
                    // GUNA FUNGSI BARU DARI EndpointConfig
                    val keepAlive = EndpointConfig.getKeepAliveForHost(key)
                    
                    if (now - pooled.lastUsed > keepAlive || pooled.socket.isClosed) {
                        try {
                            pooled.socket.close()
                        } catch (_: Exception) { }
                        iterator.remove()
                    }
                }
                
                // Remove empty endpoint entries
                if (sockets.isEmpty()) {
                    socketPool.remove(key)
                }
            }
        }
    }
    
    fun closeAll() {
        synchronized(socketPool) {
            socketPool.values.forEach { sockets ->
                sockets.forEach { 
                    try { 
                        it.socket.close() 
                    } catch (_: Exception) { } 
                }
            }
            socketPool.clear()
        }
    }
}
