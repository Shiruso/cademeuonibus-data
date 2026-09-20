package com.example.cademeuonibus.network

import android.util.Log
import com.example.cademeuonibus.data.VeiculoApi
import com.google.transit.realtime.GtfsRealtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * GtfsRealtimeService
 *
 * Responsável por baixar e processar feeds GTFS-Realtime (.pb).
 * Suporta feeds de Veículos (Vehicle Positions).
 */
class GtfsRealtimeService {

    /**
     * Baixa o feed da [url] e filtra pela [linha].
     * @param url URL do feed .pb
     * @param linha Número da linha (short_name) para filtrar
     * @param apiKey Chave opcional do portal Data.rio
     */
    suspend fun buscarVeiculos(url: String, linha: String, apiKey: String? = null): List<VeiculoApi> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url)
                apiKey?.let { request.addHeader("apikey", it) }
                
                val response = HttpClient.okHttp.newCall(request.build()).execute()
                if (!response.isSuccessful) {
                    Log.e("GtfsRealtime", "Erro ao baixar feed: ${response.code}")
                    return@withContext emptyList()
                }

                val bytes = response.body?.bytes() ?: return@withContext emptyList()
                parsearFeed(bytes.inputStream(), linha)
            } catch (e: Exception) {
                Log.e("GtfsRealtime", "Erro ao processar GTFS-RT remoto: ${e.message}", e)
                emptyList()
            }
        }

    /**
     * Processa um feed GTFS-RT vindo de um InputStream (arquivo local ou rede)
     */
    fun parsearFeed(inputStream: InputStream, linha: String): List<VeiculoApi> {
        return try {
            val feed = GtfsRealtime.FeedMessage.parseFrom(inputStream)
            Log.d("GtfsRealtime", "Feed processado: ${feed.entityCount} entidades")

            val resultado = mutableListOf<VeiculoApi>()
            val linhaBusca = linha.trim().uppercase()

            for (entity in feed.entityList) {
                if (entity.hasVehicle()) {
                    val v = entity.vehicle
                    // No GTFS-RT do Rio, 'route_id' costuma ser o short_name (ex: "864")
                    // No BRT, pode ser apenas o número.
                    val routeId = v.trip.routeId.trim().uppercase()
                    
                    if (routeId == linhaBusca || routeId.removePrefix("0") == linhaBusca) {
                        val pos = v.position
                        resultado.add(
                            VeiculoApi(
                                ordem = v.vehicle.id ?: "N/A",
                                linha = routeId,
                                latitude = pos.latitude.toDouble(),
                                longitude = pos.longitude.toDouble(),
                                velocidade = pos.speed.toDouble() * 3.6, // m/s para km/h
                                dataHora = if (v.hasTimestamp()) Instant.ofEpochSecond(v.timestamp).toString() else Instant.now().toString(),
                                bearing = if (pos.hasBearing()) pos.bearing.toDouble() else null
                            )
                        )
                    }
                }
            }
            
            Log.d("GtfsRealtime", "Filtrados ${resultado.size} veículos para linha $linha")
            resultado
        } catch (e: Exception) {
            Log.e("GtfsRealtime", "Erro no parsing do arquivo .pb: ${e.message}")
            emptyList()
        }
    }
}
