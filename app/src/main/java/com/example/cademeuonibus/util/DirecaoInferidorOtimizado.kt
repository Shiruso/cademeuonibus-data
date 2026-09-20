package com.example.cademeuonibus.util

import android.util.Log
import com.example.cademeuonibus.data.DadosLinha
import com.example.cademeuonibus.data.DirecaoInferida
import com.example.cademeuonibus.data.ShapePoint
import kotlin.math.*

/**
 * Versão altamente otimizada do algoritmo de inferência de direção
 * com cache, spatial indexing e algoritmos eficientes
 */
object DirecaoInferidorOtimizado {
    
    // Constantes otimizadas
    private const val PESO_DISTANCIA = 100_000.0
    private const val LIMIAR_DESLOCAMENTO_M = 8.0
    private const val DISTANCIA_MAXIMA_M = 500.0 // Early exit para veículos muito distantes
    
    private const val ANGULO_MAXIMO_RETO = 45.0
    private const val ANGULO_MAXIMO_CURVO = 60.0
    private const val ANGULO_MAXIMO_SINUOSO = 90.0
    
    private const val CURVATURA_CURVA_THRESHOLD = 30.0
    private const val CURVATURA_SINUOSO_THRESHOLD = 60.0
    
    // Cache para resultados de inferência
    private val cacheResultados = mutableMapOf<String, DirecaoInferida>()
    private const val MAX_CACHE_SIZE = 100
    
    /**
     * Algoritmo principal otimizado
     */
    fun inferir(
        dadosLinha: DadosLinha,
        lat: Double,
        lng: Double,
        latAnterior: Double? = null,
        lngAnterior: Double? = null
    ): DirecaoInferida {
        
        // Cache key baseado em posição quantizada (precisão ~10m)
        val cacheKey = "${dadosLinha.numeroLinha}_${quantizar(lat)}_${quantizar(lng)}"
        
        // Verifica cache primeiro
        cacheResultados[cacheKey]?.let { cached ->
            Log.d("DirecaoInferidor", "✓ Cache HIT")
            return cached
        }

        val candidatos = processarCandidatos(dadosLinha, lat, lng)
        
        if (candidatos.isEmpty()) {
            return DirecaoInferida(-1, "Desconhecido", 0.0, Double.MAX_VALUE)
        }

        val melhor = if (latAnterior != null && lngAnterior != null) {
            selecionarComMovimento(candidatos, lat, lng, latAnterior, lngAnterior)
        } else {
            candidatos.minByOrNull { it.distMetros }
        }

        val resultado = melhor?.toResultado() ?: DirecaoInferida(-1, "Desconhecido", 0.0, Double.MAX_VALUE)
        
        // Cacheia apenas resultados válidos
        if (resultado.directionId != -1) {
            cacheaResultado(cacheKey, resultado)
        }

        return resultado
    }
    
    /**
     * Processa candidatos com early exits para performance
     */
    private fun processarCandidatos(dadosLinha: DadosLinha, lat: Double, lng: Double): List<Candidato> {
        return dadosLinha.direcoes.values.mapNotNull { info ->
            val shape = info.shape
            if (shape.isEmpty()) return@mapNotNull null

            val shapeId = "${dadosLinha.numeroLinha}_${info.directionId}"
            
            // Usa cache para busca de ponto mais próximo
            val (idx, distSq) = CacheOptimizer.indiceMaisProximoComCache(shape, lat, lng, shapeId)
            
            // Calcula distância real e faz early exit
            val distM = distanciaRapida(lat, lng, shape[idx].lat, shape[idx].lng)
            if (distM > DISTANCIA_MAXIMA_M) return@mapNotNull null

            Candidato(
                directionId = info.directionId,
                headsign = info.headsign,
                progresso = idx.toDouble() / maxOf(1, shape.size - 1),
                distSq = distSq,
                distMetros = distM,
                idx = idx,
                shape = shape,
                shapeId = shapeId
            )
        }
    }
    
