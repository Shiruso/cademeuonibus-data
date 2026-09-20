package com.example.cademeuonibus.util

import android.util.Log
import com.example.cademeuonibus.data.DadosLinha
import com.example.cademeuonibus.data.DirecaoInferida
import com.example.cademeuonibus.data.ShapePoint
import kotlin.math.*

/**
 * DirecaoInferidor
 *
 * Decide em qual direção (ida/volta) e em que ponto da rota o veículo está,
 * usando múltiplos critérios em cascata:
 *
 *  1. Heading vetorial — se o veículo se moveu o suficiente entre duas posições,
 *     compara o ângulo de deslocamento com o ângulo local do shape.
 *
 *  2. Distância mínima — fallback quando não há posição anterior disponível
 *     ou o deslocamento é muito pequeno para calcular heading.
 *  
 *  3. Validação de direção — verifica se o ônibus está indo na direção correta
 *     da rota (não está indo no sentido contrário).
 */
object DirecaoInferidor {

    // Limite mínimo de deslocamento para usar heading (≈ 20 m)
    private const val LIMIAR_DESLOCAMENTO_M = 20.0

    // Penalidade de distância no score de heading
    private const val PESO_DISTANCIA = 5000.0
    
    // Ângulos máximos de divergência permitidos (em graus)
    // Sistema de 3 níveis baseado na curvatura do trecho
    private const val ANGULO_MAXIMO_RETO = 90.0        // Trecho reto: 90° (muito rigoroso)
    private const val ANGULO_MAXIMO_CURVO = 120.0      // Trecho com curvas: 120°
    private const val ANGULO_MAXIMO_SINUOSO = 140.0    // Rotatória/zigue-zague: 140°
    
    // Thresholds de curvatura para classificar o trecho
    private const val CURVATURA_CURVA_THRESHOLD = 30.0       // > 30° = curvo
    private const val CURVATURA_SINUOSO_THRESHOLD = 60.0     // > 60° = muito sinuoso

    // -----------------------------------------------------------------------
    // API pública
    // -----------------------------------------------------------------------

