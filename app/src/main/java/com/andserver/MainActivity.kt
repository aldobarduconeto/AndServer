package com.andserver

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.io.File

/**
 * Atividade principal e Shell visual do AndServer.
 * Responsável pela coordenação de janelas edge-to-edge, navegação em abas responsivas
 * (layout para telefones e tablets), vinculação com ServerService e despacho das telas.
 */
class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("andserver", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var body: LinearLayout
    private lateinit var navButtons: List<Button>
    private var screen = 0
    private var service: ServerService? = null
    private var bound = false

    private var selectedType = "web"
    private var selectedScope = "local"
    private val filters = linkedSetOf<String>()

    private lateinit var dashboardView: DashboardView
    private lateinit var setupView: SetupView
    private lateinit var settingsView: SettingsView
    private lateinit var updatesView: UpdatesView

    private var releaseReceiverRegistered = false
    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirmation != null) startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Toast.makeText(this@MainActivity, "Atualização instalada.", Toast.LENGTH_LONG).show()
                else -> Toast.makeText(this@MainActivity, "Instalação não concluída: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "verifique a assinatura do APK"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val isTablet: Boolean
        get() = resources.configuration.smallestScreenWidthDp >= 600 || resources.configuration.screenWidthDp >= 640

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? ServerService.LocalBinder)?.service()
            bound = true
            renderScreen()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            renderScreen()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (screen == 0) {
                dashboardView.update(service?.snapshot(), selectedScope, selectedType, isTablet)
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selectedType = prefs.getString("type", "web") ?: "web"
        selectedScope = prefs.getString("network_scope", "local") ?: "local"
        val savedFilters = prefs.getStringSet("filters", null)
        filters.clear()
        if (savedFilters.isNullOrEmpty()) filters.add("all") else filters.addAll(savedFilters)

        // Inicialização dos módulos de telas
        dashboardView = DashboardView(this)
        setupView = SetupView(this) {
            if (service?.snapshot()?.running == true) startServer()
            screen = 0
            renderScreen()
        }
        settingsView = SettingsView(
            this,
            onRestartServer = { showDashboard -> startServer(showDashboard) },
            onRefreshScreen = { renderScreen() }
        )
        updatesView = UpdatesView(this) { filter -> registerInstallReceiver(filter) }

        applyKeepScreenOn()
        val loc = prefs.getString("storage_location", "internal") ?: "internal"
        val activeBase = if (loc == "external") (getExternalFilesDir(null) ?: filesDir) else filesDir
        SiteTemplateManager.ensureSiteTemplate(File(activeBase, "hosted"))
        buildShell()
        renderScreen()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ServerService::class.java), connection, Context.BIND_AUTO_CREATE)
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    override fun onDestroy() {
        if (releaseReceiverRegistered) {
            try { unregisterReceiver(installReceiver) } catch (_: Exception) { }
            releaseReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiTheme.bg)
            fitsSystemWindows = false
        }

        val baseHeaderPadH = if (isTablet) UiTheme.dp(this, 32) else UiTheme.dp(this, 20)
        val baseHeaderPadTop = if (isTablet) UiTheme.dp(this, 24) else UiTheme.dp(this, 18)
        val baseBodyPadH = if (isTablet) UiTheme.dp(this, 32) else UiTheme.dp(this, 18)
        val baseBodyPadV = if (isTablet) UiTheme.dp(this, 20) else UiTheme.dp(this, 14)

        val header = LinearLayout(this).apply {
            orientation = if (isTablet) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (isTablet) Gravity.CENTER_VERTICAL else Gravity.START
            setPadding(baseHeaderPadH, baseHeaderPadTop, baseHeaderPadH, UiTheme.dp(this, 14))
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }

        val scrollInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val contentMaxWidth = if (isTablet) UiTheme.dp(this, 980) else ViewGroup.LayoutParams.MATCH_PARENT
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(baseBodyPadH, baseBodyPadV, baseBodyPadH, UiTheme.dp(this, 36))
            clipToPadding = false
        }

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { _, insets ->
                val safeInsets = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                header.setPadding(
                    baseHeaderPadH + safeInsets.left,
                    baseHeaderPadTop + safeInsets.top,
                    baseHeaderPadH + safeInsets.right,
                    UiTheme.dp(this, 14)
                )
                body.setPadding(
                    baseBodyPadH + safeInsets.left,
                    baseBodyPadV,
                    baseBodyPadH + safeInsets.right,
                    UiTheme.dp(this, 36) + safeInsets.bottom
                )
                insets
            }
        } else if (Build.VERSION.SDK_INT >= 28) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val cutout = insets.displayCutout
                val topSafe = maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0)
                val bottomSafe = maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0)
                val leftSafe = maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0)
                val rightSafe = maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0)
                header.setPadding(
                    baseHeaderPadH + leftSafe,
                    baseHeaderPadTop + topSafe,
                    baseHeaderPadH + rightSafe,
                    UiTheme.dp(this, 14)
                )
                body.setPadding(
                    baseBodyPadH + leftSafe,
                    baseBodyPadV,
                    baseBodyPadH + rightSafe,
                    UiTheme.dp(this, 36) + bottomSafe
                )
                insets
            }
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (isTablet) layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tagLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiTheme.roundedDrawable(this@MainActivity, UiTheme.panelAlt, UiTheme.panelBorder, UiTheme.dp(this@MainActivity, 1), UiTheme.dp(this@MainActivity, 16).toFloat())
            setPadding(UiTheme.dp(this@MainActivity, 8), UiTheme.dp(this@MainActivity, 4), UiTheme.dp(this@MainActivity, 12), UiTheme.dp(this@MainActivity, 4))
        }
        val appIcon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
        }
        tagLayout.addView(appIcon, LinearLayout.LayoutParams(UiTheme.dp(this, 18), UiTheme.dp(this, 18)).apply { rightMargin = UiTheme.dp(this@MainActivity, 6) })
        tagLayout.addView(UiTheme.label(this, "AND SERVER", 11, UiTheme.mint, bold = true))
        titleContainer.addView(tagLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val appTitle = UiTheme.label(this, "Central de Servidor", if (isTablet) 28 else 24, UiTheme.textPrimary, bold = true).apply {
            setPadding(0, UiTheme.dp(this@MainActivity, 6), 0, UiTheme.dp(this@MainActivity, 2))
        }
        val appSubtitle = UiTheme.label(this, "Servidor HTTPS local e internet com proteção anti-força bruta", 13, UiTheme.textMuted)
        titleContainer.addView(appTitle)
        titleContainer.addView(appSubtitle)
        header.addView(titleContainer)

        val navWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = UiTheme.roundedDrawable(this@MainActivity, UiTheme.panel, UiTheme.panelBorder, UiTheme.dp(this@MainActivity, 1), UiTheme.dp(this@MainActivity, 14).toFloat())
            setPadding(UiTheme.dp(this@MainActivity, 4), UiTheme.dp(this@MainActivity, 4), UiTheme.dp(this@MainActivity, 4), UiTheme.dp(this@MainActivity, 4))
        }

        val navTitles = listOf("Visão geral", "Configurar", "Atualizar", "Ajustes")
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
                    if (isTablet) UiTheme.dp(this, 140) else 0,
                    UiTheme.dp(this, 40),
                    if (isTablet) 0f else 1f
                ).apply {
                    if (index > 0) leftMargin = UiTheme.dp(this@MainActivity, 3)
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
                setPadding(UiTheme.dp(this@MainActivity, 18), 0, UiTheme.dp(this@MainActivity, 18), UiTheme.dp(this@MainActivity, 12))
            }
            navContainer.addView(navWrapper, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(navContainer)
        }

        val divider = View(this).apply { setBackgroundColor(UiTheme.panelBorder) }
        root.addView(divider, LinearLayout.LayoutParams(-1, UiTheme.dp(this, 1)))

        scrollInner.addView(body, LinearLayout.LayoutParams(contentMaxWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.addView(scrollInner, LinearLayout.LayoutParams(-1, -1))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    private fun renderScreen() {
        if (!::body.isInitialized) return
        navButtons.forEachIndexed { i, b ->
            val isSelected = i == screen
            b.setTextColor(if (isSelected) UiTheme.bg else UiTheme.textMuted)
            b.background = if (isSelected) {
                UiTheme.roundedDrawable(this, UiTheme.mint, 0, 0, UiTheme.dp(this, 10).toFloat())
            } else {
                UiTheme.roundedDrawable(this, Color.TRANSPARENT, 0, 0, UiTheme.dp(this, 10).toFloat())
            }
        }

        body.removeAllViews()

        when (screen) {
            0 -> dashboardView.render(
                body,
                isTablet,
                service?.snapshot(),
                selectedScope,
                onStartStop = {
                    if (service?.snapshot()?.running == true) stopServer() else startServer()
                }
            )
            1 -> setupView.render(
                body,
                isTablet,
                selectedScope,
                selectedType,
                filters,
                onScopeChanged = { selectedScope = it },
                onTypeChanged = { selectedType = it }
            )
            2 -> updatesView.render(body)
            else -> settingsView.render(
                body,
                isTablet,
                selectedType,
                selectedScope,
                onTypeChanged = { selectedType = it }
            )
        }

        if (screen == 0) {
            dashboardView.update(service?.snapshot(), selectedScope, selectedType, isTablet)
        }
    }

    fun startServer(showDashboard: Boolean = true) {
        val port = prefs.getInt("port", ServerService.DEFAULT_TLS_PORT)
        val storageLoc = prefs.getString("storage_location", "internal") ?: "internal"
        val intent = Intent(this, ServerService::class.java).setAction(ServerService.ACTION_START)
            .putExtra(ServerService.EXTRA_PORT, port)
            .putExtra(ServerService.EXTRA_TYPE, prefs.getString("type", "web"))
            .putExtra(ServerService.EXTRA_NETWORK_SCOPE, prefs.getString("network_scope", "local"))
            .putExtra(ServerService.EXTRA_STORAGE_LOCATION, storageLoc)
            .putStringArrayListExtra(ServerService.EXTRA_FILTERS, ArrayList(filters))
            .putExtra(ServerService.EXTRA_WORKERS, prefs.getInt("workers", Runtime.getRuntime().availableProcessors()))
            .putExtra(ServerService.EXTRA_PASSWORD, prefs.getString("password", ""))

        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        if (showDashboard) {
            screen = 0
            renderScreen()
        }
    }

    fun stopServer() {
        startService(Intent(this, ServerService::class.java).setAction(ServerService.ACTION_STOP))
    }

    private fun applyKeepScreenOn() {
        if (prefs.getBoolean("keep_screen", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerInstallReceiver(filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(installReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(installReceiver, filter)
        releaseReceiverRegistered = true
    }
}
