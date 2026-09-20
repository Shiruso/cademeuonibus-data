package com.example.cademeuonibus.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/**
 * Sistema de throttling para atualizações de UI com debouncing inteligente
 */
class UIThrottler {
    
    companion object {
        private const val DEFAULT_THROTTLE_MS = 100L
        private const val MAP_UPDATE_THROTTLE_MS = 200L
        private const val SUGGESTIONS_THROTTLE_MS = 300L
        private const val PANEL_UPDATE_THROTTLE_MS = 150L
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private val pendingTasks = ConcurrentHashMap<String, Runnable>()
    
    /**
     * Executa uma ação com throttling baseado na key
     */
    fun throttle(key: String, delayMs: Long = DEFAULT_THROTTLE_MS, action: () -> Unit) {
        // Cancela task anterior se existir
        pendingTasks[key]?.let { handler.removeCallbacks(it) }
        
        val newTask = Runnable {
            try {
                action()
            } finally {
                pendingTasks.remove(key)
            }
        }
        
        pendingTasks[key] = newTask
        handler.postDelayed(newTask, delayMs)
    }
    
    /**
     * Throttling específico para atualizações do mapa
     */
    fun throttleMapUpdate(action: () -> Unit) {
        throttle("map_update", MAP_UPDATE_THROTTLE_MS, action)
    }
    
    /**
     * Throttling para sugestões de busca
     */
    fun throttleSuggestions(action: () -> Unit) {
        throttle("suggestions", SUGGESTIONS_THROTTLE_MS, action)
    }
    
    /**
     * Throttling para atualizações do painel de detalhes
     */
    fun throttlePanelUpdate(action: () -> Unit) {
        throttle("panel_update", PANEL_UPDATE_THROTTLE_MS, action)
    }
    
    /**
     * Execução imediata (cancela throttling anterior)
     */
    fun immediate(key: String, action: () -> Unit) {
        pendingTasks[key]?.let { 
            handler.removeCallbacks(it)
            pendingTasks.remove(key)
        }
        action()
    }
    
    /**
     * Cancela todas as tasks pendentes
     */
    fun cancelAll() {
        pendingTasks.values.forEach { handler.removeCallbacks(it) }
        pendingTasks.clear()
    }
    
    /**
     * Cancela task específica
     */
    fun cancel(key: String) {
        pendingTasks[key]?.let { 
            handler.removeCallbacks(it)
            pendingTasks.remove(key)
        }
    }
}