package com.example.sopee

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.io.FileInputStream
import java.net.Socket
import kotlin.math.min

class AppMonitorVPNService : VpnService() {
    companion object {
        private var pandaActive = false
        private var lastPacketTime = 0L
        private var dnsIndex = 0
        private var instance: AppMonitorVPNService? = null

        fun isPandaActive(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastPacketTime > 3000) {
                pandaActive = false
            }
            return pandaActive
        }

        fun rotateDNS(dnsList: List<String>) {
            if (instance == null) return
            dnsIndex = (dnsIndex + 1) % dnsList.size
            val nextDNS = dnsList[dnsIndex]
            instance?.establishVPN(nextDNS)
        }
    }

    // ==================== DEBUG LOGGER SYSTEM ====================
    private class DebugLogger {
        private val logEntries = mutableListOf<String>()
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        
        fun log(tag: String, message: String) {
            val timestamp = dateFormat.format(Date(System.currentTimeMillis()))
            val entry = "[$timestamp] [$tag] $message"
            synchronized(logEntries) {
                logEntries.add(entry)
            }
            // Also print to logcat for real-time monitoring
            android.util.Log.d("CB_DEBUG", "$tag: $message")
        }
        
        fun saveToFile(context: android.content.Context): String {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "CedokBooster_Debug_${timestamp}.log"
            
            // GUNA FOLDER DOWNLOAD UMUM (sentiasa visible)
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            
            val logFile = File(downloadsDir, filename)
            try {
                PrintWriter(FileOutputStream(logFile)).use { writer ->
                    writer.println("=== CEDOKBOOSTER DEBUG LOG ===")
                    writer.println("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    writer.println("Package: ${context.packageName}")
                    writer.println("Android SDK: ${Build.VERSION.SDK_INT}")
                    writer.println("=".repeat(50))
                    writer.println()
                    
                    synchronized(logEntries) {
                        logEntries.forEach { writer.println(it) }
                    }
                    
                    writer.println()
                    writer.println("=== LOG END ===")
                    writer.println("Total entries: ${logEntries.size}")
                }
                return logFile.absolutePath
            } catch (e: Exception) {
                android.util.Log.e("CB_DEBUG", "Failed to save log file: ${e.message}")
                return ""
            }
        }
        
        fun clear() {
            synchronized(logEntries) {
                logEntries.clear()
            }
        }
        
        fun getEntryCount(): Int {
            synchronized(logEntries) {
                return logEntries.size
            }
        }
    }
    
    private val debugLogger = DebugLogger()
    // ==================== END DEBUG LOGGER ====================

    private var vpnInterface: ParcelFileDescriptor? = null
    private var forwardingActive = false
    
    private val connectionPool = ConnectionPool()
    private val priorityManager = TrafficPriorityManager()
    private val tcpConnections = ConcurrentHashMap<Int, Socket>()
    
    private val workerPool = Executors.newCachedThreadPool()
    private val scheduledPool: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    
    private val CHANNEL_ID = "panda_monitor_channel"
    private val NOTIF_ID = 1001

    // ==================== FIXED: SEQUENCE NUMBER TRACKING ====================
    private var sequenceNumber = 1000000000L  // Start dengan nilai besar
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        debugLogger.log("SERVICE", "AppMonitorVPNService started")
        
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Shopee Monitor running", connected = false))
        
        debugLogger.log("CONFIG", "Target package: com.shopee.foody.driver.my")
        debugLogger.log("CONFIG", "DNS: 8.8.8.8")
        debugLogger.log("CONFIG", "Sequence number start: $sequenceNumber")
        
        establishVPN("8.8.8.8")
        
        scheduledPool.scheduleAtFixedRate({
            connectionPool.cleanupIdleConnections()
            debugLogger.log("SCHEDULER", "Cleanup executed, tcpConnections: ${tcpConnections.size}")
        }, 10, 10, TimeUnit.SECONDS)
        
        startPacketProcessor()
        
        debugLogger.log("SERVICE", "All systems initialized")
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Shopee Monitor", NotificationManager.IMPORTANCE_LOW)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String, connected: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val smallIcon = if (connected) android.R.drawable.presence_online else android.R.drawable.presence_busy
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shopee Monitor")
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    fun establishVPN(dns: String) {
        debugLogger.log("VPN", "Establish VPN dengan DNS: $dns")
        
        try {
            forwardingActive = false
            connectionPool.closeAll()
            tcpConnections.values.forEach { it.close() }
            tcpConnections.clear()
            vpnInterface?.close()
        } catch (e: Exception) {
            debugLogger.log("VPN_ERROR", "Cleanup error: ${e.message}")
        }

        val builder = Builder()
        builder.setSession("ShopeeMonitor")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAllowedApplication("com.shopee.foody.driver.my")
            .addDnsServer(dns)

        vpnInterface = try {
            val iface = builder.establish()
            debugLogger.log("VPN", "VPN interface established")
            iface
        } catch (e: Exception) {
            debugLogger.log("VPN_ERROR", "Failed establish VPN: ${e.message}")
            null
        }

        if (vpnInterface != null) {
            startForeground(NOTIF_ID, createNotification("Shopee Monitor (DNS: $dns)", connected = true))
            forwardingActive = true
            debugLogger.log("VPN", "Starting packet forwarding")
            startPacketForwarding()
        } else {
            debugLogger.log("VPN", "VPN interface is null")
        }
    }

    private fun startPacketForwarding() {
        workerPool.execute {
            val buffer = ByteArray(2048)
            debugLogger.log("PACKET_FORWARD", "Packet forwarding thread started")
            
            while (forwardingActive) {
                try {
                    val fd = vpnInterface?.fileDescriptor
                    if (fd == null) {
                        debugLogger.log("PACKET_FORWARD", "VPN fd is null, stopping")
                        break
                    }
                    
                    val len = FileInputStream(fd).read(buffer)
                    if (len > 0) {
                        pandaActive = true
                        lastPacketTime = System.currentTimeMillis()
                        debugLogger.log("PACKET_RX", "Received packet of length $len bytes")
                        handleOutboundPacket(buffer.copyOfRange(0, len))
                    }
                } catch (e: Exception) {
                    pandaActive = false
                    debugLogger.log("PACKET_FORWARD_ERROR", "Error: ${e.message}")
                    Thread.sleep(50)
                }
            }
            debugLogger.log("PACKET_FORWARD", "Packet forwarding thread ended")
        }
    }

    private fun handleOutboundPacket(packet: ByteArray) {
        try {
            if (packet.size < 20) {
                debugLogger.log("PACKET_PARSE", "Packet too small: ${packet.size} bytes")
                return
            }
            
            val version = (packet[0].toInt() and 0xF0) shr 4
            if (version != 4) {
                debugLogger.log("PACKET_PARSE", "Non-IPv4 packet (version=$version), ignoring")
                return
            }
            
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (ihl < 20 || ihl > packet.size) {
                debugLogger.log("PACKET_PARSE", "Invalid IP header length: $ihl")
                return
            }
            
            val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
            if (totalLength < ihl) {
                debugLogger.log("PACKET_PARSE", "Invalid total length: $totalLength")
                return
            }
            
            val protocol = packet[9].toInt() and 0xFF
            
            if (protocol == 17) { // UDP
                handleUdpPacket(packet)
                return
            }
            
            if (protocol != 6) { // Bukan TCP
                debugLogger.log("PACKET_PARSE", "Non-TCP packet (protocol=$protocol), ignoring")
                return
            }
            
            val destIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
            
            if (packet.size < ihl + 20) {
                debugLogger.log("PACKET_PARSE", "Packet too short for TCP header")
                return
            }
            
            val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
            val destPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            
            val dataOffset = (packet[ihl + 12].toInt() and 0xF0) shr 4
            val tcpHeaderLength = dataOffset * 4
            
            if (tcpHeaderLength < 20 || tcpHeaderLength > 60) {
                debugLogger.log("PACKET_PARSE", "Invalid TCP header length: $tcpHeaderLength")
                return
            }
            
            val payloadStart = ihl + tcpHeaderLength
            val payload = if (payloadStart < packet.size && payloadStart < totalLength) {
                val payloadEnd = minOf(packet.size, totalLength)
                packet.copyOfRange(payloadStart, payloadEnd)
            } else {
                ByteArray(0)
            }
            
            val tcpFlags = packet[ihl + 13].toInt() and 0xFF
            val flagStr = buildString {
                if ((tcpFlags and 0x02) != 0) append("SYN ")
                if ((tcpFlags and 0x10) != 0) append("ACK ")
                if ((tcpFlags and 0x08) != 0) append("PSH ")
                if ((tcpFlags and 0x01) != 0) append("FIN ")
                if ((tcpFlags and 0x04) != 0) append("RST ")
            }.trim()
            
            debugLogger.log("PACKET_PARSE", "$destIp:$destPort <- 10.0.0.2:$srcPort [$flagStr], Payload: ${payload.size} bytes")
            
            if (payload.isNotEmpty() || (tcpFlags and 0x02) != 0) { // SYN atau ada data
                val priority = EndpointConfig.getPriorityForHost(destIp)
                val domainType = when (priority) {
                    3 -> "CRITICAL"
                    2 -> "IMPORTANT"
                    1 -> "BACKGROUND"
                    else -> "UNKNOWN"
                }
                debugLogger.log("ENDPOINT_CHECK", "$destIp classified as $domainType (priority=$priority)")
                
                priorityManager.addPacket(payload, destIp, destPort, srcPort)
                debugLogger.log("QUEUE", "Added to queue. Queue size: ${priorityManager.queueSize()}")
            } else {
                debugLogger.log("PACKET_PARSE", "Skipping control packet [$flagStr] with no data")
            }
            
        } catch (e: Exception) {
            debugLogger.log("PACKET_PARSE_ERROR", "Error: ${e.message}")
            val dumpSize = minOf(32, packet.size)
            val hexDump = packet.take(dumpSize).joinToString(" ") { "%02x".format(it) }
            debugLogger.log("PACKET_DUMP", "First $dumpSize bytes: $hexDump")
        }
    }

    private fun handleUdpPacket(packet: ByteArray) {
        try {
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (packet.size < ihl + 8) {
                debugLogger.log("UDP_PARSE", "Packet too short for UDP header")
                return
            }
            
            val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
            val destPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            
            val destIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
            
            debugLogger.log("UDP", "$destIp:$destPort <- 10.0.0.2:$srcPort, Size: ${packet.size} bytes")
            
            if (destPort == 53 || srcPort == 53) {
                debugLogger.log("DNS", "DNS packet detected: $destIp:$destPort")
            }
            
        } catch (e: Exception) {
            debugLogger.log("UDP_ERROR", "Error: ${e.message}")
        }
    }

    private fun startPacketProcessor() {
        workerPool.execute {
            debugLogger.log("PACKET_PROCESSOR", "Packet processor thread started")
            
            while (forwardingActive) {
                val task = priorityManager.takePacket()
                
                if (task == null) {
                    Thread.sleep(10)
                    continue
                }
                
                val destKey = "${task.destIp}:${task.destPort}"
                debugLogger.log("PACKET_TASK", "Processing: $destKey, Priority: ${task.priority}, SrcPort: ${task.srcPort}, Size: ${task.packet.size}")
                
                var socket = connectionPool.getSocket(task.destIp, task.destPort)
                
                if (socket == null) {
                    debugLogger.log("SOCKET", "No pooled socket for $destKey, creating new")
                    socket = try {
                        Socket(task.destIp, task.destPort).apply {
                            tcpNoDelay = true
                            soTimeout = 30000
                        }
                    } catch (e: Exception) {
                        debugLogger.log("SOCKET_ERROR", "Failed connect to $destKey: ${e.message}")
                        null
                    }
                } else {
                    debugLogger.log("SOCKET", "Reusing pooled socket for $destKey")
                }
                
                if (socket != null) {
                    try {
                        debugLogger.log("SOCKET_SEND", "Sending ${task.packet.size} bytes to $destKey")
                        socket.getOutputStream().write(task.packet)
                        socket.getOutputStream().flush()
                        debugLogger.log("SOCKET_SEND", "Successfully sent to $destKey")
                        
                        // ==================== FIXED: START HANDLER SIMPLE ====================
                        if (!tcpConnections.containsKey(task.srcPort)) {
                            debugLogger.log("RESPONSE_HANDLER", "🚀 STARTING handler for srcPort ${task.srcPort}")
                            startResponseHandler(task.srcPort, socket, task.destIp, task.destPort)
                        } else {
                            debugLogger.log("RESPONSE_HANDLER", "✅ Handler already exists for srcPort ${task.srcPort}")
                        }
                        
                    } catch (e: Exception) {
                        debugLogger.log("SOCKET_SEND_ERROR", "Error: ${e.message}")
                        try { socket.close() } catch (_: Exception) { }
                        tcpConnections.remove(task.srcPort)
                    }
                } else {
                    debugLogger.log("PACKET_TASK", "No socket for $destKey, packet dropped")
                }
                
                Thread.sleep(10)
            }
            debugLogger.log("PACKET_PROCESSOR", "Packet processor thread ended")
        }
    }

    private fun startResponseHandler(srcPort: Int, socket: Socket, destIp: String, destPort: Int) {
        workerPool.execute {
            debugLogger.log("RESPONSE_HANDLER", "🚀 Handler STARTED for srcPort $srcPort -> $destIp:$destPort")
            
            try {
                val outStream = FileOutputStream(vpnInterface!!.fileDescriptor)
                
                // ==================== FIXED: SEND SYN-ACK FIRST ====================
                debugLogger.log("SYNACK", "🚀 Sending SYN-ACK to app for srcPort $srcPort")
                
                val synAckPacket = buildTcpPacket(destIp, destPort, "10.0.0.2", srcPort, ByteArray(0))
                synAckPacket[33] = 0x12.toByte()  // SYN+ACK flags
                
                // Verify flags
                val flags = synAckPacket[33].toInt() and 0xFF
                debugLogger.log("SYNACK_VERIFY", "SYN-ACK flags: 0x${flags.toString(16)} (${if (flags == 0x12) "SYN+ACK" else "UNKNOWN"})")
                
                outStream.write(synAckPacket)
                outStream.flush()
                debugLogger.log("SYNACK", "✅ SYN-ACK sent to app for srcPort $srcPort")
                
                // Track socket
                tcpConnections[srcPort] = socket
                
                // Read server response
                val inStream = socket.getInputStream()
                socket.soTimeout = 30000
                val buffer = ByteArray(2048)
                
                while (forwardingActive && socket.isConnected && !socket.isClosed) {
                    val n = inStream.read(buffer)
                    if (n <= 0) {
                        debugLogger.log("RESPONSE_RX", "No more data from server for srcPort $srcPort")
                        break
                    }
                    
                    debugLogger.log("RESPONSE_RX", "✅ RECEIVED $n bytes from server for srcPort $srcPort")
                    val reply = buildTcpPacket(destIp, destPort, "10.0.0.2", srcPort, buffer.copyOfRange(0, n))
                    outStream.write(reply)
                    outStream.flush()
                    debugLogger.log("RESPONSE_TX", "✅ Forwarded $n bytes to app")
                }
                
            } catch (e: java.net.SocketTimeoutException) {
                debugLogger.log("RESPONSE_HANDLER", "⏰ Timeout waiting for server (no data received)")
            } catch (e: Exception) {
                debugLogger.log("RESPONSE_HANDLER_ERROR", "❌ Error: ${e.message}")
            } finally {
                tcpConnections.remove(srcPort)
                
                if (socket.isConnected && !socket.isClosed) {
                    debugLogger.log("SOCKET_POOL", "Returning socket to pool")
                    connectionPool.returnSocket(destIp, destPort, socket)
                } else {
                    try { socket.close() } catch (_: Exception) { }
                    debugLogger.log("SOCKET_POOL", "Socket closed")
                }
                debugLogger.log("RESPONSE_HANDLER", "Handler ENDED for srcPort $srcPort")
            }
        }
    }

    private fun buildTcpPacket(srcIp: String, srcPort: Int, destIp: String, destPort: Int, payload: ByteArray): ByteArray {
        val totalLen = 40 + payload.size
        val packet = ByteArray(totalLen)
        
        // IPv4 Header
        packet[0] = 0x45.toByte()  // Version + IHL
        packet[1] = 0x00           // DSCP + ECN
        packet[2] = (totalLen ushr 8).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x00           // Identification
        packet[5] = 0x00
        packet[6] = 0x40           // Flags + Fragment Offset
        packet[7] = 0x00
        packet[8] = 0x40.toByte()  // TTL = 64
        packet[9] = 0x06           // Protocol = TCP
        
        // IP Checksum (akan dikira kemudian)
        packet[10] = 0x00
        packet[11] = 0x00
        
        // Source IP
        val srcParts = srcIp.split(".")
        packet[12] = srcParts[0].toInt().toByte()
        packet[13] = srcParts[1].toInt().toByte()
        packet[14] = srcParts[2].toInt().toByte()
        packet[15] = srcParts[3].toInt().toByte()
        
        // Destination IP
        val destParts = destIp.split(".")
        packet[16] = destParts[0].toInt().toByte()
        packet[17] = destParts[1].toInt().toByte()
        packet[18] = destParts[2].toInt().toByte()
        packet[19] = destParts[3].toInt().toByte()
        
        // TCP Header
        packet[20] = (srcPort ushr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (destPort ushr 8).toByte()
        packet[23] = (destPort and 0xFF).toByte()
        
        // ==================== FIXED: SEQUENCE NUMBER INCREMENTAL ====================
        val seqNum = sequenceNumber++
        packet[24] = (seqNum ushr 24).toByte()
        packet[25] = (seqNum ushr 16).toByte()
        packet[26] = (seqNum ushr 8).toByte()
        packet[27] = (seqNum and 0xFF).toByte()
        
        // Acknowledgment Number
        packet[28] = 0x00
        packet[29] = 0x00
        packet[30] = 0x00
        packet[31] = 0x00
        
        // Data Offset + Reserved + Flags
        packet[32] = 0x50  // Data Offset = 5 (20 bytes)
        
        // TCP Flags
        val flags = when {
            payload.isEmpty() && packet[33] != 0x12.toByte() -> 0x02  // SYN
            payload.isEmpty() && packet[33] == 0x12.toByte() -> 0x12  // SYN-ACK
            else -> 0x10  // ACK untuk data
        }
        packet[33] = flags.toByte()
        
        // Window Size
        packet[34] = 0xFF.toByte()
        packet[35] = 0xFF.toByte()
        
        // TCP Checksum
        packet[36] = 0x00
        packet[37] = 0x00
        
        // Urgent Pointer
        packet[38] = 0x00
        packet[39] = 0x00
        
        // Payload
        System.arraycopy(payload, 0, packet, 40, payload.size)
        
        // Calculate checksums
        calculateIpChecksum(packet)
        calculateTcpChecksum(packet, srcIp, destIp)
        
        val flagName = when (flags) {
            0x02 -> "SYN"
            0x12 -> "SYN-ACK"
            0x10 -> "ACK"
            0x18 -> "PSH+ACK"
            else -> "UNKNOWN"
        }
        debugLogger.log("PACKET_BUILD", "Built: $srcIp:$srcPort -> $destIp:$destPort, Flags: $flagName, Seq: $seqNum, Size: ${packet.size}")
        
        return packet
    }

    private fun calculateIpChecksum(packet: ByteArray) {
        var sum = 0
        
        for (i in 0 until 20 step 2) {
            if (i != 10) {
                sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            }
        }
        
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        
        val checksum = sum.inv() and 0xFFFF
        packet[10] = (checksum ushr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()
    }
    
    private fun calculateTcpChecksum(packet: ByteArray, srcIp: String, destIp: String) {
        var sum = 0L
        
        // Pseudo-header: src IP
        val srcParts = srcIp.split(".")
        sum += ((srcParts[0].toInt() and 0xFF) shl 8) or (srcParts[1].toInt() and 0xFF)
        sum += ((srcParts[2].toInt() and 0xFF) shl 8) or (srcParts[3].toInt() and 0xFF)
        
        // Pseudo-header: dest IP
        val destParts = destIp.split(".")
        sum += ((destParts[0].toInt() and 0xFF) shl 8) or (destParts[1].toInt() and 0xFF)
        sum += ((destParts[2].toInt() and 0xFF) shl 8) or (destParts[3].toInt() and 0xFF)
        
        // Protocol (TCP = 6) + TCP length
        val tcpLength = packet.size - 20
        sum += 6  // Protocol
        sum += tcpLength
        
        // TCP header and data
        for (i in 20 until packet.size step 2) {
            if (i == 36) continue  // Skip checksum field
            if (i + 1 < packet.size) {
                sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            } else {
                sum += (packet[i].toInt() and 0xFF) shl 8  // Pad last byte
            }
        }
        
        // Fold to 16-bit
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        
        val checksum = sum.inv() and 0xFFFF
        packet[36] = (checksum ushr 8).toByte()
        packet[37] = (checksum and 0xFF).toByte()
    }

    override fun onDestroy() {
        debugLogger.log("SERVICE", "AppMonitorVPNService destroying...")
        
        forwardingActive = false
        connectionPool.closeAll()
        tcpConnections.values.forEach { it.close() }
        tcpConnections.clear()
        
        scheduledPool.shutdownNow()
        workerPool.shutdownNow()
        
        try {
            vpnInterface?.close()
            debugLogger.log("VPN", "VPN interface closed")
        } catch (e: Exception) {
            debugLogger.log("VPN_ERROR", "Error closing VPN: ${e.message}")
        }
        
        pandaActive = false
        lastPacketTime = 0L
        instance = null
        
        val logPath = debugLogger.saveToFile(this)
        if (logPath.isNotEmpty()) {
            android.util.Log.d("CB_DEBUG", "Debug log saved to: $logPath")
        }
        
        debugLogger.clear()
        super.onDestroy()
    }
}
