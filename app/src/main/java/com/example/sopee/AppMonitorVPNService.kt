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
        
        // NEW FUNCTION: Capture FULL system logcat
        fun captureLogcatToFile(context: android.content.Context): String {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "CedokBooster_FULL_Logcat_${timestamp}.txt"
            
            // Save to public Downloads folder
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            
            val logcatFile = File(downloadsDir, filename)
            
            try {
                // Run logcat command
                val process = Runtime.getRuntime().exec("logcat -d -v time")
                val input = process.inputStream.bufferedReader()
                val logcatContent = input.readText()
                
                // Write to file
                PrintWriter(FileOutputStream(logcatFile)).use { writer ->
                    writer.println("=== FULL LOGCAT CAPTURE ===")
                    writer.println("Timestamp: $timestamp")
                    writer.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    writer.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    writer.println("=".repeat(60))
                    writer.println()
                    writer.write(logcatContent)
                }
                
                // Clear logcat buffer untuk elak duplicate entries next time
                Runtime.getRuntime().exec("logcat -c")
                
                return logcatFile.absolutePath
            } catch (e: Exception) {
                android.util.Log.e("CB_DEBUG", "Logcat capture failed: ${e.message}")
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
                    // Track active connection
                    tcpConnections[task.srcPort] = socket
                    
                    // Send data
                    try {
                        debugLogger.log("SOCKET_SEND", "Sending ${task.packet.size} bytes to $destKey")
                        socket.getOutputStream().write(task.packet)
                        socket.getOutputStream().flush()
                        debugLogger.log("SOCKET_SEND", "Successfully sent data to $destKey")
                        
                        // Start response handler if not already
                        if (!tcpConnections.containsKey(task.srcPort)) {
                            debugLogger.log("RESPONSE_HANDLER", "Starting response handler for srcPort ${task.srcPort}")
                            startResponseHandler(task.srcPort, socket, task.destIp, task.destPort)
                        }
                    } catch (e: Exception) {
                        debugLogger.log("SOCKET_SEND_ERROR", "Error sending to $destKey: ${e.message}")
                        socket.close()
                        tcpConnections.remove(task.srcPort)
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
            debugLogger.log("RESPONSE_HANDLER", "Response handler started for srcPort $srcPort -> $destIp:$destPort")
            val outStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val inStream = socket.getInputStream()
            val buffer = ByteArray(2048)
            
            try {
                while (forwardingActive && socket.isConnected && !socket.isClosed) {
                    val n = inStream.read(buffer)
                    if (n <= 0) {
                        debugLogger.log("RESPONSE_HANDLER", "No more data from srcPort $srcPort, ending")
                        break
                    }
                    
                    debugLogger.log("RESPONSE_RX", "Received $n bytes response from $destIp:$destPort for srcPort $srcPort")
                    val reply = buildTcpPacket(destIp, destPort, "10.0.0.2", srcPort, buffer.copyOfRange(0, n))
                    outStream.write(reply)
                    outStream.flush()
                    debugLogger.log("RESPONSE_TX", "Forwarded $n bytes response back to app")
                }
            } catch (e: Exception) {
                debugLogger.log("RESPONSE_HANDLER_ERROR", "Error in response handler for srcPort $srcPort: ${e.message}")
            } finally {
                // Return socket to pool if still usable
                if (socket.isConnected && !socket.isClosed) {
                    debugLogger.log("SOCKET_POOL", "Returning socket for $destIp:$destPort to pool")
                    connectionPool.returnSocket(destIp, destPort, socket)
                } else {
                    debugLogger.log("SOCKET_POOL", "Socket for $destIp:$destPort is closed, not returning to pool")
                    socket.close()
                }
                tcpConnections.remove(srcPort)
                debugLogger.log("RESPONSE_HANDLER", "Response handler ended for srcPort $srcPort")
            }
        }
    }

    private fun handleOutboundPacket(packet: ByteArray) {
        try {
            if (packet.size < 20) return
            
            val version = (packet[0].toInt() and 0xF0) shr 4
            if (version != 4) return
            
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (ihl < 20 || ihl > packet.size) return
            
            val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
            if (totalLength < ihl) return
            
            val protocol = packet[9].toInt() and 0xFF
            if (protocol != 6) return // TCP only
            
            val destIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
            
            // FIX: Proper TCP header length calculation
            if (packet.size < ihl + 20) return // Need at least TCP header
            
            val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
            val destPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            
            // TCP Data Offset (header length in 32-bit words)
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
            val flagStr = when {
                (tcpFlags and 0x02) != 0 -> "SYN"
                (tcpFlags and 0x10) != 0 -> "ACK"
                (tcpFlags and 0x01) != 0 -> "FIN"
                (tcpFlags and 0x04) != 0 -> "RST"
                else -> "DATA"
            }
            
            debugLogger.log("PACKET_PARSE", "$destIp:$destPort <- 10.0.0.2:$srcPort ($flagStr), TCP HL: $tcpHeaderLength, Payload: ${payload.size} bytes")
            
            // Only process packets with actual data (not just SYN/ACK)
            if (payload.isNotEmpty() || (tcpFlags and 0x08) != 0) { // PSH flag
                val priority = EndpointConfig.getPriorityForHost(destIp)
                priorityManager.addPacket(payload, destIp, destPort, srcPort)
                debugLogger.log("QUEUE", "Added to queue (priority=$priority). Size: ${priorityManager.queueSize()}")
            } else {
                debugLogger.log("PACKET_PARSE", "Skipping control packet ($flagStr) with no data")
            }
            
        } catch (e: Exception) {
            debugLogger.log("PACKET_PARSE_ERROR", "Error: ${e.message}")
        }
    }

    private fun handleUdpPacket(packet: ByteArray) {
        try {
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (packet.size < ihl + 8) return
            
            val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
            val destPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            
            // DNS is typically port 53
            if (destPort == 53 || srcPort == 53) {
                val destIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"
                
                debugLogger.log("DNS", "DNS query to $destIp:$destPort")
                
                // Forward DNS through normal socket (bypass VPN)
                forwardDnsQuery(packet, ihl + 8)
            }
        } catch (e: Exception) {
            debugLogger.log("DNS_ERROR", "Error: ${e.message}")
        }
    }
    
    // In handleOutboundPacket, add UDP case:
    if (protocol == 17) { // UDP
        handleUdpPacket(packet)
        return
    }

    private fun buildTcpPacket(srcIp: String, srcPort: Int, destIp: String, destPort: Int, payload: ByteArray): ByteArray {
        debugLogger.log("PACKET_BUILD", "Building TCP packet: $srcIp:$srcPort -> $destIp:$destPort, Payload: ${payload.size} bytes")
        
        // (Keep existing implementation, Tuan)
        val totalLen = 40 + payload.size
        val packet = ByteArray(totalLen)
        packet[0] = 0x45
        packet[2] = (totalLen ushr 8).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[8] = 0xFF.toByte()
        packet[9] = 0x06
        val src = srcIp.split(".")
        packet[12] = src[0].toUByte().toByte()
        packet[13] = src[1].toUByte().toByte()
        packet[14] = src[2].toUByte().toByte()
        packet[15] = src[3].toUByte().toByte()
        val dest = destIp.split(".")
        packet[16] = dest[0].toUByte().toByte()
        packet[17] = dest[1].toUByte().toByte()
        packet[18] = dest[2].toUByte().toByte()
        packet[19] = dest[3].toUByte().toByte()
        packet[20] = (srcPort ushr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (destPort ushr 8).toByte()
        packet[23] = (destPort and 0xFF).toByte()
        packet[32] = 0x50
        packet[33] = if (payload.isEmpty()) 0x02 else 0x18.toByte()
        packet[34] = 0xFF.toByte()
        packet[35] = 0xFF.toByte()
        System.arraycopy(payload, 0, packet, 40, payload.size)
        
        debugLogger.log("PACKET_BUILD", "Packet built: ${packet.size} bytes total")
        return packet
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
        
        // CAPTURE FULL LOGCAT sebelum save debug log
        val logcatPath = debugLogger.captureLogcatToFile(this)
        if (logcatPath.isNotEmpty()) {
            debugLogger.log("LOGCAT", "Full system logcat saved to: $logcatPath")
        }
        
        // Save our debug log
        val debugLogPath = debugLogger.saveToFile(this)
        if (debugLogPath.isNotEmpty()) {
            debugLogger.log("DEBUG_LOG", "Debug log saved to: $debugLogPath")
        }
        
        // Show Toast notification dengan file locations
        android.os.Handler(mainLooper).post {
            val message = if (logcatPath.isNotEmpty() && debugLogPath.isNotEmpty()) {
                "Logs saved to Downloads:\n1. ${File(logcatPath).name}\n2. ${File(debugLogPath).name}"
            } else {
                "Logs saved to Downloads folder"
            }
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
        }
        
        debugLogger.clear()
        
        super.onDestroy()
    }
}
