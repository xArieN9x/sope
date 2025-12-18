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
    private val tcpConnections = ConcurrentHashMap<Int, Socket>() // Track active sockets by srcPort
    
    private val workerPool = Executors.newCachedThreadPool()
    private val scheduledPool: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    
    private val CHANNEL_ID = "panda_monitor_channel"
    private val NOTIF_ID = 1001

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        debugLogger.log("SERVICE", "AppMonitorVPNService started")
        
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("Shopee Monitor running", connected = false))
        
        // Log current configuration
        debugLogger.log("CONFIG", "Target package: com.shopee.foody.driver.my")
        debugLogger.log("CONFIG", "DNS: 8.8.8.8")
        debugLogger.log("CONFIG", "EndpointConfig loaded - Critical: ${EndpointConfig.CRITICAL_ENDPOINTS.size}, Important: ${EndpointConfig.IMPORTANT_ENDPOINTS.size}, Background: ${EndpointConfig.BACKGROUND_ENDPOINTS.size}")
        
        establishVPN("8.8.8.8")
        
        // Start cleanup scheduler
        scheduledPool.scheduleAtFixedRate({
            connectionPool.cleanupIdleConnections()
            debugLogger.log("SCHEDULER", "Cleanup executed")
        }, 10, 10, TimeUnit.SECONDS)
        
        // Start packet processor
        startPacketProcessor()
        
        debugLogger.log("SERVICE", "All systems initialized")
        
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Shopee Monitor", NotificationManager.IMPORTANCE_LOW)
            nm?.createNotificationChannel(channel)
            debugLogger.log("NOTIFICATION", "Notification channel created")
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
        debugLogger.log("VPN", "Attempting to establish VPN with DNS: $dns")
        
        try {
            forwardingActive = false
            connectionPool.closeAll()
            tcpConnections.values.forEach { it.close() }
            tcpConnections.clear()
            vpnInterface?.close()
            debugLogger.log("VPN", "Cleaned up previous connections")
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
            debugLogger.log("VPN", "VPN interface established successfully")
            iface
        } catch (e: Exception) {
            debugLogger.log("VPN_ERROR", "Failed to establish VPN: ${e.message}")
            null
        }

        try {
            val status = if (vpnInterface != null) " (DNS: $dns)" else " - Failed"
            startForeground(NOTIF_ID, createNotification("Shopee Monitor$status", connected = vpnInterface != null))
            debugLogger.log("NOTIFICATION", "Notification updated: $status")
        } catch (e: Exception) {
            debugLogger.log("NOTIFICATION_ERROR", "Failed to update notification: ${e.message}")
        }

        if (vpnInterface != null) {
            forwardingActive = true
            debugLogger.log("VPN", "Starting packet forwarding")
            startPacketForwarding()
        } else {
            debugLogger.log("VPN", "VPN interface is null - cannot start forwarding")
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
                        debugLogger.log("PACKET_FORWARD", "VPN file descriptor is null, stopping")
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
                    debugLogger.log("PACKET_FORWARD_ERROR", "Error reading packet: ${e.message}")
                    Thread.sleep(50)
                }
            }
            debugLogger.log("PACKET_FORWARD", "Packet forwarding thread ended")
        }
    }

    // ==================== SOLUTION #1: FIXED PACKET PARSING ====================
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
            
            // ==================== SOLUTION #3: UDP HANDLING ====================
            if (protocol == 17) { // UDP
                handleUdpPacket(packet)
                return  // Stop processing, jangan proceed ke TCP
            }
            
            if (protocol != 6) { // Bukan TCP
                debugLogger.log("PACKET_PARSE", "Non-TCP packet (protocol=$protocol), ignoring")
                return
            }
            // ==================== END UDP HANDLING ====================
            
            val destIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
            
            // Check TCP header exists
            if (packet.size < ihl + 20) {
                debugLogger.log("PACKET_PARSE", "Packet too short for TCP header")
                return
            }
            
            val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
            val destPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            
            // Calculate TCP header length properly
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
            
            // DEBUG: Log TCP flags untuk tahu packet type
            val tcpFlags = packet[ihl + 13].toInt() and 0xFF
            val flagStr = buildString {
                if ((tcpFlags and 0x02) != 0) append("SYN ")
                if ((tcpFlags and 0x10) != 0) append("ACK ")
                if ((tcpFlags and 0x08) != 0) append("PSH ")
                if ((tcpFlags and 0x01) != 0) append("FIN ")
                if ((tcpFlags and 0x04) != 0) append("RST ")
            }.trim()
            
            debugLogger.log("PACKET_PARSE", "$destIp:$destPort <- 10.0.0.2:$srcPort [$flagStr], Payload: ${payload.size} bytes")
            
            // Only process packets with actual data or PUSH flag
            //if (payload.isNotEmpty() || (tcpFlags and 0x08) != 0) { // PSH flag
            if (payload.isNotEmpty() || (tcpFlags and 0x02) != 0) { // SYN juga diproses
                val priority = EndpointConfig.getPriorityForHost(destIp)
                val domainType = when (priority) {
                    3 -> "CRITICAL"
                    2 -> "IMPORTANT"
                    1 -> "BACKGROUND"
                    else -> "UNKNOWN"
                }
                debugLogger.log("ENDPOINT_CHECK", "$destIp classified as $domainType (priority=$priority)")
                
                priorityManager.addPacket(payload, destIp, destPort, srcPort)
                debugLogger.log("QUEUE", "Added to priority queue. Queue size: ${priorityManager.queueSize()}")
            } else {
                debugLogger.log("PACKET_PARSE", "Skipping control packet [$flagStr] with no data")
            }
            
        } catch (e: Exception) {
            debugLogger.log("PACKET_PARSE_ERROR", "Error parsing packet (size=${packet.size}): ${e.message}")
            // Dump first few bytes for debugging
            val dumpSize = minOf(32, packet.size)
            val hexDump = packet.take(dumpSize).joinToString(" ") { "%02x".format(it) }
            debugLogger.log("PACKET_DUMP", "First $dumpSize bytes: $hexDump")
        }
    }

    // ==================== SOLUTION #3: UDP PACKET HANDLING ====================
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
            
            // Log semua UDP traffic untuk debug
            debugLogger.log("UDP", "$destIp:$destPort <- 10.0.0.2:$srcPort, Size: ${packet.size} bytes")
            
            // Khusus untuk DNS (port 53)
            if (destPort == 53 || srcPort == 53) {
                debugLogger.log("DNS", "DNS packet detected: $destIp:$destPort")
                // Note: DNS forwarding di-comment untuk sekarang
                // forwardDnsQuery(packet, ihl)
            }
            
        } catch (e: Exception) {
            debugLogger.log("UDP_ERROR", "Error parsing UDP: ${e.message}")
        }
    }

    /* NOTE: DNS forwarding di-comment untuk elak complexity
    private fun forwardDnsQuery(packet: ByteArray, udpHeaderStart: Int) {
        Thread {
            try {
                // Implementation akan ditambah kemudian
                debugLogger.log("DNS_FORWARD", "DNS forwarding would happen here")
            } catch (e: Exception) {
                debugLogger.log("DNS_FORWARD_ERROR", "Failed: ${e.message}")
            }
        }.start()
    }
    */

    private fun startPacketProcessor() {
        workerPool.execute {
            debugLogger.log("PACKET_PROCESSOR", "Packet processor thread started")
            
            while (forwardingActive) {
                val task = try {
                    priorityManager.takePacket()
                } catch (e: InterruptedException) {
                    null
                }
                
                if (task == null) {
                    Thread.sleep(10)
                    continue
                }
                
                val destKey = "${task.destIp}:${task.destPort}"
                debugLogger.log("PACKET_TASK", "Processing task: $destKey, Priority: ${task.priority}, SrcPort: ${task.srcPort}, Size: ${task.packet.size} bytes")
                
                // Try to get existing socket from pool
                var socket = connectionPool.getSocket(task.destIp, task.destPort)
                
                if (socket == null) {
                    debugLogger.log("SOCKET", "No pooled socket available for $destKey, creating new")
                } else if (socket.isClosed || !socket.isConnected) {
                    debugLogger.log("SOCKET", "Pooled socket for $destKey is closed/not connected, creating new")
                    socket = null
                } else {
                    debugLogger.log("SOCKET", "Reusing pooled socket for $destKey")
                }
                
                if (socket == null) {
                    // Create new socket
                    socket = try {
                        debugLogger.log("SOCKET_CONNECT", "Attempting to connect to $destKey")
                        Socket(task.destIp, task.destPort).apply {
                            tcpNoDelay = true
                            soTimeout = 15000
                        }
                    } catch (e: Exception) {
                        debugLogger.log("SOCKET_ERROR", "Failed to connect to $destKey: ${e.message}")
                        null
                    }
                    
                    if (socket != null) {
                        debugLogger.log("SOCKET_CONNECT", "Successfully connected to $destKey")
                    }
                }
                
                if (socket != null) {
                    // ✅ FIX: Check jika response handler sudah start SEBELUM add ke map
                    val handlerAlreadyStarted = tcpConnections.containsKey(task.srcPort)
                    
                    // Track active connection (hanya jika handler belum start)
                    if (!handlerAlreadyStarted) {
                        tcpConnections[task.srcPort] = socket
                        debugLogger.log("TCP_CONNECTION", "Tracked socket for srcPort ${task.srcPort}")
                    }
                    
                    // Send data
                    try {
                        debugLogger.log("SOCKET_SEND", "Sending ${task.packet.size} bytes to $destKey")
                        socket.getOutputStream().write(task.packet)
                        socket.getOutputStream().flush()
                        debugLogger.log("SOCKET_SEND", "Successfully sent data to $destKey")
                        
                        // Start response handler if not already
                        synchronized(tcpConnections) {
                            if (!tcpConnections.containsKey(task.srcPort)) {
                                debugLogger.log("RESPONSE_HANDLER", "🚀 STARTING response handler for srcPort ${task.srcPort}")
                                startResponseHandler(task.srcPort, socket, task.destIp, task.destPort)
                            } else {
                                debugLogger.log("RESPONSE_HANDLER", "✅ Handler already exists for srcPort ${task.srcPort}, data sent")
                            }
                        }
                    } catch (e: Exception) {
                        debugLogger.log("SOCKET_SEND_ERROR", "Error sending to $destKey: ${e.message}")
                        socket.close()
                        tcpConnections.remove(task.srcPort)
                        debugLogger.log("TCP_CONNECTION", "Removed socket for srcPort ${task.srcPort} due to error")
                    }
                } else {
                    debugLogger.log("PACKET_TASK", "No socket available for $destKey, packet dropped")
                }
                
                Thread.sleep(10)
            }
            debugLogger.log("PACKET_PROCESSOR", "Packet processor thread ended")
        }
    }

    private fun startResponseHandler(srcPort: Int, socket: Socket, destIp: String, destPort: Int) {
        workerPool.execute {
            // ✅ GUARD: Check jika handler sudah ada SEBELUM proceed
            synchronized(tcpConnections) {
                if (tcpConnections.containsKey(srcPort)) {
                    debugLogger.log("RESPONSE_HANDLER", "⚠️ Handler already running for srcPort $srcPort, skipping")
                    return@execute
                }
                tcpConnections[srcPort] = socket
            }
            
            debugLogger.log("RESPONSE_HANDLER", "🚀 Handler STARTED for srcPort $srcPort -> $destIp:$destPort")
            
            val outStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val inStream = socket.getInputStream()
            val buffer = ByteArray(2048)
            
            try {
                // 1. Send SYN-ACK simulation
                debugLogger.log("RESPONSE_HANDLER", "Simulating SYN-ACK for initial handshake")
                val synAckPayload = ByteArray(0)
                val synAckPacket = buildTcpPacket(destIp, destPort, "10.0.0.2", srcPort, synAckPayload)
                synAckPacket[33] = 0x12.toByte()  // SYN+ACK flags
                
                outStream.write(synAckPacket)
                outStream.flush()
                debugLogger.log("RESPONSE_TX", "✅ Sent SYN-ACK to app for srcPort $srcPort")
                
                // 2. Wait for ACK from app (timeout pendek)
                Thread.sleep(100) // Beri masa app hantar ACK
                
                // 3. Teruskan dengan baca data dari server
                socket.soTimeout = 15000  // Timeout panjang sikit
                
                while (forwardingActive && socket.isConnected && !socket.isClosed) {
                    val n = inStream.read(buffer)
                    if (n <= 0) {
                        debugLogger.log("RESPONSE_HANDLER", "No more data from srcPort $srcPort")
                        break
                    }
                    
                    debugLogger.log("RESPONSE_RX", "✅ RECEIVED $n bytes from server")
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
                synchronized(tcpConnections) {
                    tcpConnections.remove(srcPort)
                }
                
                if (socket.isConnected && !socket.isClosed) {
                    debugLogger.log("SOCKET_POOL", "Returning socket to pool")
                    connectionPool.returnSocket(destIp, destPort, socket)
                } else {
                    socket.close()
                    debugLogger.log("SOCKET_POOL", "Socket closed")
                }
                debugLogger.log("RESPONSE_HANDLER", "Handler ENDED for srcPort $srcPort")
            }
        }
    }

    private fun buildTcpPacket(srcIp: String, srcPort: Int, destIp: String, destPort: Int, payload: ByteArray): ByteArray {
        debugLogger.log("PACKET_BUILD", "Building TCP packet: $srcIp:$srcPort -> $destIp:$destPort, Payload: ${payload.size} bytes")
        
        val totalLen = 40 + payload.size
        val packet = ByteArray(totalLen)
        
        // IPv4 Header
        packet[0] = 0x45.toByte()  // Version + IHL
        packet[1] = 0x00           // DSCP + ECN
        packet[2] = (totalLen ushr 8).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x00           // Identification
        packet[5] = 0x00
        packet[6] = 0x40           // Flags + Fragment Offset (Don't Fragment)
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
        
        // Sequence Number (random untuk setiap packet)
        val seqNum = (Math.random() * Int.MAX_VALUE).toInt()
        packet[24] = (seqNum ushr 24).toByte()
        packet[25] = (seqNum ushr 16).toByte()
        packet[26] = (seqNum ushr 8).toByte()
        packet[27] = (seqNum and 0xFF).toByte()
        
        // Acknowledgment Number (0 untuk packet pertama)
        packet[28] = 0x00
        packet[29] = 0x00
        packet[30] = 0x00
        packet[31] = 0x00
        
        // Data Offset + Reserved + Flags
        packet[32] = 0x50           // Data Offset = 5 (20 bytes), Reserved = 0
        
        // TCP Flags: SYN untuk connection initiation, PSH+ACK untuk data
        val flags = if (payload.isEmpty()) 0x02 else 0x18  // SYN atau PSH+ACK
        packet[33] = flags.toByte()
       
        // ✅ TAMBAH LOG INI
        debugLogger.log("TCP_FLAGS", "Packet flags: 0x${flags.toString(16)} (${if (flags == 0x02) "SYN" else if (flags == 0x12) "SYN+ACK" else if (flags == 0x10) "ACK" else "DATA"})")
        
        // Window Size (65535)
        packet[34] = 0xFF.toByte()
        packet[35] = 0xFF.toByte()
        
        // TCP Checksum (akan dikira)
        packet[36] = 0x00
        packet[37] = 0x00
        
        // Urgent Pointer
        packet[38] = 0x00
        packet[39] = 0x00
        
        // Payload
        System.arraycopy(payload, 0, packet, 40, payload.size)
        
        // Calculate IP checksum
        calculateIpChecksum(packet)
        
        // Calculate TCP checksum (simplified untuk test dulu)
        calculateSimpleTcpChecksum(packet)
        
        debugLogger.log("PACKET_BUILD", "Packet built: ${packet.size} bytes total, Flags: 0x${flags.toString(16)}, Seq: $seqNum")
        return packet
    }

    // ==================== FUNGSI BARU 1 ====================
    private fun calculateIpChecksum(packet: ByteArray) {
        var sum = 0
        
        // Calculate sum for first 20 bytes (IP header)
        for (i in 0 until 20 step 2) {
            if (i != 10) { // Skip checksum field itself
                sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            }
        }
        
        // Fold 32-bit sum to 16-bit
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        
        val checksum = sum.inv() and 0xFFFF
        
        // Store checksum in big-endian
        packet[10] = (checksum ushr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()
    }
    
    // ==================== FUNGSI BARU 2 ====================
    private fun calculateSimpleTcpChecksum(packet: ByteArray) {
        // Simplified TCP checksum (untuk test dulu)
        // Dalam implementasi sebenar, kena include pseudo-header
        
        var sum = 0
        
        // TCP header (bytes 20-39)
        for (i in 20 until 40 step 2) {
            if (i != 36) { // Skip TCP checksum field
                sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            }
        }
        
        // Fold 32-bit sum to 16-bit
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        
        val checksum = sum.inv() and 0xFFFF
        
        // Store TCP checksum
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
            debugLogger.log("VPN_ERROR", "Error closing VPN interface: ${e.message}")
        }
        
        pandaActive = false
        lastPacketTime = 0L
        instance = null
        
        // Save debug log to file
        val logPath = debugLogger.saveToFile(this)
        if (logPath.isNotEmpty()) {
            android.util.Log.d("CB_DEBUG", "Debug log saved to: $logPath")
        } else {
            android.util.Log.e("CB_DEBUG", "Failed to save debug log")
        }
        
        debugLogger.log("SERVICE", "Service destruction complete")
        debugLogger.clear()
        
        super.onDestroy()
    }
}
