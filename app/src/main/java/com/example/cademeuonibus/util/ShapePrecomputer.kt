package com.example.cademeuonibus.util

import android.util.LruCache
import com.example.cademeuonibus.data.ShapePoint
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * Pre-computador de dados de shapes para otimizar consultas frequentes
 */
object ShapePrecomputer {
    
    private data class PrecomputedShape(
        val simplifiedPoints: List<ShapePoint>,  // Pontos simplificados
        val segmentLengths: List<Double>,        // Comprimentos dos segmentos
        val cumulativeDistances: List<Double>,   // Distâncias cumulativas
        val boundingBox: BoundingBox             // Bounding box para filtros rápidos
    )
    
    private data class BoundingBox(
        val minLat: Double, val maxLat: Double,
        val minLng: Double, val maxLng: Double
    ) {
        fun contains(lat: Double, lng: Double, margin: Double = 0.005): Boolean {
            return lat >= minLat - margin && lat <= maxLat + margin &&
                   lng >= minLng - margin && lng <= maxLng + margin
        }
    }
    
    // Cache LRU para shapes pre-computados
    private val precomputedCache = LruCache<String, PrecomputedShape>(50)
    
    /**
     * Pre-computa dados de um shape para consultas otimizadas
     */
    suspend fun precomputeShape(shapeId: String, originalPoints: List<ShapePoint>) = withContext(Dispatchers.Default) {
        if (originalPoints.isEmpty()) return@withContext
        
        // Verifica se já está em cache
        precomputedCache[shapeId]?.let { return@withContext }
        
        // Simplifica pontos usando Douglas-Peucker
        val simplifiedPoints = simplifyPoints(originalPoints, tolerance = 0.0001) // ~10m
        
        // Calcula comprimentos dos segmentos
        val segmentLengths = mutableListOf<Double>()
        val cumulativeDistances = mutableListOf<Double>()
        var totalDistance = 0.0
        
        for (i in 0 until simplifiedPoints.size - 1) {
            val dist = distanciaRapida(
                simplifiedPoints[i].lat, simplifiedPoints[i].lng,
                simplifiedPoints[i + 1].lat, simplifiedPoints[i + 1].lng
            )
            segmentLengths.add(dist)
            totalDistance += dist
            cumulativeDistances.add(totalDistance)
        }
        
        // Calcula bounding box
        val boundingBox = BoundingBox(
            minLat = simplifiedPoints.minOf { it.lat },
            maxLat = simplifiedPoints.maxOf { it.lat },
            minLng = simplifiedPoints.minOf { it.lng },
            maxLng = simplifiedPoints.maxOf { it.lng }
        )
        
        val precomputed = PrecomputedShape(
            simplifiedPoints = simplifiedPoints,
            segmentLengths = segmentLengths,
            cumulativeDistances = cumulativeDistances,
            boundingBox = boundingBox
        )
        
        precomputedCache.put(shapeId, precomputed)
    }
    
    /**
     * Busca otimizada do ponto mais próximo usando dados pre-computados
     */
    fun findNearestPointOptimized(shapeId: String, lat: Double, lng: Double): Pair<Int, Double>? {
        val precomputed = precomputedCache[shapeId] ?: return null
        
        // Early exit se ponto está fora do bounding box
        if (!precomputed.boundingBox.contains(lat, lng)) {
            return null
        }
        
        val points = precomputed.simplifiedPoints
        var minDistance = Double.MAX_VALUE
        var nearestIndex = 0
        
        // Busca linear otimizada nos pontos simplificados
        for (i in points.indices) {
            val dist = distanciaSqRapida(lat, lng, points[i].lat, points[i].lng)
            if (dist < minDistance) {
                minDistance = dist
                nearestIndex = i
            }
        }
        
        return nearestIndex to minDistance
    }
    
    /**
     * Simplificação de pontos usando algoritmo Douglas-Peucker otimizado
     */
    private fun simplifyPoints(points: List<ShapePoint>, tolerance: Double): List<ShapePoint> {
        if (points.size <= 2) return points
        
        val simplified = mutableListOf<ShapePoint>()
        simplified.add(points.first())
        
        simplifyRecursive(points, 0, points.size - 1, tolerance, simplified)
        
        simplified.add(points.last())
        return simplified.distinctBy { "${(it.lat * 10000).toInt()}_${(it.lng * 10000).toInt()}" }
    }
    
    private fun simplifyRecursive(
        points: List<ShapePoint>, 
        start: Int, 
        end: Int, 
        tolerance: Double, 
        result: MutableList<ShapePoint>
    ) {
        var maxDistance = 0.0
        var maxIndex = start
        
        // Encontra ponto mais distante da linha start->end
        for (i in start + 1 until end) {
            val distance = perpendicularDistance(points[i], points[start], points[end])
            if (distance > maxDistance) {
                maxDistance = distance
                maxIndex = i
            }
        }
        
        // Se distância é maior que tolerância, subdivide
        if (maxDistance > tolerance) {
            simplifyRecursive(points, start, maxIndex, tolerance, result)
            result.add(points[maxIndex])
            simplifyRecursive(points, maxIndex, end, tolerance, result)
        }
    }
    
    private fun perpendicularDistance(point: ShapePoint, lineStart: ShapePoint, lineEnd: ShapePoint): Double {
        val dx = lineEnd.lng - lineStart.lng
        val dy = lineEnd.lat - lineStart.lat
        
        if (dx == 0.0 && dy == 0.0) {
            return distanciaRapida(point.lat, point.lng, lineStart.lat, lineStart.lng)
        }
        
        val t = ((point.lng - lineStart.lng) * dx + (point.lat - lineStart.lat) * dy) / (dx * dx + dy * dy)
        val clampedT = maxOf(0.0, minOf(1.0, t))
        
        val projectionLng = lineStart.lng + clampedT * dx
        val projectionLat = lineStart.lat + clampedT * dy
        
        return distanciaRapida(point.lat, point.lng, projectionLat, projectionLng)
    }
    
    /**
     * Distância rápida para comparações
     */
    private fun distanciaRapida(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * 111000.0
        val dLng = (lng2 - lng1) * 111000.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dLat * dLat + dLng * dLng)
    }
    
    private fun distanciaSqRapida(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * 111000.0
        val dLng = (lng2 - lng1) * 111000.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return dLat * dLat + dLng * dLng
    }
    
    /**
     * Pre-computa shapes mais frequentes em background
     */
    fun precomputeFrequentShapes(frequentShapes: Map<String, List<ShapePoint>>) {
        BackgroundProcessor.scheduleBackground {
            frequentShapes.forEach { (shapeId, points) ->
                precomputeShape(shapeId, points)
            }
        }
    }
    
    /**
     * Limpa cache
     */
    fun clearCache() {
        precomputedCache.evictAll()
    }
    
    /**
     * Estatísticas do cache
     */
    fun getCacheStats(): String {
        return "PrecomputedShapes: ${precomputedCache.size()}/50"
    }
}