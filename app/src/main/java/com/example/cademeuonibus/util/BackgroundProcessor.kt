package com.example.cademeuonibus.util

import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * Processador dedicado para operações pesadas em background
 * com pool de threads otimizado e priorização de tarefas
 */
object BackgroundProcessor {
    
    // Dispatcher otimizado para cálculos pesados
    private val heavyComputationDispatcher = Executors.newFixedThreadPool(2) { thread ->
        Thread(thread, "BusApp-HeavyComputation").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1 // Prioridade ligeiramente menor
        }
    }.asCoroutineDispatcher()
    
    // Dispatcher para operações de I/O (network, database)
    private val ioDispatcher = Dispatchers.IO
    
    // Scope para operações de longa duração
    private val backgroundScope = CoroutineScope(
        SupervisorJob() + 
        CoroutineName("BackgroundProcessor") + 
        heavyComputationDispatcher
    )
    
    /**
     * Executa cálculos pesados (inferência de direção, processamento JSON)
     */
    suspend fun <T> executeHeavyComputation(block: suspend CoroutineScope.() -> T): T {
        return withContext(heavyComputationDispatcher) {
            block()
        }
    }
    
    /**
     * Executa operações de I/O (network, database)
     */
    suspend fun <T> executeIO(block: suspend CoroutineScope.() -> T): T {
        return withContext(ioDispatcher) {
            block()
        }
    }
    
    /**
     * Executa na main thread (para updates de UI)
     */
    suspend fun <T> executeOnMain(block: suspend CoroutineScope.() -> T): T {
        return withContext(Dispatchers.Main) {
            block()
        }
    }
    
    /**
     * Executa com timeout e fallback
     */
    suspend fun <T> executeWithTimeout(
        timeoutMs: Long,
        fallback: T,
        block: suspend CoroutineScope.() -> T
    ): T {
        return try {
            withTimeout(timeoutMs) {
                executeHeavyComputation(block)
            }
        } catch (e: TimeoutCancellationException) {
            fallback
        }
    }
    
    /**
     * Processa lista em paralelo com limite de concorrência
     */
    suspend fun <T, R> processInParallel(
        items: List<T>,
        concurrency: Int = 4,
        processor: suspend (T) -> R
    ): List<R> {
        return withContext(heavyComputationDispatcher) {
            items.chunked(concurrency).flatMap { chunk ->
                chunk.map { item ->
                    async { processor(item) }
                }.awaitAll()
            }
        }
    }
    
    /**
     * Agenda tarefa para execução em background (fire-and-forget)
     */
    fun scheduleBackground(block: suspend CoroutineScope.() -> Unit): Job {
        return backgroundScope.launch {
            try {
                block()
            } catch (e: Exception) {
                // Log error but don't crash
                android.util.Log.w("BackgroundProcessor", "Background task failed", e)
            }
        }
    }
    
    /**
     * Cancela todas as operações em background
     */
    fun cancelAll() {
        backgroundScope.coroutineContext.cancelChildren()
    }
    
    /**
     * Limpa recursos (chamar no onDestroy da aplicação)
     */
    fun shutdown() {
        backgroundScope.cancel()
        heavyComputationDispatcher.close()
    }
}