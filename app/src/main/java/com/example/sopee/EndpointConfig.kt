package com.example.sopee

object EndpointConfig {
    /* ================================
     *  SENARAI ENDPOINT SHOPEE RIDER
     *  Disusun ikut priority & fungsi
     * ================================ */

    // 1. ENDPOINT CRITICAL (Priority 3)
    //    - Untuk operasi LIVE app: order, tracking driver, config masa nyata.
    //    - Keep-alive paling lama, connection pool prioriti tinggi.
    val CRITICAL_ENDPOINTS = setOf(
        "food-driver.shopee.com.my",          // Server utama live order & logistik
        "foody.shopee.com.my",                // Server live operations (suspected)
        "remote-config.gslb.sgw.shopeemobile.com" // Konfigurasi real-time app
    )

    // 2. ENDPOINT IMPORTANT (Priority 2)
    //    - Untuk tracking, metrics & data analytics yang penting.
    //    - Keep-alive sederhana.
    val IMPORTANT_ENDPOINTS = setOf(
        "ubt.tracking.shopee.com.my",         // User behavior tracking
        "apm.tracking.shopee.com.my",         // Application performance monitoring
        "patronus.idata.shopeemobile.com",    // Data/analytics service
        "food-metric.shopee.com.my"           // Telemetry & metrics order (suspected untuk order masuk)
    )

    // 3. ENDPOINT BACKGROUND (Priority 1)
    //    - Untuk logging, analytics pihak ketiga & domain umum.
    //    - Keep-alive paling pendek, connection boleh di-terminate cepat.
    val BACKGROUND_ENDPOINTS = setOf(
        "graph.facebook.com",                 // Social login/sharing (Facebook SDK)
        "98dbdw-launches.appsflyersdk.com",   // Marketing & install analytics (AppsFlyer)
        "mall.shopee.my",
        "shopee.com.my"
        // Nota: "endpoint.mms.shopee.com.my" tidak dimasukkan kerana traffic minimum.
    )

    /* ================================
     *  KONFIGURASI KONEKSI & TIMING
     * ================================ */

    // Masa keep-alive untuk connection pool (dalam millisecond)
    const val CRITICAL_KEEP_ALIVE_MS = 120000L    // 2 minit - untuk connection critical
    const val IMPORTANT_KEEP_ALIVE_MS = 60000L    // 1 minit  - untuk connection penting
    const val BACKGROUND_KEEP_ALIVE_MS = 30000L   // 30 saat - untuk background traffic

    // Had bilangan connection serentak per endpoint
    // Mengelak resource exhaustion pada server
    const val MAX_CONNECTIONS_PER_ENDPOINT = 3

    /* ================================
     *  FUNGSI UTILITI UNTUK CHECK
     * ================================ */

    /**
     * Fungsi untuk dapatkan priority level berdasarkan destination host.
     * Digunakan dalam TrafficPriorityManager.
     *
     * @param host Destination host (contoh: "food-driver.shopee.com.my")
     * @return Priority level integer (3=Critical, 2=Important, 1=Background, 0=Default/Unknown)
     */
    fun getPriorityForHost(host: String): Int {
        return when {
            CRITICAL_ENDPOINTS.any { host.endsWith(it) } -> 3
            IMPORTANT_ENDPOINTS.any { host.endsWith(it) } -> 2
            BACKGROUND_ENDPOINTS.any { host.endsWith(it) } -> 1
            else -> 0 // Traffic yang tidak dalam senarai (contoh: update Play Store)
        }
    }

    /**
     * Fungsi untuk dapatkan keep-alive time berdasarkan destination host.
     * Digunakan dalam ConnectionPool.
     *
     * @param host Destination host
     * @return Keep-alive duration dalam milliseconds
     */
    fun getKeepAliveForHost(host: String): Long {
        return when {
            CRITICAL_ENDPOINTS.any { host.endsWith(it) } -> CRITICAL_KEEP_ALIVE_MS
            IMPORTANT_ENDPOINTS.any { host.endsWith(it) } -> IMPORTANT_KEEP_ALIVE_MS
            BACKGROUND_ENDPOINTS.any { host.endsWith(it) } -> BACKGROUND_KEEP_ALIVE_MS
            else -> 30000L // Default 30 saat untuk traffic unknown
        }
    }
}
