package com.example.sopee

object EndpointConfig {

    val CRITICAL_ENDPOINTS = setOf(
        "food-driver.shopee.com.my",          // Server utama live order & logistik
        "foody.shopee.com.my",                // Server live operations (suspected)
        "remote-config.gslb.sgw.shopeemobile.com" // Konfigurasi real-time app
    )

    val IMPORTANT_ENDPOINTS = setOf(
        "ubt.tracking.shopee.com.my",         // User behavior tracking
        "apm.tracking.shopee.com.my",         // Application performance monitoring
        "patronus.idata.shopeemobile.com",    // Data/analytics service
        "food-metric.shopee.com.my"           // Telemetry & metrics order (suspected untuk order masuk)
    )

    val BACKGROUND_ENDPOINTS = setOf(
        "graph.facebook.com",                 // Social login/sharing (Facebook SDK)
        "98dbdw-launches.appsflyersdk.com",   // Marketing & install analytics (AppsFlyer)
        "mall.shopee.my",
        "shopee.com.my"
    )

    const val CRITICAL_KEEP_ALIVE_MS = 120000L    // 2 minit - untuk connection critical
    const val IMPORTANT_KEEP_ALIVE_MS = 60000L    // 1 minit  - untuk connection penting
    const val BACKGROUND_KEEP_ALIVE_MS = 30000L   // 30 saat - untuk background traffic
    const val MAX_CONNECTIONS_PER_ENDPOINT = 3

object EndpointConfig {

    private val ipPriorityMap = mapOf(
        // Shopee Foody Driver Malaysia IPs
        "143.92.88.1" to 3,
        "143.92.88.2" to 3,
        "143.92.88.3" to 3,
        "143.92.88.4" to 3,
        "143.92.88.5" to 3,
        
        // Shopee Order API IPs  
        "143.92.73.201" to 3,
        "143.92.73.202" to 3,
        "143.92.73.203" to 3,
        "143.92.73.204" to 3,
        
        // ShopeePay Malaysia IPs
        "147.136.140.92" to 3,
        "147.136.140.93" to 3,
        "147.136.140.94" to 3,
        "147.136.140.95" to 3,
        
        // DNS Servers
        "8.8.8.8" to 1,
        "8.8.4.4" to 1,
        "156.154.70.1" to 1  // DNS server dari log Tuan
    )

    fun getPriorityForHost(host: String): Int {
        if (host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            val priority = ipPriorityMap[host]
            if (priority != null) {
                return priority
            }
        }
        
        return when {
            CRITICAL_ENDPOINTS.any { host.endsWith(it) } -> 3
            IMPORTANT_ENDPOINTS.any { host.endsWith(it) } -> 2
            BACKGROUND_ENDPOINTS.any { host.endsWith(it) } -> 1
            else -> 1  // ⚠️ UBAH: Default priority 1 (bukan 0!)
        }
    }

    fun getKeepAliveForHost(host: String): Long {
        return when {
            CRITICAL_ENDPOINTS.any { host.endsWith(it) } -> CRITICAL_KEEP_ALIVE_MS
            IMPORTANT_ENDPOINTS.any { host.endsWith(it) } -> IMPORTANT_KEEP_ALIVE_MS
            BACKGROUND_ENDPOINTS.any { host.endsWith(it) } -> BACKGROUND_KEEP_ALIVE_MS
            else -> 30000L // Default 30 saat untuk traffic unknown
        }
    }
}
