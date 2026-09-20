package com.example.cademeuonibus.data

// ---------------------------------------------------------------------------
// GTFS — dados estáticos de rota carregados dos arquivos locais
// ---------------------------------------------------------------------------

data class Parada(
    val id: String,
    val nome: String,
    val lat: Double,
    val lng: Double
)

data class ShapePoint(val lat: Double, val lng: Double)

data class DirecaoInfo(
    val directionId: Int,
    val headsign: String,
    val shape: List<ShapePoint>,
    val paradas: List<Parada>
)

data class DadosLinha(
    val numeroLinha: String,
    val direcoes: Map<Int, DirecaoInfo>
)

// ---------------------------------------------------------------------------
// GPS em tempo real
// ---------------------------------------------------------------------------

data class VeiculoApi(
    val ordem: String,
    val linha: String,
    val latitude: Double,
    val longitude: Double,
    val velocidade: Double,
    val dataHora: String,
    val sentido: String? = null,
    val bearing: Double? = null,
    val datetimeServidor: String? = null
)

data class DirecaoInferida(
    val directionId: Int,
    val headsign: String,
    val progresso: Double,
    val distanciaMetros: Double,
    val bearing: Double? = null,
    val delaySegundos: Long? = null
)

data class VeiculoProcessado(
    val ordem: String,
    val lat: Double,
    val lng: Double,
    val velocidade: Double,
    val direcao: DirecaoInferida,
    val bearing: Double? = null
)

/** Uma linha acompanhada no mapa, incluindo as escolhas do usuário. */
data class LinhaMonitorada(
    val linha: String,
    val headsignFiltro: String? = null,
    val exibirIda: Boolean = true,
    val exibirVolta: Boolean = true,
    val salva: Boolean = false
)

// ---------------------------------------------------------------------------
// Estados de UI
// ---------------------------------------------------------------------------

sealed class BuscaState {
    object Idle : BuscaState()
    object CarregandoGtfs : BuscaState()
    object Buscando : BuscaState()
    data class Sucesso(
        val veiculos: List<VeiculoProcessado>,
        val linha: String,
        val timestamp: String,
        val reescreverMapa: Boolean,
        val aindaBuscandoGps: Boolean = false
    ) : BuscaState()
    data class Erro(val mensagem: String) : BuscaState()
    data class AvisoGtfs(val mensagem: String) : BuscaState()
}

sealed class LocalizacaoState {
    object Aguardando : LocalizacaoState()
    data class Disponivel(val lat: Double, val lng: Double) : LocalizacaoState()
    data class Erro(val mensagem: String) : LocalizacaoState()
}
