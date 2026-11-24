package com.andserver

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import java.net.NetworkInterface

class MainActivity : Activity() {
    // Paleta moderna e clean (Dark Slate, Emerald & Sky Blue)
    private val bg = Color.rgb(11, 15, 23)           // #0B0F17 Superfície de fundo profunda
    private val panel = Color.rgb(19, 27, 44)        // #131B2C Cartões e seções
    private val panelAlt = Color.rgb(27, 38, 62)     // #1B263E Superfície interna/destaque
    private val panelBorder = Color.rgb(38, 52, 80)  // #263450 Bordas sutis
    private val mint = Color.rgb(16, 185, 129)       // #10B981 Emerald vibrante
    private val mintDark = Color.rgb(6, 78, 59)      // #064E3B Fundo do badge ativo
    private val skyBlue = Color.rgb(56, 189, 248)    // #38BDF8 Sky Blue para Internet WAN
    private val skyBlueDark = Color.rgb(12, 74, 110) // #0C4A6E Fundo do badge WAN
    private val textPrimary = Color.rgb(248, 250, 252) // #F8FAFC Texto principal
    private val textMuted = Color.rgb(148, 163, 184)   // #94A3B8 Texto secundário
    private val textSubtle = Color.rgb(100, 116, 139)  // #64748B Legendas menores
    private val amber = Color.rgb(245, 158, 11)        // #F59E0B Alerta/Inativo
    private val amberDark = Color.rgb(120, 53, 15)     // #78350F Fundo inativo

