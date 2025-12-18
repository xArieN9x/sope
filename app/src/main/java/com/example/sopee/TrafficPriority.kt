package com.example.sopee

import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit  // TAMBAH INI!

data class PacketTask(
    val packet: ByteArray,
    val destIp: String,
    val destPort: Int,
    val srcPort: Int,
    val priority: Int,
    val timestamp: Long = System.currentTimeMillis()
) : Comparable<PacketTask> {
    override fun compareTo(other: PacketTask): Int {
        if (this.priority != other.priority) {
            return other.priority - this.priority
        }
        return (this.timestamp - other.timestamp).toInt()
    }

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
        val priority = EndpointConfig.getPriorityForHost(destIp)
        
        // Debug log
        android.util.Log.d("CB_DEBUG", "QUEUE_ADD: $destIp:$destPort -> priority=$priority, size=${packetQueue.size + 1}")
        
        packetQueue.put(PacketTask(packet, destIp, destPort, srcPort, priority))
    }
    
    fun takePacket(): PacketTask? {
        return try {
            // Use poll with timeout instead of take()
            packetQueue.poll(50, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            null
        }
    }
    
    fun queueSize(): Int = packetQueue.size
}
