package com.example.cademeuonibus.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import com.example.cademeuonibus.R
import com.example.cademeuonibus.data.*
import com.example.cademeuonibus.util.FriendlyMessageUtil
import com.example.cademeuonibus.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.gms.location.*
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import android.provider.MediaStore
import androidx.core.view.GravityCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var webViewMapa: WebView? = null
    private lateinit var fusedLocation: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var locPermissaoConcedida = false

    private var mapaCarregado = false
    private var centralizouNoUsuario = false
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<android.view.View>

    private val PALETAS = arrayOf(
        arrayOf("#38BDF8", "#FB923C"), // azul / laranja
        arrayOf("#EF4444", "#22C55E"), // vermelho / verde
        arrayOf("#A855F7", "#EAB308"), // roxo / amarelo
        arrayOf("#EC4899", "#14B8A6"), // rosa / turquesa
        arrayOf("#6366F1", "#84CC16")  // índigo / lima
    )
    private val COR_IDA get() = PALETAS[0][0]
    private val COR_VOLTA get() = PALETAS[0][1]

    private val permissaoLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissoes ->
        locPermissaoConcedida = permissoes[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                                permissoes[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locPermissaoConcedida) iniciarLocalizacao()
        else mostrarStatus(FriendlyMessageUtil.getPermission(), cor = "#fbbf24")
    }
    
    private val avatarPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val original = MediaStore.Images.Media.getBitmap(this.contentResolver, it)
                val scaled = Bitmap.createScaledBitmap(original, 128, 128, true)
                val base64 = bitmapToBase64(scaled)
                val fullBase64 = "data:image/png;base64,$base64"
                getSharedPreferences("user_prefs", Context.MODE_PRIVATE).edit().putString("user_avatar", fullBase64).apply()
                executarJs("window.setUserAvatar('$fullBase64');")
            } catch (e: Exception) { Log.e("MainActivity", "Erro avatar: ${e.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        configurarWebView()
        configurarBusca()
        configurarBotoesMapa()
        configurarBottomSheet()
        configurarMenuLateral()
        observarViewModel()
        verificarPermissaoLocalizacao()
    }

    private fun configurarBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.detailsBottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: android.view.View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN ||
                    newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    executarJs("window.limparSelecaoOnibus();")
                }
            }

            override fun onSlide(bottomSheet: android.view.View, slideOffset: Float) = Unit
        })
        binding.detailsBottomSheet.setOnClickListener {}
    }
    
    private fun configurarMenuLateral() {
        binding.btnSettings.setOnClickListener { esconderTeclado(); binding.drawerLayout.openDrawer(GravityCompat.END) }
        binding.btnFavoritas.setOnClickListener { esconderTeclado(); binding.drawerLayout.openDrawer(GravityCompat.START) }

        // Lógica de Submenu "Como Usar"
        findViewById<android.view.View>(R.id.btnHowToUse)?.setOnClickListener {
            findViewById<android.view.View>(R.id.llSettingsMain)?.visibility = android.view.View.GONE
            findViewById<android.view.View>(R.id.llHowToUse)?.visibility = android.view.View.VISIBLE
        }
        findViewById<android.view.View>(R.id.btnBackFromHowToUse)?.setOnClickListener {
            findViewById<android.view.View>(R.id.llHowToUse)?.visibility = android.view.View.GONE
            findViewById<android.view.View>(R.id.llSettingsMain)?.visibility = android.view.View.VISIBLE
        }
        
        // Resetar o menu ao fechar a gaveta
        binding.drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: android.view.View) {
                if (drawerView.id == R.id.navView) {
                    findViewById<android.view.View>(R.id.llHowToUse)?.visibility = android.view.View.GONE
                    findViewById<android.view.View>(R.id.llSettingsMain)?.visibility = android.view.View.VISIBLE
                }
            }
        })
        
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        findViewById<SwitchMaterial>(R.id.switchSatellite)?.apply {
            isChecked = prefs.getBoolean("map_satellite", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("map_satellite", isChecked).apply()
                executarJs("window.setMapType('${if (isChecked) "Satélite" else "Padrão"}');")
            }
        }
        findViewById<SwitchMaterial>(R.id.switchHideNoSignal)?.apply {
            isChecked = prefs.getBoolean("hide_no_signal", false)
            setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("hide_no_signal", isChecked).apply() }
        }
        findViewById<SwitchMaterial>(R.id.switchSimpleIcon)?.apply {
            isChecked = prefs.getBoolean("use_bus_icon", false) // Invertido: falso significa usar bolinha (padrão)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("use_bus_icon", isChecked).apply()
                executarJs("window.setIconStyle($isChecked);")
            }
        }
        findViewById<SwitchMaterial>(R.id.switchShowRoutes)?.apply {
            isChecked = prefs.getBoolean("show_routes", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("show_routes", isChecked).apply()
                executarJs("window.toggleRoutes($isChecked);")
            }
        }
        findViewById<SwitchMaterial>(R.id.switchShowStops)?.apply {
            isChecked = prefs.getBoolean("show_stops", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("show_stops", isChecked).apply()
                executarJs("window.toggleStops($isChecked);")
            }
        }
        findViewById<android.view.View>(R.id.btnChangeAvatar)?.setOnClickListener { avatarPickerLauncher.launch("image/*") }
    }

    private fun observarViewModel() {
        viewModel.buscaState.observe(this) { estado ->
            when (estado) {
                is BuscaState.Buscando, is BuscaState.CarregandoGtfs -> { 
                    binding.loadingIndicator.visibility = android.view.View.VISIBLE 
                }
                is BuscaState.Sucesso -> {
                    if (!estado.aindaBuscandoGps) {
                        binding.loadingIndicator.visibility = android.view.View.GONE
                    } else {
                        binding.loadingIndicator.visibility = android.view.View.VISIBLE
                    }
                    mostrarStatus("", "#FFFFFF")
                    if (estado.reescreverMapa) desenharRotas()
                    renderizarVeiculos(estado)
                }
                is BuscaState.Erro -> { 
                    binding.loadingIndicator.visibility = android.view.View.GONE
                    mostrarStatus(estado.mensagem, "#F87171") 
                }
                else -> { binding.loadingIndicator.visibility = android.view.View.GONE }
            }
        }
        viewModel.linhasMonitoradas.observe(this) { linhas ->
            atualizarFavoritas(linhas)
            if (mapaCarregado) { desenharRotas(); renderizarTodasLinhas() }
        }
    }

    private fun renderizarVeiculos(estado: BuscaState.Sucesso) {
        if (!mapaCarregado || webViewMapa == null) return
        val payload = JSONArray()
        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val hideNoSignal = prefs.getBoolean("hide_no_signal", false)
        val configuracao = viewModel.getLinhaMonitorada(estado.linha)
        // Um resultado de rede pode chegar logo após o usuário remover a linha.
        if (configuracao == null) return
        var count = 0
        for (v in estado.veiculos) {
            if (hideNoSignal && v.direcao.delaySegundos != null && v.direcao.delaySegundos > 300) continue
            if (v.direcao.directionId == 0 && configuracao?.exibirIda == false) continue
            if (v.direcao.directionId != 0 && configuracao?.exibirVolta == false) continue
            count++
            payload.put(JSONObject().apply {
                put("ordem", "${estado.linha}:${v.ordem}"); put("linha", estado.linha); put("lat", v.lat); put("lng", v.lng)
                put("cor", corDaLinha(estado.linha, v.direcao.directionId))
                put("bearing", v.bearing ?: v.direcao.bearing ?: 0.0); put("vel", v.velocidade)
                put("progresso", v.direcao.progresso); put("headsign", v.direcao.headsign)
                put("delayTxt", if (v.direcao.delaySegundos != null) "${v.direcao.delaySegundos}s" else "")
            })
        }
        binding.tvDetCountPill.text = "$count Ônibus"
        executarJs("window.atualizarOnibusDaLinha('${estado.linha}', $payload, ${estado.reescreverMapa});")
    }

    private fun corDaLinha(linha: String, directionId: Int): String {
        val indice = viewModel.linhasMonitoradas.value?.indexOfFirst { it.linha == linha }?.takeIf { it >= 0 } ?: 0
        return PALETAS[indice][if (directionId == 0) 0 else 1]
    }

    private fun renderizarTodasLinhas() {
        // CORREÇÃO VISUAL: Se a linha foi totalmente excluída do ViewModel, removemos os ônibus do mapa Leaflet
        // Isso impede que os marcadores fiquem flutuando sozinhos na tela sem as rotas correspondentes.
        val monitoradas = viewModel.linhasMonitoradas.value?.map { it.linha }?.toSet().orEmpty()
        
        viewModel.getUltimosVeiculos().forEach { (linha, veiculos) ->
            if (monitoradas.contains(linha)) {
                renderizarVeiculos(BuscaState.Sucesso(veiculos, linha, "", false))
            } else {
                executarJs("window.removerLinha('$linha');")
            }
        }
    }

    private fun desenharRotas() {
        if (!mapaCarregado || webViewMapa == null) return
        
        // CONSTRUÇÃO ATÔMICA: Cria um único script para evitar conflitos de sincronia
        val script = StringBuilder("window.limparMapa();\n")
        val jsonParadas = JSONArray()
        
        for ((selecionada, dadosLinha) in viewModel.getLinhasComDados()) for ((did, info) in dadosLinha.direcoes) {
            if ((did == 0 && !selecionada.exibirIda) || (did != 0 && !selecionada.exibirVolta)) continue
            val cor = corDaLinha(selecionada.linha, did)
            val points = info.shape
            if (points.isNotEmpty()) {
                // Filtro equilibrado: 600 pontos garantem curvas perfeitas e leveza
                val passo = maxOf(1, points.size / 600)
                val filtered = points.filterIndexed { i, _ -> i % passo == 0 }
                val pontosArray = JSONArray()
                for (p in filtered) {
                    pontosArray.put(JSONArray().apply { put(p.lat); put(p.lng) })
                }
                // Adiciona o comando de desenho diretamente no script principal
                script.append("window.desenharTrajetoria($pontosArray, '$cor', '${selecionada.linha}');\n")
            }
            
            for (p in info.paradas) {
                jsonParadas.put(JSONObject().apply { 
                    put("nome", p.nome); put("lat", p.lat); put("lng", p.lng); put("cor", cor) 
                })
            }
        }
        
        // Adiciona o desenho das paradas por último
        script.append("window.desenharParadas($jsonParadas);\n")

        // Ajusta o zoom para mostrar a rota completa
        script.append("window.fitBoundsToRoutes();\n")
        
        // EXECUÇÃO ÚNICA: Envia tudo para o WebView de uma só vez
        executarJs(script.toString())
    }

    private fun atualizarFavoritas(linhas: List<LinhaMonitorada>) {
        binding.favoritesLinesContainer.removeAllViews()
        val favoritas = linhas.filter { it.salva }
        
        if (favoritas.isEmpty()) {
            binding.favoritesLinesContainer.addView(TextView(this).apply {
                text = "Nenhuma linha favorita ainda"; setTextColor(android.graphics.Color.parseColor("#94A3B8")); textSize = 14f; gravity = android.view.Gravity.CENTER; setPadding(0, dp(36), 0, 0)
            })
            return
        }
        favoritas.forEachIndexed { indice, linha ->
            val corIda = android.graphics.Color.parseColor(PALETAS[indice][0])
            val corVolta = android.graphics.Color.parseColor(PALETAS[indice][1])
            val cartao = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(10), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat(); setColor(android.graphics.Color.parseColor("#F8FAFC")); setStroke(dp(1), android.graphics.Color.parseColor("#E2E8F0"))
                }
            }
            val cabecalho = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
            cabecalho.addView(TextView(this).apply { text = "Linha " + linha.linha; setTextColor(android.graphics.Color.parseColor("#0F172A")); textSize = 17f; typeface = android.graphics.Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            cabecalho.addView(TextView(this).apply { text = "Remover"; setTextColor(android.graphics.Color.parseColor("#EF4444")); textSize = 12f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setOnClickListener { 
                viewModel.removerLinha(linha.linha)
                executarJs("window.removerLinha('${linha.linha}');")
                
                // FECHA O BOTTOM SHEET COM SEGURANÇA: Se o painel atualmente aberto for da linha removida, fecha-o
                if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN && binding.tvDetLinha.text.contains(linha.linha)) {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                }
            } })
            cartao.addView(cabecalho)
            
            fun interruptor(texto: String, marcado: Boolean, ida: Boolean, cor: Int) = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
                text = texto; textSize = 14f; setTextColor(android.graphics.Color.parseColor("#334155"))
                
                // Configuração perfeita dos seletores de cor para evitar tint roxo padrão do Material
                val trackStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                trackTintList = android.content.res.ColorStateList(trackStates, intArrayOf(
                    android.graphics.Color.argb(100, android.graphics.Color.red(cor), android.graphics.Color.green(cor), android.graphics.Color.blue(cor)),
                    android.graphics.Color.parseColor("#CBD5E1")
                ))
                
                thumbTintList = android.content.res.ColorStateList(trackStates, intArrayOf(cor, android.graphics.Color.parseColor("#FFFFFF")))
                isChecked = marcado
                setOnCheckedChangeListener { _, _ -> viewModel.alternarDirecao(linha.linha, ida) }
            }
            
            val nomeIda = viewModel.obterHeadsignDaDirecao(linha.linha, 0) ?: "Trajeto de ida"
            val nomeVolta = viewModel.obterHeadsignDaDirecao(linha.linha, 1) ?: "Trajeto de volta"
            
            cartao.addView(interruptor(nomeIda, linha.exibirIda, true, corIda))
            cartao.addView(interruptor(nomeVolta, linha.exibirVolta, false, corVolta))
            binding.favoritesLinesContainer.addView(cartao, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
    }
    private fun dp(valor: Int) = (valor * resources.displayMetrics.density).toInt()

    private fun configurarWebView() {
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                setGeolocationEnabled(true)
            }
            addJavascriptInterface(MapaBridge(), "AndroidBridge")
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, true, false) }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { mapaCarregado = true }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (url.endsWith("bus_sprites.png")) {
                        val file = File(filesDir, "bus_sprites.png")
                        if (file.exists()) return WebResourceResponse("image/png", "UTF-8", FileInputStream(file))
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
        }
        binding.mapContainer.addView(wv); webViewMapa = wv; wv.loadUrl("file:///android_asset/map.html")
    }

    private fun configurarBusca() {
        binding.btnClear.setOnClickListener { binding.editLinha.text.clear() }
        
        val adapterBusca = object : ArrayAdapter<String>(this, R.layout.item_sugestao_moderna, mutableListOf<String>()) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_sugestao_moderna, parent, false)
                val item = getItem(position) ?: ""
                val tvNumero = view.findViewById<TextView>(R.id.tvSugNumero)
                val tvTexto = view.findViewById<TextView>(R.id.tvSugTexto)
                val partes = item.split(" - ")
                tvNumero.text = partes.getOrNull(0) ?: ""; tvTexto.text = partes.getOrNull(1) ?: ""
                return view
            }
        }
        binding.editLinha.setAdapter(adapterBusca)
        binding.editLinha.threshold = 1

        binding.editLinha.setOnFocusChangeListener { _, hasFocus -> 
            if (hasFocus) {
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                if (binding.editLinha.text.isEmpty()) mostrarHistorico(binding.editLinha)
            }
        }

        binding.editLinha.addTextChangedListener { text ->
            val query = text.toString().trim()
            binding.btnClear.visibility = if (query.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            if (query.isNotEmpty() && !query.contains(" - ")) {
                binding.editLinha.dismissDropDown()
                viewModel.atualizarQuerySugestao(query)
            } else {
                viewModel.atualizarQuerySugestao("")
                if (query.isEmpty() && binding.editLinha.hasFocus()) mostrarHistorico(binding.editLinha)
            }
        }

        binding.editLinha.setOnClickListener { 
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN 
            if (binding.editLinha.text.isEmpty()) mostrarHistorico(binding.editLinha)
        }

        binding.editLinha.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { binding.editLinha.dismissDropDown(); executarBusca(); true } else false
        }
        binding.editLinha.setOnItemClickListener { parent, _, position, _ ->
            val selecao = parent.getItemAtPosition(position) as String
            binding.editLinha.setText(selecao); executarBusca()
        }

        viewModel.sugestoes.observe(this) { lista ->
            val queryAtual = binding.editLinha.text?.toString()?.trim().orEmpty()
            if (queryAtual.isBlank() || queryAtual.contains(" - ")) return@observe
            
            adapterBusca.clear()
            adapterBusca.addAll(lista)
            adapterBusca.filter.filter(queryAtual) { quantidade ->
                if (quantidade > 0 && binding.editLinha.hasFocus()) {
                    binding.editLinha.showDropDown()
                }
            }
        }
        viewModel.historico.observe(this) { _ ->
            if (binding.editLinha.hasFocus() && binding.editLinha.text.isNullOrBlank()) {
                mostrarHistorico(binding.editLinha)
            }
        }
    }

    private fun mostrarHistorico(editText: AutoCompleteTextView) {
        viewModel.historico.value?.let { hist ->
            if (hist.isNotEmpty()) {
                val adapter = editText.adapter as? ArrayAdapter<String>
                val histLimpo = hist.map { "$it - Histórico" }
                adapter?.clear()
                adapter?.addAll(histLimpo)
                adapter?.filter?.filter("") { quantidade ->
                    if (quantidade > 0 && !editText.isPopupShowing) {
                        editText.showDropDown()
                    }
                }
            }
        }
    }

    private fun executarBusca() {
        val texto = binding.editLinha.text.toString().trim()
        if (texto.isNotEmpty()) {
            esconderTeclado()
            // Uma nova linha encerra o destaque do ônibus da busca anterior.
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            executarJs("window.limparSelecaoOnibus();")
            viewModel.buscarLinha(texto)
        }
    }

    private fun configurarBotoesMapa() {
        binding.btnMinhaLocalizacao.setOnClickListener {
            esconderTeclado()
            val loc = viewModel.localizacaoState.value
            if (loc is LocalizacaoState.Disponivel) executarJs("window.centralizarMapa(${loc.lat}, ${loc.lng}, 16);")
        }
    }

    private fun mostrarStatus(msg: String, cor: String) {
        if (msg.isEmpty()) binding.tvStatus.visibility = android.view.View.GONE
        else {
            binding.tvStatus.visibility = android.view.View.VISIBLE; binding.tvStatus.text = msg
            try { binding.tvStatus.setTextColor(android.graphics.Color.parseColor(cor)) } catch (e: Exception) {}
        }
    }

    private fun esconderTeclado() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.editLinha.clearFocus(); binding.editLinha.dismissDropDown()
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val bos = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.DEFAULT).replace("\n", "")
    }

    private fun executarJs(js: String) { webViewMapa?.post { webViewMapa?.evaluateJavascript(js, null) } }

    private fun verificarPermissaoLocalizacao() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) iniciarLocalizacao()
        else permissaoLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @SuppressLint("MissingPermission")
    private fun iniciarLocalizacao() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                viewModel.atualizarLocalizacao(loc.latitude, loc.longitude)
                executarJs("window.atualizarPosicaoUsuario(${loc.latitude}, ${loc.longitude});")
                if (!centralizouNoUsuario && mapaCarregado) { centralizouNoUsuario = true; executarJs("window.centralizarMapa(${loc.latitude}, ${loc.longitude}, 15);") }
            }
        }
        fusedLocation.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }

    inner class MapaBridge {
        @JavascriptInterface
        fun onBusClick(ordem: String, lat: Double, lng: Double, vel: Double, progresso: Double, dist: Double, headsign: String, delayTxt: String) {
            runOnUiThread { 
                esconderTeclado()
                preencherPainel(ordem, lat, lng, vel, progresso, dist, headsign, delayTxt)
                
                // Pequeno delay para permitir que a animação de fechamento do teclado termine
                // e o layout se estabilize antes de expandir o painel.
                binding.root.postDelayed({
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                }, 150)
            }
        }
        @JavascriptInterface fun onBusUpdate(ordem: String, lat: Double, lng: Double, vel: Double, progresso: Double, dist: Double, headsign: String, delayTxt: String) {
            runOnUiThread { if (bottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN && binding.tvDetIdPill.text.contains(ordem)) preencherPainel(ordem, lat, lng, vel, progresso, dist, headsign, delayTxt) }
        }
        @JavascriptInterface fun onMapClick() { runOnUiThread { esconderTeclado(); bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN } }
        @JavascriptInterface fun onMapInteract() { runOnUiThread { esconderTeclado() } }
        @JavascriptInterface fun onMapReady() { runOnUiThread { 
            mapaCarregado = true
            val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            prefs.getString("user_avatar", null)?.let { executarJs("window.setUserAvatar('$it');") }
            executarJs("window.setMapType('${if (prefs.getBoolean("map_satellite", false)) "Satélite" else "Padrão"}');")
            executarJs("window.setIconStyle(${prefs.getBoolean("use_bus_icon", false)});")
            executarJs("window.toggleRoutes(${prefs.getBoolean("show_routes", true)});")
             executarJs("window.toggleStops(${prefs.getBoolean("show_stops", true)});")
             desenharRotas()
             renderizarTodasLinhas()
        } }
    }

    private fun preencherPainel(ordem: String, lat: Double, lng: Double, vel: Double, progresso: Double, dist: Double, headsign: String, delayTxt: String) {
        val linha = ordem.substringBefore(":")
        binding.tvDetLinha.text = "Linha $linha"
        binding.tvDetSentido.text = headsign; binding.tvDetIdPill.text = "ID: $ordem"
        binding.tvPillVelocidade.text = "⚡ ${vel.toInt()} km/h"
        binding.tvDetDistancia.text = String.format(Locale.getDefault(), "📍 %.1f km", dist)
        val etaMin = if (vel > 10) (dist / vel * 60).toInt() else (dist / 20 * 60).toInt()
        binding.tvDetEta.text = if (etaMin > 0) "$etaMin min" else "Perto"
        binding.tvPillDelay.text = if (delayTxt.isEmpty() || delayTxt.contains("0s")) "✔ No horário" else "⚠️ $delayTxt"
        binding.detProgressBar.progress = (progresso * 100).toInt()
        binding.tvDetProgresso.text = "${(progresso * 100).toInt()}%"
        val isIda = viewModel.getDadosLinha(linha)?.direcoes?.get(0)?.headsign == headsign
        binding.flBusIcon.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(corDaLinha(linha, if (isIda) 0 else 1))
        )
        
        // Configuração Dinâmica da Estrela de Favoritos
        val linhaConfig = viewModel.getLinhaMonitorada(linha)
        val isFavorita = linhaConfig?.salva == true
        binding.btnFavStar.imageTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(if (isFavorita) "#FBBF24" else "#475569")
        )
        
        binding.btnFavStar.setOnClickListener {
            val configAtual = viewModel.getLinhaMonitorada(linha)
            if (configAtual?.salva == true) {
                // Se já é favorita, remove completamente da lista E do mapa
                viewModel.removerLinha(linha)
                executarJs("window.removerLinha('$linha');")
                bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            } else {
                // Se não é favorita, adiciona na lista
                viewModel.marcarComoFavoritaExistente(linha)
                binding.btnFavStar.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FBBF24"))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocation.removeLocationUpdates(it) }
    }
}
