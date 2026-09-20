package com.example.cademeuonibus.network

import android.util.LruCache
import com.example.cademeuonibus.data.VeiculoApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CachedResponse(
    val data: List<VeiculoApi>,
    val timestamp: Long,
    val etag: String? = null
)

/**
 * Cache em memória das últimas posições por linha.
 */
class NetworkCache {
    companion object {
        private const val CACHE_SIZE = 100
    }

    private val cache = LruCache<String, CachedResponse>(CACHE_SIZE)
    private val cacheMutex = Mutex()

    /**
     * Retorna a última posição conhecida dentro da idade máxima solicitada.
     */
    suspend fun getCachedResponse(linha: String, maxAgeMs: Long): List<VeiculoApi>? {
        return cacheMutex.withLock {
            val cached = cache.get(linha.normalizar()) ?: return@withLock null
            if (System.currentTimeMillis() - cached.timestamp <= maxAgeMs) cached.data else null
        }
    }

    suspend fun cacheResponse(linha: String, data: List<VeiculoApi>, etag: String? = null) {
        cacheMutex.withLock {
            cache.put(linha.normalizar(), CachedResponse(data, System.currentTimeMillis(), etag))
        }
    }

    suspend fun getETag(linha: String): String? {
        return cacheMutex.withLock {
            cache.get(linha.normalizar())?.etag
        }
    }

    suspend fun clearCache() {
        cacheMutex.withLock {
            cache.evictAll()
        }
    }

    suspend fun getCacheStats(): String {
        return cacheMutex.withLock {
            "Cache: ${cache.size()}/$CACHE_SIZE entries"
        }
    }

    private fun String.normalizar() = uppercase().trim()
}
