package com.example.cademeuonibus.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.cademeuonibus.R
import com.example.cademeuonibus.data.GtfsParser
import com.example.cademeuonibus.util.DataUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.FileInputStream

/**
 * SplashActivity
 * Exibe as imagens da pasta assets/Intros com transição suave estilo GTA V (crossfade).
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var imgFront: ImageView
    private lateinit var imgBack: ImageView
    private lateinit var tvStatus: TextView
    private var isShowingFront = true
    private var isUpdateFinished = false
    private var isSlideshowFinished = false
    
    // Configurações de animação
    private val TEMPO_POR_IMAGEM_MS = 3000L        // 3 segundos por imagem
    private val DURACAO_FADE_MS = 1500L            // 1.5 segundos de transição suave
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Esconde as barras de sistema para tela cheia
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.decorView.windowInsetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }

        setContentView(R.layout.activity_splash)

        imgFront = findViewById(R.id.imgSplash)
        imgBack = findViewById(R.id.imgSplashBackground)
        tvStatus = findViewById(R.id.tvUpdateStatus)
        
        // Começa com a frente visível e fundo invisível
        imgFront.alpha = 1f
        imgBack.alpha = 0f

        // 1. Inicia a exibição da capa atual IMEDIATAMENTE
        exibirCapaEGerenciarFluxo()

        // 2. Verifica atualizações em segundo plano (para a PRÓXIMA vez)
        verificarAtualizacoes()
    }

    private fun exibirCapaEGerenciarFluxo() {
        try {
            val updateManager = DataUpdateManager(this)
            val introsSalvas = updateManager.getSavedIntrosList()
            
            // Pega a capa salva ou a capa inicial embutida no aplicativo.
            val nomeImagem = introsSalvas?.firstOrNull() ?: "img6.png"
            
            android.util.Log.d("SplashActivity", "Exibindo capa: $nomeImagem")
            carregarImagemEmView(nomeImagem, imgFront)

            // Aguarda 3 segundos para o usuário ver a capa e o sistema atualizar
            Handler(Looper.getMainLooper()).postDelayed({
                irParaMain()
            }, 3500)

        } catch (e: Exception) {
            android.util.Log.e("SplashActivity", "Erro na splash", e)
            irParaMain()
        }
    }

    private fun verificarAtualizacoes() {
        val updateManager = DataUpdateManager(this)
        lifecycleScope.launch(Dispatchers.IO) {
            updateManager.checkForUpdates { status ->
                runOnUiThread {
                    tvStatus.text = status
                }
            }
            // Sinaliza que a verificação terminou
            isUpdateFinished = true
            
            // Se o slideshow já acabou, entra na Main agora
            if (isSlideshowFinished) {
                runOnUiThread { irParaMain() }
            }
        }
    }

    private fun iniciarSlideshowComFade(intros: List<String>) {
        android.util.Log.d("SplashActivity", "Iniciando slideshow com ${intros.size} imagens")
        
        // Inicia o pre-carregamento do GTFS enquanto mostra as imagens
        val parser = GtfsParser(this)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                parser.preCarregarEstrutura()
            } catch (e: Exception) {
                android.util.Log.e("SplashActivity", "Erro pre-carregando GTFS: ${e.message}")
            }
        }

        val handler = Handler(Looper.getMainLooper())
        var index = 0
        var imagensCarregadas = 0
        
        // Carrega a primeira imagem imediatamente
        android.util.Log.d("SplashActivity", "Tentando carregar imagem inicial [0]: ${intros[0]}")
        val primeiraCarregou = carregarImagemEmView(intros[0], imgFront)
        
        if (primeiraCarregou) {
            imagensCarregadas++
            index++
            
            val runnable = object : Runnable {
                override fun run() {
                    if (index < intros.size) {
                        android.util.Log.d("SplashActivity", "Transição para imagem [$index]: ${intros[index]}")
                        
                        var carregouComSucesso = false
                        
                        // Alterna entre as duas ImageViews para criar crossfade
                        if (isShowingFront) {
                            carregouComSucesso = carregarImagemEmView(intros[index], imgBack)
                            if (carregouComSucesso) {
                                crossFade(imgBack, imgFront)
                                imagensCarregadas++
                            }
                        } else {
                            carregouComSucesso = carregarImagemEmView(intros[index], imgFront)
                            if (carregouComSucesso) {
                                crossFade(imgFront, imgBack)
                                imagensCarregadas++
                            }
                        }
                        
                        if (carregouComSucesso) {
                            isShowingFront = !isShowingFront
                        }
                        
                        index++
                        handler.postDelayed(this, TEMPO_POR_IMAGEM_MS)
                    } else {
                        android.util.Log.d("SplashActivity", "Slideshow finalizado - Aguardando atualização...")
                        isSlideshowFinished = true
                        
                        // Só vai para a Main se o UpdateManager já tiver dado o "OK"
                        // Ou se passar de um tempo limite de 5 segundos de espera
                        if (isUpdateFinished) {
                            handler.postDelayed({ irParaMain() }, 500)
                        } else {
                            // Aguarda o UpdateManager chamar irParaMain ou dá um timeout
                            handler.postDelayed({ 
                                if (!isFinishing) irParaMain() 
                            }, 5000)
                        }
                    }
                }
            }
            handler.postDelayed(runnable, TEMPO_POR_IMAGEM_MS)
        } else {
            android.util.Log.w("SplashActivity", "Falha crítica ao carregar imagem inicial. Indo direto para Main.")
            irParaMain()
        }
    }
    
    /**
     * Crossfade suave estilo GTA V:
     * - viewIn vai de alpha 0 → 1 (aparece)
     * - viewOut vai de alpha 1 → 0 (desaparece)
     * Ambos ao mesmo tempo com interpolação suave
     */
    private fun crossFade(viewIn: ImageView, viewOut: ImageView) {
        // Animação de entrada (fade in)
        val fadeIn = ObjectAnimator.ofFloat(viewIn, "alpha", 0f, 1f).apply {
            duration = DURACAO_FADE_MS
            interpolator = DecelerateInterpolator() // Suavização no final
        }
        
        // Animação de saída (fade out)
        val fadeOut = ObjectAnimator.ofFloat(viewOut, "alpha", 1f, 0f).apply {
            duration = DURACAO_FADE_MS
            interpolator = DecelerateInterpolator()
        }
        
        // Inicia ambas ao mesmo tempo
        fadeIn.start()
        fadeOut.start()
    }
    
    /**
     * Carrega bitmap de forma eficiente (evita OutOfMemoryError)
     * Retorna true se carregou com sucesso, false se falhou
     */
    private fun carregarImagemEmView(nomeArquivo: String, imageView: ImageView): Boolean {
        try {
            val fileDownloaded = File(filesDir, "Intros/$nomeArquivo")
            android.util.Log.d("SplashActivity", "Carregando imagem: $nomeArquivo (Existe no download? ${fileDownloaded.exists()})")

            // Define o stream de entrada (Prioriza arquivo baixado, depois assets)
            val inputStreamProvider: () -> InputStream = {
                if (fileDownloaded.exists()) FileInputStream(fileDownloaded)
                else assets.open("Intros/$nomeArquivo")
            }

            // Otimização: Primeiro lê apenas as dimensões
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            inputStreamProvider().use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            // Calcula o redimensionamento ideal para a tela
            val displayMetrics = resources.displayMetrics
            options.inSampleSize = calcularInSampleSize(options, displayMetrics.widthPixels, displayMetrics.heightPixels)
            options.inJustDecodeBounds = false
            
            // Decodifica o bitmap redimensionado
            inputStreamProvider().use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream, null, options)
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    return true
                }
            }
            return false
        } catch (e: Exception) {
            android.util.Log.e("SplashActivity", "Erro ao carregar $nomeArquivo: ${e.message}")
            return false
        }
    }

    private fun calcularInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height: Int = options.outHeight
        val width: Int = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
    
    private fun irParaMain() {
        if (isFinishing) return
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
