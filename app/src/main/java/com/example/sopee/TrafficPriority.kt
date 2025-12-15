package com.example.sopee

import java.util.concurrent.PriorityBlockingQueue

data class PacketTask(
    val packet: ByteArray,
    val destIp: String,
    val destPort: Int,
    val srcPort: Int,
    val priority: Int, // 3=CRITICAL, 2=IMPORTANT, 1=BACKGROUND, 0=UNKNOWN
    val timestamp: Long = System.currentTimeMillis()
) : Comparable<PacketTask> {
    override fun compareTo(other: PacketTask): Int {
        // Higher priority first, then older packets first
        return if (this.priority != other.priority) {
            other.priority - this.priority // Higher priority should come out first
        } else {
            (this.timestamp - other.timestamp).toInt() // Older packets first if same priority
        }
    }

    // Optional: Override equals & hashCode untuk data class dengan ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PacketTask

        if (!packet.contentEquals(other.packet)) return false
        if (destIp != other.destIp) return false
        if (destPort != other.destPort) return false
        if (srcPort != other.srcPort) return false
        if (priority != other.priority) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packet.contentHashCode()
        result = 31 * result + destIp.hashCode()
        result = 31 * result + destPort
        result = 31 * result + srcPort
        result = 31 * result + priority
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

class TrafficPriorityManager {
    private val packetQueue = PriorityBlockingQueue<PacketTask>()
    
    fun addPacket(packet: ByteArray, destIp: String, destPort: Int, srcPort: Int) {
        // GUNA FUNGSI BARU DARI EndpointConfig
        val priority = EndpointConfig.getPriorityForHost(destIp)
        
        packetQueue.put(PacketTask(packet, destIp, destPort, srcPort, priority))
    }
    
    fun takePacket(): PacketTask? {
        return try {
            packetQueue.take()
        } catch (e: InterruptedException) {
            null
        }
    }
    
    fun queueSize(): Int = packetQueue.size
}
