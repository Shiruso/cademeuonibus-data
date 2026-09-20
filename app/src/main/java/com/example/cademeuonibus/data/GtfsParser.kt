package com.example.cademeuonibus.data

import android.content.Context
import android.util.Log
import com.example.cademeuonibus.data.db.*
import com.example.cademeuonibus.util.ShapePrecomputer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GtfsParser
 *
 * Agora focado exclusivamente em consultas ao banco de dados SQLite (gtfs_rio.db).
 * Toda a lógica de importação de arquivos TXT/CSV foi removida para manter o app leve.
 */
class GtfsParser(private val context: Context) {

    private val gtfsDb = GtfsDatabase.getDatabase(context)
    private val gtfsDao = gtfsDb.gtfsDao()

    private val historyDb = HistoryDatabase.getDatabase(context)
    private val historyDao = historyDb.historyDao()
    
    private val MAX_CACHE_LINHAS = 20
    private val CACHE_EXPIRATION_MS = 30 * 60 * 1000L // 30 minutos

    private val cacheDadosLinha = LinkedHashMap<String, CacheEntry>(
        MAX_CACHE_LINHAS,
        0.75f,
        true
    )
    
    private data class CacheEntry(
        val dados: DadosLinha,
        val timestamp: Long = System.currentTimeMillis()
    )

    private fun formatarDestino(texto: String): String {
        return texto.replace("Terminal", "Rodoviária", ignoreCase = true)
    }

    suspend fun obterDestino(servico: String, direcaoId: Int): String? = withContext(Dispatchers.IO) {
        try {
            gtfsDao.getDestino(servico.trim().uppercase(), direcaoId)?.let { formatarDestino(it) }
        } catch (e: Exception) {
            Log.e("GtfsParser", "Erro ao obter destino", e)
            null
        }
    }

    suspend fun obterHistoricoRecente(): List<String> = withContext(Dispatchers.IO) {
        try {
            historyDao.getHistoricoRecente().map { it.linha }
        } catch (e: Exception) {
            Log.e("GtfsParser", "Erro ao obter histórico", e)
            emptyList()
        }
    }

    suspend fun salvarNoHistorico(linha: String) = withContext(Dispatchers.IO) {
        try {
            historyDao.insertHistorico(HistoricoEntity(linha.uppercase(), System.currentTimeMillis()))
        } catch (e: Exception) {
            Log.e("GtfsParser", "Erro ao salvar no histórico", e)
        }
    }

    suspend fun buscarSugestoes(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isEmpty()) return@withContext emptyList()
        
        try {
            val queryUpper = query.uppercase().trim()
            val sugestoes = gtfsDao.buscarSugestoesComSentido(queryUpper).map { formatarDestino(it) }.toMutableList()
            
            val linhasEncontradas = sugestoes.map { it.split(" - ")[0] }.distinct()
            if (linhasEncontradas.size == 1) {
                sugestoes.add(0, "${linhasEncontradas[0]} - Todos os sentidos")
            }
            
            sugestoes.distinct()
        } catch (e: Exception) {
            Log.e("GtfsParser", "Erro ao buscar sugestões", e)
            emptyList()
        }
    }

    suspend fun dadosLinha(numeroLinha: String): DadosLinha? =
        withContext(Dispatchers.IO) {
            val num = numeroLinha.trim().uppercase()
            val agora = System.currentTimeMillis()
            
            cacheDadosLinha[num]?.let { entry ->
                if (agora - entry.timestamp < CACHE_EXPIRATION_MS) {
                    return@withContext entry.dados
                }
            }
            
            val startTime = System.currentTimeMillis()

            // Query otimizada: busca todos os trips da linha em uma consulta
            val trips = gtfsDao.getOptimizedTripsByRoute(num)
            if (trips.isEmpty()) return@withContext null
            
            val tripPorDirecao = mutableMapOf<Int, TripEntity>()
            trips.forEach { trip ->
                if (!tripPorDirecao.containsKey(trip.directionId)) {
                    tripPorDirecao[trip.directionId] = trip
                }
            }

            val direcoes = mutableMapOf<Int, DirecaoInfo>()
            for ((did, trip) in tripPorDirecao) {
                // Remove o limite de pontos (0 = todos) para garantir a trajetória completa
                val shapePoints = gtfsDao.getShapePointsByShapeId(trip.shapeId, 0)
                val points = shapePoints.map { ShapePoint(it.lat, it.lon) }
                
                // Query otimizada: busca paradas com JOIN
                val paradas = gtfsDao.getStopsForTrip(trip.tripId)
                    .map { s -> Parada(s.stopId, s.stopName, s.stopLat, s.stopLon) }

                direcoes[did] = DirecaoInfo(did, formatarDestino(trip.tripHeadsign), points, paradas)
            }

            val resultado = DadosLinha(num, direcoes)
            cacheDadosLinha[num] = CacheEntry(resultado, agora)
            Log.d("GtfsParser", "✓ Linha $num carregada (otimizada) em ${System.currentTimeMillis() - startTime}ms")
            resultado
        }

    fun preCarregarEstrutura() {
        // Nada a fazer, o banco de dados é a única fonte agora
    }
}
