package com.example.cademeuonibus.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.*
import androidx.lifecycle.viewModelScope
import com.example.cademeuonibus.data.*
import com.example.cademeuonibus.network.MobilidadeRioService
import com.example.cademeuonibus.util.DirecaoInferidor
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val gtfsParser = GtfsParser(application)
    private val apiService = MobilidadeRioService()
    private val prefs = application.getSharedPreferences("linhas_monitoradas", Context.MODE_PRIVATE)
    private val linhas = linkedMapOf<String, LinhaMonitorada>()
    private val dadosPorLinha = mutableMapOf<String, DadosLinha>()
    private val posAnterior = mutableMapOf<String, MutableMap<String, Pair<Double, Double>>>()
    private val pollingJobs = mutableMapOf<String, Job>()
    private val ultimosVeiculos = mutableMapOf<String, List<VeiculoProcessado>>()
    private val fmtHora = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val _buscaState = MutableLiveData<BuscaState>(BuscaState.Idle)
    val buscaState: LiveData<BuscaState> = _buscaState
    private val _linhasMonitoradas = MutableLiveData<List<LinhaMonitorada>>()
    val linhasMonitoradas: LiveData<List<LinhaMonitorada>> = _linhasMonitoradas
    private val _localizacaoState = MutableLiveData<LocalizacaoState>(LocalizacaoState.Aguardando)
    val localizacaoState: LiveData<LocalizacaoState> = _localizacaoState
    private val _historico = MutableLiveData<List<String>>()
    val historico: LiveData<List<String>> = _historico
    private val _sugestoes = MutableLiveData<List<String>>()
    val sugestoes: LiveData<List<String>> = _sugestoes
    private var sugestaoJob: Job? = null
    private var versaoSugestao = 0

    init { carregarHistorico(); restaurarLinhas() }
    fun carregarHistorico() { viewModelScope.launch { _historico.value = gtfsParser.obterHistoricoRecente() } }
    fun atualizarQuerySugestao(query: String) { sugestaoJob?.cancel(); val v = ++versaoSugestao; sugestaoJob = viewModelScope.launch { delay(300); val r = if (query.isBlank()) emptyList() else gtfsParser.buscarSugestoes(query); if (v == versaoSugestao) _sugestoes.value = r } }
    fun getDadosLinha(linha: String) = dadosPorLinha[linha]
    fun getLinhaMonitorada(linha: String) = linhas[linha]
    fun getLinhasComDados() = linhas.values.mapNotNull { l -> dadosPorLinha[l.linha]?.let { l to it } }
    fun getUltimosVeiculos() = ultimosVeiculos.toMap()
    
    // Método auxiliar para obter o Headsign salvo na memória do GTFS para a UI
    fun obterHeadsignDaDirecao(linha: String, directionId: Int): String? {
        return dadosPorLinha[linha]?.direcoes?.get(directionId)?.headsign
    }

    fun marcarComoFavoritaExistente(linha: String) {
        val existente = linhas[linha]
        if (linhas.values.count { it.salva } >= 5 && existente?.salva != true) {
            _buscaState.value = BuscaState.Erro("Você pode ter no máximo 5 linhas favoritas.")
            return
        }
        if (existente != null) {
            linhas[linha] = existente.copy(salva = true)
        } else {
            linhas[linha] = LinhaMonitorada(linha, salva = true)
        }
        publicarLinhas(); salvarLinhas()
    }

    fun buscarLinha(texto: String, favoritar: Boolean = false) {
        val partes = texto.split(" - "); val numero = partes.first().trim().uppercase()
        val filtro = partes.drop(1).firstOrNull()?.takeIf { !it.contains("Todos", true) }?.trim()
        if (numero.isBlank()) return
        
        if (favoritar) {
            if (!linhas.containsKey(numero) && linhas.values.count { it.salva } >= 5) {
                _buscaState.value = BuscaState.Erro("Você pode ter no máximo 5 linhas favoritas.")
                return
            }
            linhas[numero] = LinhaMonitorada(numero, filtro, salva = true)
        } else {
            // COMPORTAMENTO CORRIGIDO: Remove qualquer outra busca temporária anterior do mapa
            // para que apenas as linhas marcadas propositalmente como FAVORITAS permaneçam na tela.
            val linhasParaRemover = linhas.filterValues { !it.salva && it.linha != numero }.keys
            linhasParaRemover.forEach { linhaAntiga ->
                linhas.remove(linhaAntiga)
                dadosPorLinha.remove(linhaAntiga)
                posAnterior.remove(linhaAntiga)
                ultimosVeiculos.remove(linhaAntiga)
                pollingJobs.remove(linhaAntiga)?.cancel()
            }

            val existente = linhas[numero]
            if (existente == null) {
                linhas[numero] = LinhaMonitorada(numero, filtro, salva = false)
            }
        }
        
        publicarLinhas(); salvarLinhas()
        viewModelScope.launch { gtfsParser.salvarNoHistorico(numero); carregarHistorico(); carregarLinha(numero) }
    }
    fun alternarDirecao(linha: String, ida: Boolean) { val l = linhas[linha] ?: return; linhas[linha] = if (ida) l.copy(exibirIda = !l.exibirIda) else l.copy(exibirVolta = !l.exibirVolta); publicarLinhas(); salvarLinhas() }
    fun removerLinha(linha: String) { linhas.remove(linha); dadosPorLinha.remove(linha); posAnterior.remove(linha); ultimosVeiculos.remove(linha); pollingJobs.remove(linha)?.cancel(); publicarLinhas(); salvarLinhas() }

    private suspend fun carregarLinha(linha: String) {
        _buscaState.postValue(BuscaState.Buscando)
        try {
            gtfsParser.dadosLinha(linha)?.let { bruto ->
                val filtro = linhas[linha]?.headsignFiltro
                dadosPorLinha[linha] = if (filtro != null) bruto.copy(direcoes = bruto.direcoes.filterValues { it.headsign == filtro }.ifEmpty { bruto.direcoes }) else bruto
                
                // NOTIFICAÇÃO ATÔMICA: Força a atualização da lista para que a UI veja os nomes dos destinos
                publicarLinhas()
                
                _buscaState.postValue(BuscaState.Sucesso(emptyList(), linha, "--:--:--", true, true))
            }
            apiService.obterUltimaPosicao(linha)?.let { processar(linha, it, false, true) }
            processar(linha, apiService.buscarVeiculos(linha), true); iniciarPolling(linha)
        } catch (e: Exception) { Log.e("MainViewModel", "Erro ao buscar $linha", e); _buscaState.postValue(BuscaState.Erro("Erro ao buscar a linha $linha.")) }
    }
    private suspend fun processar(linha: String, pontos: List<VeiculoApi>, redesenhar: Boolean, aguardando: Boolean = false) {
        val dados = dadosPorLinha[linha]; val anteriores = posAnterior.getOrPut(linha) { mutableMapOf() }; val filtro = linhas[linha]?.headsignFiltro
        val veiculos = withContext(Dispatchers.Default) { pontos.groupBy { it.ordem }.mapNotNull { (ordem, grupo) ->
            val v = grupo.maxByOrNull { it.dataHora } ?: return@mapNotNull null; val ant = anteriores[ordem]
            val atraso = try { Duration.between(OffsetDateTime.parse(v.dataHora).toInstant(), v.datetimeServidor?.let { OffsetDateTime.parse(it).toInstant() } ?: Instant.now()).seconds } catch (_: Exception) { null }
            val d = dados?.let { DirecaoInferidor.inferir(it, v.latitude, v.longitude, ant?.first, ant?.second).copy(bearing = v.bearing, delaySegundos = atraso) } ?: DirecaoInferida(0, "Desconhecido", 0.0, 0.0, v.bearing, atraso)
            if (d.distanciaMetros > 1000 || (filtro != null && d.headsign != filtro)) return@mapNotNull null
            anteriores[ordem] = v.latitude to v.longitude; VeiculoProcessado(ordem, v.latitude, v.longitude, v.velocidade, d, v.bearing)
        } }
        ultimosVeiculos[linha] = veiculos
        _buscaState.postValue(BuscaState.Sucesso(veiculos, linha, LocalTime.now().format(fmtHora), redesenhar, aguardando))
    }
    private fun iniciarPolling(linha: String) { if (pollingJobs[linha]?.isActive == true) return; pollingJobs[linha] = viewModelScope.launch { while (isActive && linhas.containsKey(linha)) { delay(10_000); processar(linha, apiService.buscarVeiculos(linha), false) } } }
    private fun publicarLinhas() { _linhasMonitoradas.postValue(linhas.values.toList()) }
    private fun salvarLinhas() { prefs.edit().putString("itens", JSONArray().apply { linhas.values.filter { it.salva }.forEach { put(JSONObject().put("linha", it.linha).put("headsign", it.headsignFiltro).put("ida", it.exibirIda).put("volta", it.exibirVolta)) } }.toString()).apply() }
    private fun restaurarLinhas() { try { val a=JSONArray(prefs.getString("itens", "[]")); for(i in 0 until a.length()) { val o=a.getJSONObject(i); val l=LinhaMonitorada(o.getString("linha"), o.optString("headsign").ifBlank { null }, o.optBoolean("ida",true), o.optBoolean("volta",true), salva = true); linhas[l.linha]=l; viewModelScope.launch { carregarLinha(l.linha) } } } catch (_: Exception) {}; publicarLinhas() }
    fun atualizarLocalizacao(lat: Double, lng: Double) { _localizacaoState.value = LocalizacaoState.Disponivel(lat, lng) }
    override fun onCleared() { pollingJobs.values.forEach { it.cancel() }; sugestaoJob?.cancel() }
}
