package com.example.cademeuonibus.util

import kotlin.random.Random

/**
 * Utilitário para transformar mensagens técnicas em frases naturais e "cariocas".
 */
object FriendlyMessageUtil {

    private val GENERIC_ERRORS = listOf(
        "Ih, deu ruim... Tenta de novo!",
        "Algo de errado não está certo.",
        "Ops! O sistema deu uma cochilada.",
        "Eita! Ocorreu um erro inesperado.",
        "Deu zebra por aqui..."
    )

    private val CONNECTION_ERRORS = listOf(
        "Conexão lenta? Deu zebra na rede.",
        "O sinal fugiu! Verifica a internet.",
        "Tá sem rede? O ônibus sumiu do radar.",
        "Ih, a internet deu uma rateada.",
        "Parece que o Wi-fi ou 4G tiraram folga."
    )

    private val NOT_FOUND_ERRORS = listOf(
        "Essa linha tá difícil de achar...",
        "Ônibus fantasma? Não achei nada agora.",
        "Linha sumida! Tem certeza que digitou certo?",
        "Nenhum ônibus no radar agora.",
        "Ih, parece que essa linha tá descansando."
    )

    private val GPS_ERRORS = listOf(
        "Me ajuda a te achar! Liga o GPS aí.",
        "Tô perdido! Onde você está? (Liga o GPS)",
        "GPS desligado? Assim eu não te vejo.",
        "Dá um toque no GPS pra gente se localizar.",
        "Sinal de GPS tá fraco ou desligado."
    )

    private val PERMISSION_ERRORS = listOf(
        "Preciso de um OK seu pra ver sua localização.",
        "Sem permissão, não consigo te mostrar no mapa.",
        "Libera a localização pra gente rodar liso!",
        "Dá aquela moral nas permissões de localização?"
    )

    fun getGeneric(): String = GENERIC_ERRORS.random()
    fun getConnection(): String = CONNECTION_ERRORS.random()
    fun getNotFound(): String = NOT_FOUND_ERRORS.random()
    fun getGps(): String = GPS_ERRORS.random()
    fun getPermission(): String = PERMISSION_ERRORS.random()

    private fun <T> List<T>.random(): T = this[Random.nextInt(size)]
}