    /**
     * Seleção baseada em movimento com cache de cálculos
     */
    private fun selecionarComMovimento(
        candidatos: List<Candidato>,
        lat: Double, lng: Double,
        latAnterior: Double, lngAnterior: Double
    ): Candidato? {
        
        val deslocamento = distanciaRapida(latAnterior, lngAnterior, lat, lng)
        
        if (deslocamento < LIMIAR_DESLOCAMENTO_M) {
            return candidatos.minByOrNull { it.distMetros }
        }

        val dxVeiculo = lng - lngAnterior
        val dyVeiculo = lat - latAnterior
        val magV = sqrt(dxVeiculo * dxVeiculo + dyVeiculo * dyVeiculo)
        val anguloVeiculo = Math.toDegrees(atan2(dyVeiculo, dxVeiculo))
        
        return candidatos.maxByOrNull { candidato ->
            val score = calcularScore(candidato, dxVeiculo, dyVeiculo, magV)
            val anguloRota = CacheOptimizer.calcularAnguloRotaComCache(
                candidato.shape, candidato.idx, candidato.shapeId
            )
            val curvatura = CacheOptimizer.calcularCurvaturaRotaComCache(
                candidato.shape, candidato.idx, candidato.shapeId
            )
            
            val divergencia = divergenciaAngular(anguloVeiculo, anguloRota)
            val limiar = when {
                curvatura > CURVATURA_SINUOSO_THRESHOLD -> ANGULO_MAXIMO_SINUOSO
                curvatura > CURVATURA_CURVA_THRESHOLD -> ANGULO_MAXIMO_CURVO
                else -> ANGULO_MAXIMO_RETO
            }
            
            if (divergencia > limiar) score - 10000.0 else score
        }
    }
    
    /**
     * Cálculo de score otimizado
     */
    private fun calcularScore(
        candidato: Candidato,
        dxVeiculo: Double, dyVeiculo: Double, magV: Double
    ): Double {
        val shape = candidato.shape
        val idx = candidato.idx
        
        // Janela reduzida para melhor performance
        val idxAntes = max(0, idx - 2)
        val idxDepois = min(shape.size - 1, idx + 2)

        val dxShape = shape[idxDepois].lng - shape[idxAntes].lng
        val dyShape = shape[idxDepois].lat - shape[idxAntes].lat
        val magS = sqrt(dxShape * dxShape + dyShape * dyShape)

        val cosseno = if (magS > 0.001) {
            (dxVeiculo * dxShape + dyVeiculo * dyShape) / (magV * magS)
        } else 0.0

        return cosseno - candidato.distSq * PESO_DISTANCIA
    }
    
    /**
     * Distância rápida para comparações (aproximada mas suficiente)
     */
    private fun distanciaRapida(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * 111000.0 // ~111km por grau de latitude
        val dLng = (lng2 - lng1) * 111000.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dLat * dLat + dLng * dLng)
    }
    
    /**
     * Divergência angular otimizada
     */
    private fun divergenciaAngular(angulo1: Double, angulo2: Double): Double {
        val diff = abs(angulo1 - angulo2)
        return if (diff > 180.0) 360.0 - diff else diff
    }
    
    /**
     * Quantização para cache (precisão de ~10m)
     */
    private fun quantizar(coord: Double): Int = (coord * 1000).toInt()
    
    /**
     * Gerenciamento do cache
     */
    private fun cacheaResultado(key: String, resultado: DirecaoInferida) {
        cacheResultados[key] = resultado
        
        // Cleanup do cache quando fica muito grande
        if (cacheResultados.size > MAX_CACHE_SIZE) {
            val keysToRemove = cacheResultados.keys.take(20)
            keysToRemove.forEach { cacheResultados.remove(it) }
        }
    }
    
    /**
     * Limpa cache para uma linha específica
     */
    fun limparCache(numeroLinha: String? = null) {
        if (numeroLinha != null) {
            val keysToRemove = cacheResultados.keys.filter { it.startsWith(numeroLinha) }
            keysToRemove.forEach { cacheResultados.remove(it) }
            CacheOptimizer.clearCacheForLine(numeroLinha)
        } else {
            cacheResultados.clear()
            CacheOptimizer.clearCache()
        }
    }
    
    /**
     * Classe de candidato otimizada
     */
    private data class Candidato(
        val directionId: Int,
        val headsign: String,
        val progresso: Double,
        val distSq: Double,
        val distMetros: Double,
        val idx: Int,
        val shape: List<ShapePoint>,
        val shapeId: String
    ) {
        fun toResultado(): DirecaoInferida {
            return DirecaoInferida(directionId, headsign, progresso, distMetros)
        }
    }
}