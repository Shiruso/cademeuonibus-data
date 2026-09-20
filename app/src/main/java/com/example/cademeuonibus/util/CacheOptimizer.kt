package com.example.cademeuonibus.util

import kotlin.math.sqrt
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.example.cademeuonibus.data.ShapePoint

/**
 * Otimizador de cache para cálculos pesados do DirecaoInferidor
 */
object CacheOptimizer {
    
    // Caches para evitar recálculos
    private val cacheAngulos = MutableMapMap<String, Int, Double>()
    private val cacheCurvaturas = MutableMapMap<String, Int, Double>()
    private val cacheDistancias = mutableMapOf<String, Pair<Int, Double>>()
    
    private class MutableMapMap<K1, K2, V> {
        private val map = mutableMapOf<K1, MutableMap<K2, V>>()
        
        fun getOrPut(k1: K1, k2: K2, defaultValue: () -> V): V {
            return map.getOrPut(k1) { mutableMapOf() }.getOrPut(k2, defaultValue)
        }
        
        fun clear() {
            map.clear()
        }
    }
    
    /**
     * Cache para busca local de ponto mais próximo (otimização para veículos em movimento)
     */
    fun indiceMaisProximoComCache(
        shape: List<ShapePoint>, 
        lat: Double, 
        lng: Double, 
        shapeId: String
    ): Pair<Int, Double> {
        
        // Verifica se existe cache válido
        val cached = cacheDistancias[shapeId]
        if (cached != null) {
            val (cachedIdx, _) = cached
            // Busca local ao redor do último ponto conhecido (±10 pontos)
            val inicio = max(0, cachedIdx - 10)
            val fim = min(shape.size, cachedIdx + 10)
            
            var melhorIdx = cachedIdx
            var melhorDist = Double.MAX_VALUE
            
            for (i in inicio until fim) {
                val dist = distSq(lat, lng, shape[i].lat, shape[i].lng)
                if (dist < melhorDist) {
                    melhorDist = dist
                    melhorIdx = i
                }
            }
            
            // Se encontrou um ponto melhor na vizinhança, atualiza cache
            if (melhorIdx != cachedIdx) {
                cacheDistancias[shapeId] = melhorIdx to melhorDist
            }
            
            return melhorIdx to melhorDist
        }
        
        // Busca completa na primeira vez
        return indiceMaisProximoCompleto(shape, lat, lng, shapeId)
    }
    
    private fun indiceMaisProximoCompleto(
        shape: List<ShapePoint>,
        lat: Double,
        lng: Double,
        shapeId: String
    ): Pair<Int, Double> {
        val n = shape.size
        if (n == 0) return 0 to Double.MAX_VALUE

        val passo = max(1, n / 200)
        var melhorIdx = 0
        var melhorD = Double.MAX_VALUE

        // Varredura grosseira
        var i = 0
        while (i < n) {
            val d = distSq(lat, lng, shape[i].lat, shape[i].lng)
            if (d < melhorD) {
                melhorD = d
                melhorIdx = i
            }
            i += passo
        }

        // Refinamento na vizinhança
        val inicio = max(0, melhorIdx - passo)
        val fim = min(n, melhorIdx + passo + 1)
        for (j in inicio until fim) {
            val d = distSq(lat, lng, shape[j].lat, shape[j].lng)
            if (d < melhorD) {
                melhorD = d
                melhorIdx = j
            }
        }

        // Armazena no cache
        cacheDistancias[shapeId] = melhorIdx to melhorD
        return melhorIdx to melhorD
    }
    
    /**
     * Cache para ângulo da rota
     */
    fun calcularAnguloRotaComCache(
        shape: List<ShapePoint>,
        idx: Int,
        shapeId: String
    ): Double {
        return cacheAngulos.getOrPut(shapeId, idx) {
            calcularAnguloRota(shape, idx)
        }
    }
    
    /**
     * Cache para curvatura da rota
     */
    fun calcularCurvaturaRotaComCache(
        shape: List<ShapePoint>,
        idx: Int,
        shapeId: String
    ): Double {
        return cacheCurvaturas.getOrPut(shapeId, idx) {
            calcularCurvaturaRota(shape, idx)
        }
    }
    
    private fun calcularAnguloRota(shape: List<ShapePoint>, idx: Int): Double {
        if (shape.size <= 1) return 0.0
        
        val idxFrente = min(shape.size - 1, idx + 5)
        if (idxFrente == idx) return 0.0
        
        val dxRota = shape[idxFrente].lng - shape[idx].lng
        val dyRota = shape[idxFrente].lat - shape[idx].lat
        
        return Math.toDegrees(atan2(dyRota, dxRota))
    }
    
    private fun calcularCurvaturaRota(shape: List<ShapePoint>, idx: Int): Double {
        val idxFinal = min(shape.size - 1, idx + 15)
        
        if (idxFinal - idx < 3) return 0.0
        
        val mudancas = mutableListOf<Double>()
        
        for (i in idx until idxFinal - 2) {
            val anguloSegmento1 = Math.toDegrees(atan2(
                shape[i + 1].lat - shape[i].lat,
                shape[i + 1].lng - shape[i].lng
            ))
            
            val anguloSegmento2 = Math.toDegrees(atan2(
                shape[i + 2].lat - shape[i + 1].lat,
                shape[i + 2].lng - shape[i + 1].lng
            ))
            
            val mudanca = calcularDivergenciaAngular(anguloSegmento1, anguloSegmento2)
            mudancas.add(mudanca)
        }
        
        return if (mudancas.size >= 3) {
            mudancas.sortedDescending().take(3).average()
        } else {
            mudancas.average()
        }
    }
    
    private fun calcularDivergenciaAngular(angulo1: Double, angulo2: Double): Double {
        var diff = abs(angulo1 - angulo2)
        if (diff > 180.0) {
            diff = 360.0 - diff
        }
        return diff
    }
    
    private fun distSq(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = lat2 - lat1
        val dLng = (lng2 - lng1) * 0.9205
        return dLat * dLat + dLng * dLng
    }
    
    /**
     * Limpa todos os caches (útil quando muda de linha)
     */
    fun clearCache() {
        cacheAngulos.clear()
        cacheCurvaturas.clear()
        cacheDistancias.clear()
    }
    
    /**
     * Remove cache de uma linha específica
     */
    fun clearCacheForLine(numeroLinha: String) {
        val keysToRemove = cacheDistancias.keys.filter { it.startsWith(numeroLinha) }
        keysToRemove.forEach { cacheDistancias.remove(it) }
    }
}