    private val prefs by lazy { getSharedPreferences("andserver", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var navButtons: List<Button>
    private var screen = 0
    private var service: ServerService? = null
    private var bound = false
    private var selectedType = "web"
    private var selectedScope = "local" // "local" vs "internet"
    private var typeRadios: List<RadioButton> = emptyList()
    private var scopeRadios: List<RadioButton> = emptyList()
    private val fileChecks = linkedMapOf<String, CheckBox>()
    private val metricViews = linkedMapOf<String, TextView>()
    private var dashboardBuilt = false
    private var dashboardBadge: TextView? = null
    private var dashboardScopeBadge: TextView? = null
    private var dashboardTitle: TextView? = null
    private var dashboardAddressLan: TextView? = null
    private var dashboardAddressWan: TextView? = null
    private var wanAddressBox: View? = null
    private var dashboardAction: Button? = null
    private var dashboardQrBtn: Button? = null
    private var dashboardLog: TextView? = null
    private var dashboardProfile: TextView? = null
    private val filters = linkedSetOf<String>()

    private val isTablet: Boolean
        get() = resources.configuration.smallestScreenWidthDp >= 600 || resources.configuration.screenWidthDp >= 640

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? ServerService.LocalBinder)?.service()
            renderScreen()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            renderScreen()
        }
    }

    private val refreshTask = object : Runnable {
        override fun run() {
            if (screen == 0 && ::body.isInitialized) updateDashboard()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        selectedType = prefs.getString("type", "web") ?: "web"
        selectedScope = prefs.getString("network_scope", "local") ?: "local"
        filters.addAll(prefs.getStringSet("filters", setOf("all")) ?: setOf("all"))
        buildShell()
        applyKeepScreenOn()
        renderScreen()
        handler.post(refreshTask)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, ServerService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) unbindService(connection)
        bound = false
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        super.onDestroy()
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = if (isTablet) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (isTablet) Gravity.CENTER_VERTICAL else Gravity.START
            val hPad = if (isTablet) dp(32) else dp(20)
            val vPadTop = if (isTablet) dp(24) else dp(18)
            setPadding(hPad, vPadTop, hPad, dp(14))
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (isTablet) layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tagLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedDrawable(panelAlt, panelBorder, dp(1), dp(16).toFloat())
            setPadding(dp(10), dp(4), dp(12), dp(4))
        }
        val tagDot = View(this).apply {
            background = roundedDrawable(mint, 0, 0, dp(4).toFloat())
        }
        tagLayout.addView(tagDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(6) })
        tagLayout.addView(label("AND SERVER", 11, mint, bold = true))
        titleContainer.addView(tagLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val appTitle = label("Central de Servidor", if (isTablet) 28 else 24, textPrimary, bold = true).apply {
            setPadding(0, dp(6), 0, dp(2))
        }
        val appSubtitle = label("Servidor HTTPS local e internet com proteção anti-força bruta", 13, textMuted)
        titleContainer.addView(appTitle)
        titleContainer.addView(appSubtitle)
        header.addView(titleContainer)

        val navWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedDrawable(panel, panelBorder, dp(1), dp(14).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val navTitles = listOf("Visão geral", "Configurar", "Ajustes")
        navButtons = navTitles.mapIndexed { index, title ->
            Button(this).apply {
                text = title
                textSize = if (isTablet) 13f else 12f
                isAllCaps = false
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setOnClickListener {
                    screen = index
                    renderScreen()
                }
            }.also { btn ->
                val p = LinearLayout.LayoutParams(
                    if (isTablet) dp(140) else 0,
                    dp(40),
                    if (isTablet) 0f else 1f
                ).apply {
                    if (index > 0) leftMargin = dp(3)
                }
                navWrapper.addView(btn, p)
            }
        }

        if (isTablet) {
            header.addView(navWrapper)
            root.addView(header)
        } else {
            root.addView(header)
            val navContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(18), 0, dp(18), dp(12))
            }
            navContainer.addView(navWrapper, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(navContainer)
        }

        val divider = View(this).apply { setBackgroundColor(panelBorder) }
        root.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))

        val scroll = ScrollView(this).apply {
            fillViewport = true
            isVerticalScrollBarEnabled = false
        }

        val scrollInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val contentMaxWidth = if (isTablet) dp(980) else ViewGroup.LayoutParams.MATCH_PARENT
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = if (isTablet) dp(32) else dp(18)
            val padV = if (isTablet) dp(20) else dp(14)
            setPadding(padH, padV, padH, dp(36))
        }

        scrollInner.addView(body, LinearLayout.LayoutParams(contentMaxWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.addView(scrollInner, LinearLayout.LayoutParams(-1, -1))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    private fun renderScreen() {
        if (!::body.isInitialized) return
        navButtons.forEachIndexed { i, b ->
            val isSelected = i == screen
            b.setTextColor(if (isSelected) bg else textMuted)
            b.background = if (isSelected) {
                roundedDrawable(mint, 0, 0, dp(10).toFloat())
            } else {
                roundedDrawable(Color.TRANSPARENT, 0, 0, dp(10).toFloat())
            }
        }

        body.removeAllViews()
        dashboardBuilt = false
        metricViews.clear()

        when (screen) {
            0 -> renderDashboard()
            1 -> renderSetup()
            else -> renderSettings()
        }
    }

    private fun renderDashboard() {
        body.removeAllViews()
        metricViews.clear()

        val snapshot = service?.snapshot()
        val port = snapshot?.port
        val active = snapshot?.running == true && port != null

        if (isTablet) {
            val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            val leftCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            leftCol.addView(buildServerControlCard(active, port))

            val infoCard = card().apply {
                addView(label("PERFIL, REDE E SEGURANÇA", 11, textSubtle, bold = true))
                val configName = serverTypeName(prefs.getString("type", "web") ?: "web")
                val scopeName = if (selectedScope == "internet") "Toda a Internet (Público)" else "Rede Local (LAN)"
                dashboardProfile = label(
                    "Perfil: $configName\nModo de Rede: $scopeName\nPorta: ${prefs.getInt("port", ServerService.DEFAULT_TLS_PORT)}\nProtocolo: HTTPS / TLS 1.3\nEscudo Força Bruta: Ativo (Auto-Ban)\nSegurança: ${if (prefs.getString("password", "").isNullOrEmpty()) "Sem senha (Acesso livre)" else "HTTP Basic ativa"}",
                    13, textMuted
                ).apply {
                    setPadding(0, dp(8), 0, 0)
                    setLineSpacing(dp(4).toFloat(), 1f)
                }
                addView(dashboardProfile)
            }
            leftCol.addView(infoCard, marginTop(14))

            val rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            rightCol.addView(buildMetricsSection(active, snapshot))
            rightCol.addView(buildLogsSection(snapshot), marginTop(14))

            columns.addView(leftCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
            columns.addView(View(this), LinearLayout.LayoutParams(dp(18), 1))
            columns.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            body.addView(columns)
        } else {
            body.addView(buildServerControlCard(active, port))
            body.addView(buildMetricsSection(active, snapshot), marginTop(16))
            body.addView(buildLogsSection(snapshot), marginTop(16))

            dashboardProfile = label("", 12, textSubtle).apply {
                setPadding(dp(4), dp(14), dp(4), 0)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            body.addView(dashboardProfile)
        }

        dashboardBuilt = true
        updateDashboard()
    }

    private fun buildServerControlCard(active: Boolean, port: Int?): View {
        return card().apply {
            val statusHeader = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            dashboardBadge = TextView(this@MainActivity).apply {
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }
            statusHeader.addView(dashboardBadge)

            dashboardScopeBadge = TextView(this@MainActivity).apply {
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }
            statusHeader.addView(dashboardScopeBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })

            val spacer = View(this@MainActivity)
            statusHeader.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

            val secureChip = label("🔒 TLS Autoassinado", 11, textSubtle)
            statusHeader.addView(secureChip)
            addView(statusHeader)

            dashboardTitle = label("", if (isTablet) 20 else 18, textPrimary, bold = true).apply {
                setPadding(0, dp(12), 0, dp(4))
            }
            addView(dashboardTitle)

            // Caixa de Endereço Local (LAN)
            val lanBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(panelAlt, panelBorder, dp(1), dp(12).toFloat())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val url = dashboardAddressLan?.tag as? String
                    if (!url.isNullOrEmpty()) {
                        copyToClipboard("Endereço Local", url)
                    }
                }
            }
            lanBox.addView(label("ENDEREÇO NA REDE LOCAL (TOQUE P/ COPIAR)", 10, textSubtle, bold = true))
            dashboardAddressLan = TextView(this@MainActivity).apply {
                textSize = if (isTablet) 15f else 14f
                setTextColor(Color.WHITE)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(5), 0, 0)
                setTextIsSelectable(true)
            }
            lanBox.addView(dashboardAddressLan)
            addView(lanBox, marginTop(10))

            // Caixa de Endereço Público / WAN (exibida quando no modo Internet)
            val wanBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(panelAlt, panelBorder, dp(1), dp(12).toFloat())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val url = dashboardAddressWan?.tag as? String
                    if (!url.isNullOrEmpty()) {
                        copyToClipboard("Endereço Internet", url)
                    }
                }
            }
            wanBox.addView(label("ENDEREÇO NA INTERNET (GLOBAL / WAN)", 10, skyBlue, bold = true))
            dashboardAddressWan = TextView(this@MainActivity).apply {
                textSize = if (isTablet) 15f else 14f
                setTextColor(Color.WHITE)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(5), 0, 0)
                setTextIsSelectable(true)
            }
            wanBox.addView(dashboardAddressWan)
            wanAddressBox = wanBox
            addView(wanBox, marginTop(8))

            // Linha com Botões: Iniciar/Parar e Mostrar QR Code
            val actionRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            dashboardAction = Button(this@MainActivity).apply {
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setOnClickListener {
                    if (service?.snapshot()?.running == true) stopServer() else startServer()
                }
            }
            actionRow.addView(dashboardAction, LinearLayout.LayoutParams(0, dp(48), 1.2f))

            dashboardQrBtn = Button(this@MainActivity).apply {
                text = "📱 QR CODE"
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(textPrimary)
                background = roundedDrawable(panelAlt, panelBorder, dp(1), dp(12).toFloat())
                setOnClickListener {
                    val currentUrl = dashboardAddressLan?.tag as? String ?: ""
                    if (currentUrl.isNotEmpty()) {
                        showQrDialog(currentUrl)
                    } else {
                        Toast.makeText(this@MainActivity, "Inicie o servidor para exibir o QR Code", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            actionRow.addView(dashboardQrBtn, LinearLayout.LayoutParams(0, dp(48), 0.8f).apply { leftMargin = dp(8) })

            addView(actionRow, marginTop(14))
        }
    }

    private fun showQrDialog(url: String) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedDrawable(panel, panelBorder, dp(1), dp(20).toFloat())
            val pad = dp(24)
            setPadding(pad, pad, pad, pad)
        }

        view.addView(label("Conectar via QR Code", 18, textPrimary, bold = true))
        view.addView(label("Aponte a câmera de outro celular, tablet ou PC:", 13, textMuted).apply {
            setPadding(0, dp(4), 0, dp(16))
            gravity = Gravity.CENTER_HORIZONTAL
        })

        val matrix = QrGenerator.encode(url)
        val bitmap = QrGenerator.toBitmap(matrix, dp(240))
        val qrImage = ImageView(this).apply {
            setImageBitmap(bitmap)
            background = roundedDrawable(Color.WHITE, 0, 0, dp(12).toFloat())
            val p = dp(10)
            setPadding(p, p, p, p)
        }
        view.addView(qrImage, LinearLayout.LayoutParams(dp(250), dp(250)))

        val urlText = TextView(this).apply {
            text = url
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(mint)
            setPadding(0, dp(14), 0, dp(14))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        view.addView(urlText)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val copyBtn = Button(this).apply {
            text = "COPIAR LINK"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(bg)
            background = roundedDrawable(mint, 0, 0, dp(10).toFloat())
            setOnClickListener {
                copyToClipboard("Link do Servidor", url)
            }
        }
        btnRow.addView(copyBtn, LinearLayout.LayoutParams(0, dp(42), 1f))

        val closeBtn = Button(this).apply {
            text = "FECHAR"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(textPrimary)
            background = roundedDrawable(panelAlt, panelBorder, dp(1), dp(10).toFloat())
            setOnClickListener { dialog.dismiss() }
        }
        btnRow.addView(closeBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
        view.addView(btnRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copiado para a área de transferência!", Toast.LENGTH_SHORT).show()
    }

    private fun buildMetricsSection(active: Boolean, snapshot: ServerSnapshot?): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(label("ATIVIDADE DO SERVIDOR", 11, mint, bold = true).apply { setPadding(dp(2), 0, 0, dp(8)) })

        val statsCard = card()
        val uptime = if (active) duration(System.currentTimeMillis() - (snapshot?.startedAt ?: 0)) else "—"

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(metric("REQUISIÇÕES", snapshot?.requests?.toString() ?: "0"), LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(metric("CONEXÕES ATIVAS", snapshot?.clients?.toString() ?: "0"), LinearLayout.LayoutParams(0, -2, 1f))
        statsCard.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        row2.addView(metric("ARQUIVOS HOSPEDADOS", snapshot?.files?.toString() ?: "0"), LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(metric("TEMPO ATIVO", uptime), LinearLayout.LayoutParams(0, -2, 1f))
        statsCard.addView(row2)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        row3.addView(metric("DADOS RECEBIDOS", formatBytes(snapshot?.bytesReceived ?: 0)), LinearLayout.LayoutParams(0, -2, 1f))
        row3.addView(metric("DADOS ENVIADOS", formatBytes(snapshot?.bytesSent ?: 0)), LinearLayout.LayoutParams(0, -2, 1f))
        statsCard.addView(row3)

        container.addView(statsCard)
        return container
    }

    private fun buildLogsSection(snapshot: ServerSnapshot?): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(label("REGISTRO DE EVENTOS RECENTES", 11, mint, bold = true).apply { setPadding(dp(2), 0, 0, dp(8)) })

        val logCard = card().apply {
            val logWrapper = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(panelAlt, panelBorder, dp(1), dp(10).toFloat())
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            dashboardLog = TextView(this@MainActivity).apply {
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(textMuted)
                setLineSpacing(dp(3).toFloat(), 1f)
            }
            logWrapper.addView(dashboardLog)
            addView(logWrapper)
        }
        container.addView(logCard)
        return container
    }

    private fun updateDashboard() {
        val snapshot = service?.snapshot()
        val port = snapshot?.port
        val active = snapshot?.running == true && port != null
        val isInternet = selectedScope == "internet"

        dashboardBadge?.apply {
            text = if (active) "●  ONLINE" else "○  PARADO"
            setTextColor(if (active) mint else amber)
            background = roundedDrawable(if (active) mintDark else amberDark, 0, 0, dp(12).toFloat())
        }

        dashboardScopeBadge?.apply {
            text = if (isInternet) "🌍 TODA A INTERNET" else "🏠 REDE LOCAL"
            setTextColor(if (isInternet) skyBlue else textMuted)
            background = roundedDrawable(if (isInternet) skyBlueDark else panelAlt, 0, 0, dp(12).toFloat())
        }

        dashboardTitle?.text = if (active) {
            if (isInternet) "Servidor ativo para toda a Internet" else "Servidor ativo na Rede Local"
        } else {
            "Servidor parado"
        }

        val lan = lanAddress()
        val lanUrl = if (active) "https://$lan:$port" else ""
        dashboardAddressLan?.apply {
            text = if (active) "$lanUrl\nToque para copiar o endereço local" else "Inicie o servidor para liberar o endereço local."
            tag = lanUrl
        }

        wanAddressBox?.visibility = if (isInternet) View.VISIBLE else View.GONE
        if (isInternet) {
            val publicIp = snapshot?.publicIp
            val wanUrl = if (active && !publicIp.isNullOrEmpty()) "https://$publicIp:$port" else ""
            dashboardAddressWan?.apply {
                text = if (active) {
                    if (!publicIp.isNullOrEmpty()) "$wanUrl\nToque para copiar o endereço público" else "Consultando IP público da conexão..."
                } else {
                    "Inicie o servidor para expor à internet."
                }
                tag = wanUrl
            }
        }

        dashboardAction?.apply {
            text = if (active) "PARAR SERVIDOR" else "INICIAR SERVIDOR"
            setTextColor(if (active) Color.WHITE else bg)
            background = roundedDrawable(
                if (active) Color.rgb(185, 28, 28) else mint,
                0, 0, dp(12).toFloat()
            )
        }

        metricViews["REQUISIÇÕES"]?.text = snapshot?.requests?.toString() ?: "0"
        metricViews["CONEXÕES ATIVAS"]?.text = snapshot?.clients?.toString() ?: "0"
        metricViews["ARQUIVOS HOSPEDADOS"]?.text = snapshot?.files?.toString() ?: "0"
        metricViews["TEMPO ATIVO"]?.text = if (active) duration(System.currentTimeMillis() - (snapshot?.startedAt ?: 0)) else "—"
        metricViews["DADOS RECEBIDOS"]?.text = formatBytes(snapshot?.bytesReceived ?: 0)
        metricViews["DADOS ENVIADOS"]?.text = formatBytes(snapshot?.bytesSent ?: 0)

        val events = snapshot?.recentEvents.orEmpty()
        dashboardLog?.text = if (events.isEmpty()) {
            "Nenhuma requisição recebida ainda. O registro de conexões aparecerá aqui."
        } else {
            events.take(if (isTablet) 10 else 6).joinToString("\n\n")
        }

        val configName = serverTypeName(prefs.getString("type", "web") ?: "web")
        dashboardProfile?.text = "Perfil: $configName · Modo: ${if (isInternet) "Internet" else "Local"} · Porta ${prefs.getInt("port", ServerService.DEFAULT_TLS_PORT)} · ${if (prefs.getString("password", "").isNullOrEmpty()) "Sem senha" else "Com senha"} · HTTPS"
    }

    private fun renderSetup() {
        body.addView(label("CONFIGURAÇÃO DO SERVIDOR", 11, mint, bold = true).apply { setPadding(dp(2), 0, 0, dp(4)) })
        body.addView(label("Escolha o alcance de rede, perfil do servidor e parâmetros de segurança.", 14, textMuted).apply {
            setPadding(dp(2), 0, 0, dp(16))
        })

        // 1. ESCOPO DE REDE: LOCAL VS. TODA A INTERNET
        body.addView(label("ALCANCE DE REDE (LOCAL VS. INTERNET)", 11, textSubtle, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
        val scopes = listOf(
            Triple("local", "🏠 Apenas Rede Local (LAN)", "Conexões aceitas apenas de dispositivos na mesma Wi-Fi. Conexões externas são bloqueadas pelo firewall."),
            Triple("internet", "🌍 Toda a Internet (Público / WAN)", "Acessível de qualquer lugar do mundo. Mostra seu IP público e ativa o escudo anti-força bruta.")
        )
        scopeRadios = scopes.map { (id, title, desc) ->
            buildScopeCard(id, title, desc).also { body.addView(it, marginTop(6)) }
        }

        val types = listOf(
            Triple("web", "Servidor Web Completo", "Página inicial, upload/download e endpoints HTTP"),
            Triple("files", "Servidor de Arquivos", "Compartilhamento, listagem e transferência de arquivos"),
            Triple("api", "Servidor de API JSON", "Rotas REST, métricas e administração remota")
        )

        if (isTablet) {
            val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val leftCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            leftCol.addView(label("PERFIL DE OPERAÇÃO", 11, textSubtle, bold = true).apply { setPadding(0, dp(16), 0, dp(8)) })
            typeRadios = types.map { (id, title, desc) ->
                buildProfileCard(id, title, desc).also { leftCol.addView(it, marginTop(8)) }
            }

            rightCol.addView(label("FORMATOS DE ARQUIVO PERMITIDOS", 11, textSubtle, bold = true).apply { setPadding(0, dp(16), 0, dp(8)) })
            buildFileFilters(rightCol)

            columns.addView(leftCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
            columns.addView(View(this), LinearLayout.LayoutParams(dp(20), 1))
            columns.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            body.addView(columns)
        } else {
            body.addView(label("PERFIL DE OPERAÇÃO", 11, textSubtle, bold = true).apply { setPadding(0, dp(16), 0, dp(6)) })
            typeRadios = types.map { (id, title, desc) ->
                buildProfileCard(id, title, desc).also { body.addView(it, marginTop(8)) }
            }

            body.addView(label("FORMATOS DE ARQUIVO PERMITIDOS", 11, mint, bold = true).apply { setPadding(0, dp(22), 0, dp(6)) })
            buildFileFilters(body)
        }

        // Seção de Porta e Senha
        val networkCard = card().apply {
            addView(label("PARÂMETROS DE REDE E SENHA", 11, textSubtle, bold = true))

            addView(label("Porta HTTPS (1024 a 65535)", 13, textPrimary, bold = true).apply { setPadding(0, dp(12), 0, dp(6)) })
            val portInput = EditText(this@MainActivity).apply {
                setText(prefs.getInt("port", ServerService.DEFAULT_TLS_PORT).toString())
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                setTextColor(textPrimary)
                textSize = 15f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundedDrawable(panelAlt, panelBorder, dp(1), dp(10).toFloat())
            }
            addView(portInput, LinearLayout.LayoutParams(-1, dp(48)))

            addView(label("Senha de Acesso (HTTP Basic - Recomendada para Internet)", 13, textPrimary, bold = true).apply { setPadding(0, dp(14), 0, dp(4)) })
            addView(label("Deixe em branco para permitir acesso livre sem autenticação aos clientes da rede.", 12, textSubtle).apply { setPadding(0, 0, 0, dp(6)) })
            val passwordInput = EditText(this@MainActivity).apply {
                hint = "Mínimo 8 caracteres (ou em branco)"
                setText(prefs.getString("password", ""))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                setTextColor(textPrimary)
                setHintTextColor(textSubtle)
                textSize = 15f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundedDrawable(panelAlt, panelBorder, dp(1), dp(10).toFloat())
            }
            addView(passwordInput, LinearLayout.LayoutParams(-1, dp(48)))

            val saveBtn = Button(this@MainActivity).apply {
                text = "SALVAR E APLICAR CONFIGURAÇÃO"
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(bg)
                background = roundedDrawable(mint, 0, 0, dp(10).toFloat())
                setOnClickListener {
                    val value = portInput.text.toString().toIntOrNull()
                    if (value == null || value !in 1024..65535) {
                        portInput.error = "Use uma porta válida entre 1024 e 65535"
                        return@setOnClickListener
                    }
                    if (passwordInput.text.isNotEmpty() && passwordInput.text.length < 8) {
                        passwordInput.error = "A senha deve ter pelo menos 8 caracteres ou ficar em branco"
                        return@setOnClickListener
                    }
                    prefs.edit()
                        .putString("type", selectedType)
                        .putString("network_scope", selectedScope)
                        .putStringSet("filters", filters.toSet())
                        .putInt("port", value)
                        .putString("password", passwordInput.text.toString())
                        .apply()

                    Toast.makeText(this@MainActivity, "Configuração atualizada com sucesso!", Toast.LENGTH_SHORT).show()
                    if (service?.snapshot()?.running == true) startServer()
                    screen = 0
                    renderScreen()
                }
            }
            addView(saveBtn, marginTop(18))
        }

        body.addView(networkCard, marginTop(16))
    }

    private fun buildScopeCard(id: String, title: String, desc: String): RadioButton {
        val radio = RadioButton(this).apply {
            text = "$title\n$desc"
            textSize = 13f
            setTextColor(textPrimary)
            buttonTintList = ColorStateList.valueOf(if (id == "internet") skyBlue else mint)
            isChecked = selectedScope == id
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedDrawable(panel, if (selectedScope == id) (if (id == "internet") skyBlue else mint) else panelBorder, dp(1), dp(12).toFloat())
        }
        radio.setOnClickListener {
            selectedScope = id
            scopeRadios.forEach { item ->
                val checked = item == radio
                item.isChecked = checked
                item.background = roundedDrawable(panel, if (checked) (if (id == "internet") skyBlue else mint) else panelBorder, dp(1), dp(12).toFloat())
            }
        }
        return radio
    }

    private fun buildProfileCard(id: String, title: String, desc: String): RadioButton {
        val radio = RadioButton(this).apply {
            text = "$title\n$desc"
            textSize = 13f
            setTextColor(textPrimary)
            buttonTintList = ColorStateList.valueOf(mint)
            isChecked = selectedType == id
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedDrawable(panel, if (selectedType == id) mint else panelBorder, dp(1), dp(12).toFloat())
        }
        radio.setOnClickListener {
            selectedType = id
            typeRadios.forEach { item ->
                val checked = item == radio
                item.isChecked = checked
                item.background = roundedDrawable(panel, if (checked) mint else panelBorder, dp(1), dp(12).toFloat())
            }
        }
        return radio
    }

    private fun buildFileFilters(container: ViewGroup) {
        val choices = listOf(
            "all" to "Todos os tipos de arquivo",
            "images" to "Imagens (JPG, PNG, GIF, WebP, SVG)",
            "documents" to "Documentos (PDF, DOCX, TXT, HTML)",
            "audio" to "Músicas e Áudio (MP3, WAV, AAC, FLAC)",
            "video" to "Vídeos (MP4, MKV, WebM, AVI)",
            "archives" to "Arquivos compactados (ZIP, RAR, 7Z, TAR)"
        )
        fileChecks.clear()
        choices.forEach { (id, title) ->
            val filterCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedDrawable(panel, panelBorder, dp(1), dp(10).toFloat())
                setPadding(dp(10), dp(6), dp(10), dp(6))

                val check = CheckBox(this@MainActivity).apply {
                    text = title
                    textSize = 13f
                    setTextColor(textPrimary)
                    buttonTintList = ColorStateList.valueOf(mint)
                    isChecked = id in filters
                    setOnCheckedChangeListener { _, checked -> onFilterChanged(id, checked) }
                }
                fileChecks[id] = check
                addView(check)
            }
            container.addView(filterCard, marginTop(6))
        }
    }

    private fun renderSettings() {
        body.addView(label("AJUSTES E RECURSOS DO SISTEMA", 11, mint, bold = true).apply { setPadding(dp(2), 0, 0, dp(4)) })
        body.addView(label("Otimize o desempenho, conexões e aproveite as 5 novas funcionalidades integradas.", 14, textMuted).apply {
            setPadding(dp(2), 0, 0, dp(16))
        })

        // Cartão das 5 Novas Features
        val featuresCard = card().apply {
            addView(label("✨ 5 NOVAS FEATURES INTEGRADAS", 11, skyBlue, bold = true))
            val items = listOf(
                "📱 QR Code de Conexão Rápida" to "Geração dinâmica de QR Code no app e no painel web para escanear e conectar sem digitar IP.",
                "🛡️ Escudo Anti-Força Bruta" to "Bloqueio automático temporário de 5 minutos para IPs que falharem 5 tentativas de senha sucessivas.",
                "📦 Download em Lote ZIP" to "Compactação e download de pastas completas e múltiplos arquivos via streaming ZIP em tempo real.",
                "🔗 Links Temporários com Expiração" to "Gere links de download avulsos válidos por 1h até 7 dias sem precisar revelar a senha mestra.",
                "🎬 Streaming & Player In-Browser" to "Visualizador lightbox de fotos e player integrado de áudio e vídeo direto no navegador."
            )
            items.forEach { (title, desc) ->
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(10), 0, 0)
                    addView(label(title, 13, textPrimary, bold = true))
                    addView(label(desc, 12, textMuted).apply { setPadding(0, dp(2), 0, 0) })
                }
                addView(row)
            }
        }
        body.addView(featuresCard)

        val content = if (isTablet) {
            val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val leftCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            leftCol.addView(buildWorkerCard())
            leftCol.addView(buildScreenCard(), marginTop(12))

            rightCol.addView(buildStorageCard())

            cols.addView(leftCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            cols.addView(View(this), LinearLayout.LayoutParams(dp(18), 1))
            cols.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            cols
        } else {
            val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            stack.addView(buildWorkerCard())
            stack.addView(buildScreenCard(), marginTop(12))
            stack.addView(buildStorageCard(), marginTop(12))
            stack
        }
        body.addView(content, marginTop(14))

        val footer = label(
            "AndServer · Perfil: ${serverTypeName(selectedType)} · Modo: ${if (selectedScope == "internet") "Internet (WAN)" else "Rede Local (LAN)"} · Porta ${prefs.getInt("port", ServerService.DEFAULT_TLS_PORT)}",
            12, textSubtle
        ).apply {
            setPadding(dp(4), dp(20), dp(4), 0)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        body.addView(footer)
    }

    private fun buildWorkerCard(): View = card().apply {
        addView(label("CONEXÕES SIMULTÂNEAS (WORKERS)", 13, textPrimary, bold = true))
        addView(label("Define quantos clientes podem transferir dados ao mesmo tempo. Valores maiores usam mais memória e CPU.", 12, textMuted).apply {
            setPadding(0, dp(4), 0, dp(10))
        })
        val spinner = Spinner(this@MainActivity).apply {
            background = roundedDrawable(panelAlt, panelBorder, dp(1), dp(8).toFloat())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val options = listOf(2, 4, 8, 12, 16)
        spinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options.map { "$it conexões simultâneas" })
        spinner.setSelection(options.indexOf(prefs.getInt("workers", 8)).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val changed = prefs.getInt("workers", 8) != options[position]
                prefs.edit().putInt("workers", options[position]).apply()
                if (changed && service?.snapshot()?.running == true) startServer(showDashboard = false)
            }
        }
        addView(spinner)
    }

    private fun buildScreenCard(): View = card().apply {
        addView(label("MANTER TELA LIGADA", 13, textPrimary, bold = true))
        addView(label("Impede que o Android suspenda a rede Wi-Fi durante transferências longas ou apresentações.", 12, textMuted).apply {
            setPadding(0, dp(4), 0, dp(8))
        })
        val switch = Switch(this@MainActivity).apply {
            text = "Evitar modo de repouso"
            setTextColor(textPrimary)
            isChecked = prefs.getBoolean("keep_screen", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("keep_screen", checked).apply()
                applyKeepScreenOn()
            }
        }
        addView(switch)
    }

    private fun buildStorageCard(): View = card().apply {
        addView(label("ARMAZENAMENTO DE ARQUIVOS", 13, textPrimary, bold = true))
        addView(label("Os arquivos enviados pela interface web ficam guardados no espaço privado seguro do aplicativo.", 12, textMuted).apply {
            setPadding(0, dp(4), 0, dp(12))
        })
        val clearBtn = Button(this@MainActivity).apply {
            text = "LIMPAR ARQUIVOS HOSPEDADOS"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedDrawable(Color.rgb(127, 29, 29), 0, 0, dp(8).toFloat())
            setOnClickListener {
                val dir = java.io.File(filesDir, "hosted")
                val count = dir.listFiles()?.count { it.walkTopDown().count { child -> child.isFile } > 0 && it.deleteRecursively() } ?: 0
                Toast.makeText(this@MainActivity, "$count item(ns) removido(s)", Toast.LENGTH_SHORT).show()
                renderScreen()
            }
        }
        addView(clearBtn)
    }

    private fun startServer(showDashboard: Boolean = true) {
        val port = prefs.getInt("port", ServerService.DEFAULT_TLS_PORT)
        val intent = Intent(this, ServerService::class.java).setAction(ServerService.ACTION_START)
            .putExtra(ServerService.EXTRA_PORT, port)
            .putExtra(ServerService.EXTRA_TYPE, prefs.getString("type", "web"))
            .putExtra(ServerService.EXTRA_NETWORK_SCOPE, prefs.getString("network_scope", "local"))
            .putStringArrayListExtra(ServerService.EXTRA_FILTERS, ArrayList(prefs.getStringSet("filters", setOf("all")) ?: setOf("all")))
            .putExtra(ServerService.EXTRA_WORKERS, prefs.getInt("workers", 8))
            .putExtra(ServerService.EXTRA_PASSWORD, prefs.getString("password", ""))

        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            if (showDashboard) {
                screen = 0
                renderScreen()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível iniciar: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopServer() {
        startService(Intent(this, ServerService::class.java).setAction(ServerService.ACTION_STOP))
        renderScreen()
    }

    private fun onFilterChanged(id: String, checked: Boolean) {
        if (id == "all" && checked) {
            filters.clear()
            filters.add("all")
        } else if (id == "all") {
            filters.remove("all")
        } else {
            if (checked) {
                filters.remove("all")
                filters.add(id)
            } else {
                filters.remove(id)
            }
        }
        fileChecks.forEach { (key, box) ->
            box.setOnCheckedChangeListener(null)
            box.isChecked = key in filters
            box.setOnCheckedChangeListener { _, value -> onFilterChanged(key, value) }
        }
    }

    private fun applyKeepScreenOn() {
        if (prefs.getBoolean("keep_screen", false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun lanAddress(): String {
        try {
            val addresses = NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
            return (addresses.firstOrNull { it.hostAddress?.startsWith("192.168.") == true }
                ?: addresses.firstOrNull { it.hostAddress?.startsWith("10.") == true }
                ?: addresses.firstOrNull())?.hostAddress ?: "IP indisponível"
        } catch (_: Exception) {
            return "IP indisponível"
        }
    }

    private fun duration(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun serverTypeName(type: String) = when (type) {
        "files" -> "Servidor de arquivos"
        "api" -> "Servidor de API"
        else -> "Servidor web"
    }

    private fun metric(caption: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedDrawable(panelAlt, panelBorder, dp(1), dp(10).toFloat())
        val padH = if (isTablet) dp(14) else dp(10)
        val padV = if (isTablet) dp(12) else dp(10)
        setPadding(padH, padV, padH, padV)

        addView(label(caption, 10, textSubtle, bold = true))
        val valueView = label(value, if (isTablet) 20 else 18, textPrimary, bold = true).apply {
            setPadding(0, dp(4), 0, 0)
        }
        metricViews[caption] = valueView
        addView(valueView)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padH = if (isTablet) dp(22) else dp(16)
        val padV = if (isTablet) dp(20) else dp(16)
        setPadding(padH, padV, padH, padV)
        background = roundedDrawable(panel, panelBorder, dp(1), dp(16).toFloat())
    }

    private fun roundedDrawable(bgColor: Int, strokeColor: Int = 0, strokeWidth: Int = 0, radius: Float = dp(14).toFloat()): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = radius
            if (strokeWidth > 0 && strokeColor != 0) {
                setStroke(strokeWidth, strokeColor)
            }
        }
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD), Typeface.BOLD)
    }

    private fun marginTop(v: Int) = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(v)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
