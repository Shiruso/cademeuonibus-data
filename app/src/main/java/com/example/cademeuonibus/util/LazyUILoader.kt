package com.example.cademeuonibus.util

import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Sistema de lazy loading para componentes de UI pesados
 */
class LazyUILoader {
    
    private val loadedComponents = ConcurrentHashMap<String, Boolean>()
    private val loadingComponents = ConcurrentHashMap<String, Boolean>()
    
    /**
     * Carrega um componente de forma lazy quando necessário
     */
    fun loadComponent(
        componentId: String,
        viewStub: ViewStub?,
        delayMs: Long = 0,
        onLoaded: (View) -> Unit = {}
    ) {
        if (loadedComponents[componentId] == true || loadingComponents[componentId] == true) {
            return
        }
        
        if (viewStub == null) return
        
        loadingComponents[componentId] = true
        
        BackgroundProcessor.scheduleBackground {
            if (delayMs > 0) {
                delay(delayMs)
            }
            
            BackgroundProcessor.executeOnMain {
                try {
                    val inflatedView = viewStub.inflate()
                    loadedComponents[componentId] = true
                    onLoaded(inflatedView)
                } catch (e: Exception) {
                    // ViewStub já foi inflado ou view foi destruída
                } finally {
                    loadingComponents.remove(componentId)
                }
            }
        }
    }
    
    /**
     * Carrega componentes com prioridade (críticos primeiro)
     */
    fun loadWithPriority(
        components: List<LazyComponent>
    ) {
        BackgroundProcessor.scheduleBackground {
            components.sortedBy { it.priority }.forEach { component ->
                if (!isLoaded(component.id)) {
                    loadComponent(component.id, component.viewStub, component.delayMs, component.onLoaded)
                    // Delay entre carregamentos para evitar sobrecarga
                    delay(50)
                }
            }
        }
    }
    
    /**
     * Verifica se um componente já foi carregado
     */
    fun isLoaded(componentId: String): Boolean {
        return loadedComponents[componentId] == true
    }
    
    /**
     * Verifica se um componente está sendo carregado
     */
    fun isLoading(componentId: String): Boolean {
        return loadingComponents[componentId] == true
    }
    
    /**
     * Remove um componente do cache (útil para recarregar)
     */
    fun unload(componentId: String) {
        loadedComponents.remove(componentId)
        loadingComponents.remove(componentId)
    }
    
    /**
     * Limpa todos os componentes carregados
     */
    fun clear() {
        loadedComponents.clear()
        loadingComponents.clear()
    }
    
    data class LazyComponent(
        val id: String,
        val viewStub: ViewStub?,
        val priority: Int = 5, // 1 = alta prioridade, 10 = baixa prioridade
        val delayMs: Long = 0,
        val onLoaded: (View) -> Unit = {}
    )
}

/**
 * Otimizador de RecyclerView para listas grandes
 */
object RecyclerViewOptimizer {
    
    /**
     * Configura RecyclerView para performance otimizada
     */
    fun optimizeRecyclerView(recyclerView: androidx.recyclerview.widget.RecyclerView) {
        // Usa pool compartilhado para ViewHolders
        recyclerView.setRecycledViewPool(SharedViewPool.instance)
        
        // Otimizações de performance
        recyclerView.setHasFixedSize(true)
        recyclerView.setItemViewCacheSize(20)
        
        // Pré-carrega itens fora da tela
        recyclerView.layoutManager?.let { layoutManager ->
            when (layoutManager) {
                is androidx.recyclerview.widget.LinearLayoutManager -> {
                    layoutManager.initialPrefetchItemCount = 4
                }
            }
        }
    }
    
    /**
     * Pool compartilhado de ViewHolders entre RecyclerViews
     */
    object SharedViewPool {
        val instance = androidx.recyclerview.widget.RecyclerView.RecycledViewPool().apply {
            setMaxRecycledViews(0, 20) // ViewType 0 (itens gerais)
            setMaxRecycledViews(1, 10) // ViewType 1 (headers, etc)
        }
    }
}

/**
 * Sistema de viewport culling para elementos fora da tela
 */
class ViewportCuller {
    
    private val visibleViews = mutableSetOf<View>()
    
    /**
     * Verifica se uma view está visível no viewport
     */
    fun isInViewport(view: View, container: ViewGroup): Boolean {
        val containerRect = android.graphics.Rect()
        container.getGlobalVisibleRect(containerRect)
        
        val viewRect = android.graphics.Rect()
        view.getGlobalVisibleRect(viewRect)
        
        return containerRect.intersect(viewRect)
    }
    
    /**
     * Oculta views fora do viewport para economizar recursos
     */
    fun cullViewsOutsideViewport(container: ViewGroup) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            
            if (isInViewport(child, container)) {
                if (!visibleViews.contains(child)) {
                    // View entrou no viewport
                    child.visibility = View.VISIBLE
                    visibleViews.add(child)
                }
            } else {
                if (visibleViews.contains(child)) {
                    // View saiu do viewport
                    child.visibility = View.GONE
                    visibleViews.remove(child)
                }
            }
        }
    }
    
    fun clear() {
        visibleViews.clear()
    }
}