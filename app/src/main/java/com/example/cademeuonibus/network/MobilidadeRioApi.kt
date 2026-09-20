package com.example.cademeuonibus.network

import android.util.Log
import com.example.cademeuonibus.data.VeiculoApi
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private data class VeiculoApiRaw(
    @SerializedName("id_veiculo", alternate = ["codigo"]) val idVeiculo: String? = null,
    @SerializedName("servico", alternate = ["linha"])    val servico: String?    = null,
    @SerializedName("latitude")   val latRaw: JsonElement? = null,
    @SerializedName("longitude")  val lngRaw: JsonElement? = null,
    @SerializedName("velocidade") val velRaw: JsonElement? = null,
    @SerializedName("direcao")    val dirRaw: JsonElement? = null,
    @SerializedName("datetime")   val datetime: String?    = null,
    @SerializedName("dataHora")   val dataHoraLong: Long?  = null,
    @SerializedName("sentido")    val sentido: String?     = null,
    @SerializedName("datetime_servidor") val datetimeServidor: String? = null
) {
    fun getOrdem() = (idVeiculo ?: "").trim()
    fun getLinha() = (servico ?: "").trim()
    
    fun getLat(): Double = try {
        when {
            latRaw == null || latRaw.isJsonNull -> 0.0
            latRaw.isJsonPrimitive && latRaw.asJsonPrimitive.isNumber -> latRaw.asDouble
            latRaw.isJsonPrimitive && latRaw.asJsonPrimitive.isString -> 
                latRaw.asString.replace(",", ".").toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    } catch (e: Exception) { 0.0 }
    
    fun getLng(): Double = try {
        when {
            lngRaw == null || lngRaw.isJsonNull -> 0.0
            lngRaw.isJsonPrimitive && lngRaw.asJsonPrimitive.isNumber -> lngRaw.asDouble
            lngRaw.isJsonPrimitive && lngRaw.asJsonPrimitive.isString -> 
                lngRaw.asString.replace(",", ".").toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    } catch (e: Exception) { 0.0 }
    
    fun getVel(): Double = try {
        when {
            velRaw == null || velRaw.isJsonNull -> 0.0
            velRaw.isJsonPrimitive && velRaw.asJsonPrimitive.isNumber -> velRaw.asDouble
            velRaw.isJsonPrimitive && velRaw.asJsonPrimitive.isString -> 
                velRaw.asString.replace(",", ".").toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    } catch (e: Exception) { 0.0 }

    fun getDirecao(): Double? = try {
        when {
            dirRaw == null || dirRaw.isJsonNull -> null
            dirRaw.isJsonPrimitive && dirRaw.asJsonPrimitive.isNumber -> dirRaw.asDouble
            dirRaw.isJsonPrimitive && dirRaw.asJsonPrimitive.isString -> 
                dirRaw.asString.replace(",", ".").toDoubleOrNull()
            else -> null
        }
    } catch (e: Exception) { null }

    fun getTimestamp(): String {
        if (!datetime.isNullOrEmpty()) return datetime
        if (dataHoraLong != null && dataHoraLong > 0) {
            return java.time.Instant.ofEpochMilli(dataHoraLong)
                .atOffset(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }
        return ""
    }
}

object HttpClient {
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(loggingInterceptor)
        .build()
}

class MobilidadeRioService {
    private val gson = Gson()
    private val networkCache = NetworkCache()

    private sealed class ResultadoFonte {
        data class Sucesso(val veiculos: List<VeiculoApi>) : ResultadoFonte()
        data object Falha : ResultadoFonte()
    }

    suspend fun obterUltimaPosicao(linha: String): List<VeiculoApi>? =
        networkCache.getCachedResponse(linha, maxAgeMs = 60_000L)

    suspend fun buscarVeiculos(linha: String): List<VeiculoApi> =
        withContext(Dispatchers.IO) {
            val agoraUtc = OffsetDateTime.now(ZoneOffset.UTC)
            val inicioUtc = agoraUtc.minusMinutes(5)
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            val dataInicial = inicioUtc.format(fmt)
            val dataFinal = agoraUtc.format(fmt)

            val cliente = HttpClient.okHttp.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            suspend fun consultarFonte(url: String): ResultadoFonte {
                return try {
                    val request = Request.Builder().url(url).header("Accept", "application/json").build()
                    cliente.newCall(request).execute().use { resposta ->
                        if (resposta.isSuccessful) {
                            val body = resposta.body
                            if (body != null) {
                                return ResultadoFonte.Sucesso(parsearRespostaStreaming(body.charStream(), linha))
                            }
                        }
                    }
                    ResultadoFonte.Falha
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ResultadoFonte.Falha
                }
            }

            val isBrt = linha.all { it.isDigit() } && linha.length <= 2
            val urlConecta = "https://dados.mobilidade.rio/sppo/conecta/gps?servico=$linha&dataInicial=$dataInicial&dataFinal=$dataFinal"
            val urlBrt = "https://dados.mobilidade.rio/gps/brt?servico=$linha&dataInicial=$dataInicial&dataFinal=$dataFinal"

            val url = if (isBrt) urlBrt else urlConecta
            when (val resultado = consultarFonte(url)) {
                is ResultadoFonte.Sucesso -> {
                    networkCache.cacheResponse(linha, resultado.veiculos)
                    resultado.veiculos
                }
                ResultadoFonte.Falha -> emptyList()
            }
        }

    private fun parsearRespostaStreaming(reader: java.io.Reader, linha: String): List<VeiculoApi> {
        val lista = mutableListOf<VeiculoApi>()
        val linhaNormalizada = linha.trim().uppercase().removePrefix("0")
        try {
            val jsonReader = JsonReader(reader)
            jsonReader.isLenient = true
            if (jsonReader.peek() == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                jsonReader.beginArray()
                while (jsonReader.hasNext()) {
                    adicionarSeDaLinha(lista, linhaNormalizada, gson.fromJson(jsonReader, VeiculoApiRaw::class.java))
                }
                jsonReader.endArray()
            } else if (jsonReader.peek() == com.google.gson.stream.JsonToken.BEGIN_OBJECT) {
                jsonReader.beginObject()
                while (jsonReader.hasNext()) {
                    if (jsonReader.nextName() == "veiculos") {
                        if (jsonReader.peek() == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                            jsonReader.beginArray()
                            while (jsonReader.hasNext()) {
                                adicionarSeDaLinha(lista, linhaNormalizada, gson.fromJson(jsonReader, VeiculoApiRaw::class.java))
                            }
                            jsonReader.endArray()
                        } else jsonReader.skipValue()
                    } else jsonReader.skipValue()
                }
                jsonReader.endObject()
            }
            jsonReader.close()
        } catch (e: Exception) { }
        return lista
    }

    private fun adicionarSeDaLinha(resultado: MutableList<VeiculoApi>, linhaNormalizada: String, veiculo: VeiculoApiRaw) {
        val linhaVeiculo = veiculo.getLinha().uppercase().trim().removePrefix("0")
        if (linhaVeiculo != linhaNormalizada) return

        val ordem = veiculo.getOrdem()
        val latitude = veiculo.getLat()
        val longitude = veiculo.getLng()
        if (ordem.isNotEmpty() && latitude != 0.0 && longitude != 0.0) {
            resultado.add(
                VeiculoApi(
                    ordem = ordem,
                    linha = veiculo.getLinha(),
                    latitude = latitude,
                    longitude = longitude,
                    velocidade = veiculo.getVel(),
                    dataHora = veiculo.getTimestamp(),
                    sentido = veiculo.sentido,
                    bearing = veiculo.getDirecao(),
                    datetimeServidor = veiculo.datetimeServidor
                )
            )
        }
    }
}