    /**
     * Infere a direção e o progresso do veículo na rota.
     *
     * @param dadosLinha     Dados GTFS da linha (shapes das duas direções).
     * @param lat            Latitude atual do veículo.
     * @param lng            Longitude atual do veículo.
     * @param latAnterior    Latitude anterior (null se não disponível).
     * @param lngAnterior    Longitude anterior (null se não disponível).
     * @return [DirecaoInferida] com direction_id, headsign e progresso (0–1).
     */
    fun inferir(
        dadosLinha: DadosLinha,
        lat: Double,
        lng: Double,
        latAnterior: Double? = null,
        lngAnterior: Double? = null
    ): DirecaoInferida {
        val candidatos = dadosLinha.direcoes.values.mapNotNull { info ->
            val shape = info.shape
            if (shape.isEmpty()) return@mapNotNull null

            val (idx, distSq) = indiceMaisProximo(shape, lat, lng)
            
            // Calcula distância real em metros para o ponto mais próximo encontrado
            val distM = distanciaHaversineKm(lat, lng, shape[idx].lat, shape[idx].lng) * 1000.0

            Candidato(
                directionId = info.directionId,
                headsign    = info.headsign,
                progresso   = idx.toDouble() / max(1, shape.size - 1),
                distSq      = distSq,
                distMetros  = distM,
                idx         = idx,
                shape       = shape
            )
        }

        if (candidatos.isEmpty()) {
            return DirecaoInferida(-1, "Desconhecido", 0.0, Double.MAX_VALUE)
        }

        // Tentar usar heading vetorial se houver posição anterior válida
        if (latAnterior != null && lngAnterior != null) {
            // Calcula deslocamento em metros (mais preciso que lat/lng²)
            val deslocamentoM = distanciaHaversineKm(
                latAnterior, lngAnterior,
                lat, lng
            ) * 1000.0

            if (deslocamentoM >= LIMIAR_DESLOCAMENTO_M) {
                val dxVeiculo = lng - lngAnterior
                val dyVeiculo = lat - latAnterior
                val deslocSq  = dxVeiculo * dxVeiculo + dyVeiculo * dyVeiculo
                val magV = sqrt(deslocSq)
                
                // Calcula ângulo de movimento do veículo
                val anguloVeiculo = Math.toDegrees(atan2(dyVeiculo, dxVeiculo))
                
                Log.d("DirecaoInferidor", "Veículo deslocou ${"%.1f".format(deslocamentoM)}m, " +
                    "ângulo=${"%.1f".format(anguloVeiculo)}°")
                
                val melhor = candidatos.maxByOrNull { c ->
                    val score = scoreHeading(c, dxVeiculo, dyVeiculo, magV)
                    
                    // Validação adicional: verifica se está indo na direção da rota
                    val anguloRota = calcularAnguloRota(c)
                    val divergencia = calcularDivergenciaAngular(anguloVeiculo, anguloRota)
                    
                    // Calcula curvatura do trecho
                    val curvatura = calcularCurvaturaRota(c)
                    
                    // Limiar adaptativo baseado na curvatura (3 níveis)
                    val limiarDivergencia = when {
                        curvatura > CURVATURA_SINUOSO_THRESHOLD -> ANGULO_MAXIMO_SINUOSO  // > 60°: rotatória/zigue-zague
                        curvatura > CURVATURA_CURVA_THRESHOLD -> ANGULO_MAXIMO_CURVO      // > 30°: curvas normais
                        else -> ANGULO_MAXIMO_RETO                                         // ≤ 30°: trecho reto
                    }
                    
                    val classificacao = when {
                        curvatura > CURVATURA_SINUOSO_THRESHOLD -> "SINUOSO"
                        curvatura > CURVATURA_CURVA_THRESHOLD -> "CURVO"
                        else -> "RETO"
                    }
                    
                    // Log para debug
                    Log.d("DirecaoInferidor", 
                        "  Dir ${c.directionId} (${c.headsign}): " +
                        "score=${"%.3f".format(score)}, " +
                        "ângulo_rota=${"%.1f".format(anguloRota)}°, " +
                        "divergência=${"%.1f".format(divergencia)}°, " +
                        "curvatura=${"%.1f".format(curvatura)}° [$classificacao], " +
                        "limiar=${"%.1f".format(limiarDivergencia)}°, " +
                        "dist=${"%.0f".format(c.distMetros)}m")
                    
                    // Se divergência > limiar adaptativo, penaliza fortemente
                    if (divergencia > limiarDivergencia) {
                        Log.d("DirecaoInferidor", "    ⚠️ Divergência excedida! Penalizando...")
                        score - 10000.0 // Penalidade muito severa
                    } else {
                        score
                    }
                }
                
                if (melhor != null) {
                    // Valida se a melhor opção não está indo ao contrário
                    val anguloRota = calcularAnguloRota(melhor)
                    val divergencia = calcularDivergenciaAngular(anguloVeiculo, anguloRota)
                    val curvatura = calcularCurvaturaRota(melhor)
                    
                    // Limiar adaptativo (3 níveis)
                    val limiarDivergencia = when {
                        curvatura > CURVATURA_SINUOSO_THRESHOLD -> ANGULO_MAXIMO_SINUOSO
                        curvatura > CURVATURA_CURVA_THRESHOLD -> ANGULO_MAXIMO_CURVO
                        else -> ANGULO_MAXIMO_RETO
                    }
                    
                    if (divergencia <= limiarDivergencia) {
                        Log.d("DirecaoInferidor", "✓ Direção ACEITA (divergência OK)")
                        return DirecaoInferida(
                            melhor.directionId, 
                            melhor.headsign, 
                            melhor.progresso, 
                            melhor.distMetros
                        )
                    } else {
                        Log.d("DirecaoInferidor", 
                            "✗ Direção REJEITADA: divergência ${"%.1f".format(divergencia)}° > " +
                            "limiar ${"%.1f".format(limiarDivergencia)}° (curvatura=${"%.1f".format(curvatura)}°)")
                        return DirecaoInferida(-1, "Sentido Incorreto", melhor.progresso, melhor.distMetros)
                    }
                }
            } else {
                Log.d("DirecaoInferidor", "Deslocamento insuficiente (${"%.1f".format(deslocamentoM)}m < ${LIMIAR_DESLOCAMENTO_M}m)")
            }
        }

        // Fallback: menor distância ao shape
        val melhor = candidatos.minByOrNull { it.distSq }!!
        return DirecaoInferida(melhor.directionId, melhor.headsign, melhor.progresso, melhor.distMetros)
    }

    /**
     * Calcula o ângulo da rota no ponto onde o veículo está.
     * Usa um segmento à frente para determinar a direção esperada.
     */
    private fun calcularAnguloRota(c: Candidato): Double {
        val shape = c.shape
        val idx = c.idx
        
        // Pega ponto à frente (segmento de ~50m ou 10 pontos à frente)
        val idxFrente = min(shape.size - 1, idx + 10)
        
        if (idxFrente == idx) return 0.0
        
        val dxRota = shape[idxFrente].lng - shape[idx].lng
        val dyRota = shape[idxFrente].lat - shape[idx].lat
        
        return Math.toDegrees(atan2(dyRota, dxRota))
    }
    
