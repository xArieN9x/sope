package com.example.sopee

object EndpointConfig {
    // 1. ENDPOINT CRITICAL (Priority 3)
    val CRITICAL_ENDPOINTS = setOf(
        "food-driver.shopee.com.my",
        "foody.shopee.com.my",
        "remote-config.gslb.sgw.shopeemobile.com"
    )

    // 2. ENDPOINT IMPORTANT (Priority 2)
    val IMPORTANT_ENDPOINTS = setOf(
        "ubt.tracking.shopee.com.my",
        "apm.tracking.shopee.com.my",
        "patronus.idata.shopeemobile.com",
        "food-metric.shopee.com.my"
    )

    // 3. ENDPOINT BACKGROUND (Priority 1)
    val BACKGROUND_ENDPOINTS = setOf(
        "graph.facebook.com",
        "98dbdw-launches.appsflyersdk.com",
        "mall.shopee.my",
        "shopee.com.my"
    )

    // IP-to-Priority Mapping (TAMBAH INI!)
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
        "156.154.70.1" to 1
    )

    // Connection timing
    const val CRITICAL_KEEP_ALIVE_MS = 120000L
    const val IMPORTANT_KEEP_ALIVE_MS = 60000L
    const val BACKGROUND_KEEP_ALIVE_MS = 30000L
    const val MAX_CONNECTIONS_PER_ENDPOINT = 3

    /**
     * Get priority for host/IP
     */
    fun getPriorityForHost(host: String): Int {
        // Check IP mapping first
        if (host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
            return ipPriorityMap[host] ?: 1  // Default 1 untuk IP unknown
        }
        
        // Check domain
        return when {
            CRITICAL_ENDPOINTS.any { host.endsWith(it) } -> 3
            IMPORTANT_ENDPOINTS.any { host.endsWith(it) } -> 2
            BACKGROUND_ENDPOINTS.any { host.endsWith(it) } -> 1
            else -> 1  // Default priority 1
        }
    }

    /**
     * Get keep-alive time for host
     */
    fun getKeepAliveForHost(host: String): Long {
        return when (getPriorityForHost(host)) {
            3 -> CRITICAL_KEEP_ALIVE_MS
            2 -> IMPORTANT_KEEP_ALIVE_MS
            else -> BACKGROUND_KEEP_ALIVE_MS
        }
    }
}