    /**
     * Calcula o índice de curvatura do trecho à frente.
     * Retorna a variação angular acumulada nos próximos pontos.
     * 
     * Alta curvatura = rotatória, zigue-zague, curvas acentuadas
     * Baixa curvatura = trecho reto
     * 
     * Retorna valor médio para evitar picos pontuais
     */
    fun calcularCurvaturaRota(pontos: List<ShapePoint>, idxInicial: Int): Double {
        // Analisa os próximos 15 pontos (~75-100m dependendo da densidade)
        val idxFinal = min(pontos.size - 1, idxInicial + 15)
        
        if (idxFinal - idxInicial < 3) return 0.0 // Trecho muito curto
        
        val mudancas = mutableListOf<Double>()
        
        // Calcula mudança de direção entre segmentos consecutivos
        for (i in idxInicial until idxFinal - 2) {
            val anguloSegmento1 = Math.toDegrees(atan2(
                pontos[i + 1].lat - pontos[i].lat,
                pontos[i + 1].lng - pontos[i].lng
            ))
            
            val anguloSegmento2 = Math.toDegrees(atan2(
                pontos[i + 2].lat - pontos[i + 1].lat,
                pontos[i + 2].lng - pontos[i + 1].lng
            ))
            
            val mudanca = calcularDivergenciaAngular(anguloSegmento1, anguloSegmento2)
            mudancas.add(mudanca)
        }
        
        // Retorna a média das 3 maiores mudanças angulares
        // (ignora pequenas oscilações, foca nas curvas reais)
        return if (mudancas.size >= 3) {
            mudancas.sortedDescending().take(3).average()
        } else {
            mudancas.average()
        }
    }
    
    private fun calcularCurvaturaRota(c: Candidato): Double {
        return calcularCurvaturaRota(c.shape, c.idx)
    }
    
    /**
     * Calcula a divergência angular entre dois ângulos (em graus).
     * Retorna o menor ângulo entre eles (0° a 180°).
     */
    private fun calcularDivergenciaAngular(angulo1: Double, angulo2: Double): Double {
        var diff = abs(angulo1 - angulo2)
        
        // Normaliza para 0-180°
        if (diff > 180.0) {
            diff = 360.0 - diff
        }
        
        return diff
    }

    /**
     * Calcula a distância em km entre dois pontos usando a fórmula de Haversine.
     * Equivale a _dist_haversine() do Python.
     */
    fun distanciaHaversineKm(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double
    ): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)
        return r * 2 * asin(sqrt(a))
    }

    // -----------------------------------------------------------------------
    // Lógica interna
    // -----------------------------------------------------------------------

    /**
     * Encontra o índice do ponto do shape mais próximo ao veículo.
     * Usa busca em dois passos (grosseiro → refinado) igual ao Python.
     *
     * @return Par (índice, distância²)
     */
    private fun indiceMaisProximo(
        shape: List<ShapePoint>,
        lat: Double,
        lng: Double
    ): Pair<Int, Double> {
        val n = shape.size
        if (n == 0) return 0 to Double.MAX_VALUE

        val passo = max(1, n / 200)
        var melhorIdx = 0
        var melhorD   = Double.MAX_VALUE

        // Varredura grosseira
        var i = 0
        while (i < n) {
            val d = distSq(lat, lng, shape[i].lat, shape[i].lng)
            if (d < melhorD) { melhorD = d; melhorIdx = i }
            i += passo
        }

        // Refinamento na vizinhança
        val inicio = max(0, melhorIdx - passo)
        val fim    = min(n, melhorIdx + passo + 1)
        for (j in inicio until fim) {
            val d = distSq(lat, lng, shape[j].lat, shape[j].lng)
            if (d < melhorD) { melhorD = d; melhorIdx = j }
        }

        return melhorIdx to melhorD
    }

    /**
     * Score de alinhamento de heading para escolher a melhor direção.
     * Combina cosseno do ângulo entre vetor do veículo e vetor local do shape,
     * penalizado pela distância ao shape.
     */
    private fun scoreHeading(
        c: Candidato,
        dxVeiculo: Double,
        dyVeiculo: Double,
        magV: Double
    ): Double {
        val shape    = c.shape
        val idxAntes  = max(0, c.idx - 3)
        val idxDepois = min(shape.size - 1, c.idx + 3)

        val dxShape = shape[idxDepois].lng - shape[idxAntes].lng
        val dyShape = shape[idxDepois].lat - shape[idxAntes].lat
        val magS    = sqrt(dxShape * dxShape + dyShape * dyShape)

        val cosseno = if (magS > 0)
            (dxVeiculo * dxShape + dyVeiculo * dyShape) / (magV * magS)
        else 0.0

        return cosseno - c.distSq * PESO_DISTANCIA
    }

    /**
     * Distância euclidiana ao quadrado, com fator de correção de longitude
     * para baixas latitudes (≈ cos(22,9°) ≈ 0,9205 para o Rio de Janeiro).
     * Equivale a _dist_sq() do Python.
     */
    private fun distSq(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double
    ): Double {
        val dLat = lat2 - lat1
        val dLng = (lng2 - lng1) * 0.9205
        return dLat * dLat + dLng * dLng
    }

    // -----------------------------------------------------------------------
    // Tipo interno
    // -----------------------------------------------------------------------

    private data class Candidato(
        val directionId: Int,
        val headsign: String,
        val progresso: Double,
        val distSq: Double,
        val distMetros: Double,
        val idx: Int,
        val shape: List<ShapePoint>
    )
}
