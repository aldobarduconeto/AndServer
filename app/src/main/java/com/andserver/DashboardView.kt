package com.andserver

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Visualização e lógica do Painel de Controle (Dashboard) principal do AndServer.
 * Exibe status online/offline, endereços LAN/WAN, métricas de tráfego em tempo real,
 * terminal de logs recentes e diálogo de conexão rápida via QR Code.
 */
class DashboardView(private val activity: Activity) {

    private val metricViews = linkedMapOf<String, TextView>()
    private var dashboardBadge: TextView? = null
    private var dashboardScopeBadge: TextView? = null
    private var dashboardPowerBadge: TextView? = null
    private var dashboardTitle: TextView? = null
    private var dashboardAddressLan: TextView? = null
    private var dashboardAddressWan: TextView? = null
    private var wanAddressBox: View? = null
    private var dashboardAction: Button? = null
    private var dashboardQrBtn: Button? = null
    private var dashboardLog: TextView? = null
    private var dashboardProfile: TextView? = null

    fun render(
        container: LinearLayout,
        isTablet: Boolean,
        snapshot: ServerSnapshot?,
        selectedScope: String,
        onStartStop: () -> Unit
    ) {
        container.removeAllViews()
        metricViews.clear()

        val port = snapshot?.port
        val active = snapshot?.running == true && port != null

        if (isTablet) {
            val columns = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

            val leftCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            leftCol.addView(buildServerControlCard(active, isTablet, onStartStop))

            val prefs = activity.getSharedPreferences("andserver", Context.MODE_PRIVATE)
            val infoCard = UiTheme.card(activity).apply {
                addView(UiTheme.label(activity, "PERFIL, REDE E SEGURANÇA", 11, UiTheme.textSubtle, bold = true))
                val configName = ServerProfiles.nameOf(prefs.getString("type", "web") ?: "web")
                val scopeName = if (selectedScope == "internet") "Toda a Internet (Público)" else "Rede Local (LAN)"
                dashboardProfile = UiTheme.label(
                    activity,
                    "Perfil: $configName\nModo de Rede: $scopeName\nPorta: ${prefs.getInt("port", ServerService.DEFAULT_TLS_PORT)}\nProtocolo: HTTPS / TLS 1.3\nEscudo Força Bruta: Ativo (Auto-Ban)\nSegurança: ${if (prefs.getString("password", "").isNullOrEmpty()) "Sem senha (Acesso livre)" else "HTTP Basic ativa"}",
                    13, UiTheme.textMuted
                ).apply {
                    setPadding(0, UiTheme.dp(activity, 8), 0, 0)
                    setLineSpacing(UiTheme.dp(activity, 4).toFloat(), 1f)
                }
                addView(dashboardProfile)
            }
            leftCol.addView(infoCard, UiTheme.marginTop(activity, 14))

            val rightCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            rightCol.addView(buildMetricsSection(active, snapshot))
            rightCol.addView(buildLogsSection(), UiTheme.marginTop(activity, 14))

            columns.addView(leftCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
            columns.addView(View(activity), LinearLayout.LayoutParams(UiTheme.dp(activity, 18), 1))
            columns.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            container.addView(columns)
        } else {
            container.addView(buildServerControlCard(active, isTablet, onStartStop))
            container.addView(buildMetricsSection(active, snapshot), UiTheme.marginTop(activity, 16))
            container.addView(buildLogsSection(), UiTheme.marginTop(activity, 16))

            dashboardProfile = UiTheme.label(activity, "", 12, UiTheme.textSubtle).apply {
                setPadding(UiTheme.dp(activity, 4), UiTheme.dp(activity, 14), UiTheme.dp(activity, 4), 0)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            container.addView(dashboardProfile)
        }
    }

    private fun buildServerControlCard(active: Boolean, isTablet: Boolean, onStartStop: () -> Unit): View {
        return UiTheme.card(activity).apply {
            val statusHeader = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            dashboardBadge = TextView(activity).apply {
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 5), UiTheme.dp(activity, 10), UiTheme.dp(activity, 5))
            }
            statusHeader.addView(dashboardBadge)

            dashboardScopeBadge = TextView(activity).apply {
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 5), UiTheme.dp(activity, 10), UiTheme.dp(activity, 5))
            }
            statusHeader.addView(
                dashboardScopeBadge,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = UiTheme.dp(activity, 8)
                }
            )

            dashboardPowerBadge = TextView(activity).apply {
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 5), UiTheme.dp(activity, 10), UiTheme.dp(activity, 5))
                visibility = View.GONE
            }
            statusHeader.addView(
                dashboardPowerBadge,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = UiTheme.dp(activity, 8)
                }
            )

            val spacer = View(activity)
            statusHeader.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

            val secureChip = UiTheme.label(activity, "🔒 TLS Autoassinado", 11, UiTheme.textSubtle)
            statusHeader.addView(secureChip)
            addView(statusHeader)

            dashboardTitle = UiTheme.label(activity, "", if (isTablet) 20 else 18, UiTheme.textPrimary, bold = true).apply {
                setPadding(0, UiTheme.dp(activity, 12), 0, UiTheme.dp(activity, 4))
            }
            addView(dashboardTitle)

            // Caixa de Endereço Local (LAN)
            val lanBox = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 12).toFloat())
                setPadding(UiTheme.dp(activity, 14), UiTheme.dp(activity, 12), UiTheme.dp(activity, 14), UiTheme.dp(activity, 12))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val url = dashboardAddressLan?.tag as? String
                    if (!url.isNullOrEmpty()) {
                        copyToClipboard("Endereço Local", url)
                    }
                }
            }
            lanBox.addView(UiTheme.label(activity, "ENDEREÇO NA REDE LOCAL (TOQUE P/ COPIAR)", 10, UiTheme.textSubtle, bold = true))
            dashboardAddressLan = TextView(activity).apply {
                textSize = if (isTablet) 15f else 14f
                setTextColor(Color.WHITE)
                typeface = Typeface.MONOSPACE
                setPadding(0, UiTheme.dp(activity, 5), 0, 0)
                setTextIsSelectable(true)
            }
            lanBox.addView(dashboardAddressLan)
            addView(lanBox, UiTheme.marginTop(activity, 10))

            // Caixa de Endereço Público / WAN (exibida quando no modo Internet)
            val wanBox = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 12).toFloat())
                setPadding(UiTheme.dp(activity, 14), UiTheme.dp(activity, 12), UiTheme.dp(activity, 14), UiTheme.dp(activity, 12))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val url = dashboardAddressWan?.tag as? String
                    if (!url.isNullOrEmpty()) {
                        copyToClipboard("Endereço Internet", url)
                    }
                }
            }
            wanBox.addView(UiTheme.label(activity, "ENDEREÇO NA INTERNET (GLOBAL / WAN)", 10, UiTheme.skyBlue, bold = true))
            dashboardAddressWan = TextView(activity).apply {
                textSize = if (isTablet) 15f else 14f
                setTextColor(Color.WHITE)
                typeface = Typeface.MONOSPACE
                setPadding(0, UiTheme.dp(activity, 5), 0, 0)
                setTextIsSelectable(true)
            }
            wanBox.addView(dashboardAddressWan)
            wanAddressBox = wanBox
            addView(wanBox, UiTheme.marginTop(activity, 8))

            // Linha com Botões: Iniciar/Parar e Mostrar QR Code
            val actionRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

            dashboardAction = Button(activity).apply {
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setOnClickListener { onStartStop() }
            }
            actionRow.addView(dashboardAction, LinearLayout.LayoutParams(0, UiTheme.dp(activity, 48), 1.2f))

            dashboardQrBtn = Button(activity).apply {
                text = "📱 QR CODE"
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(UiTheme.textPrimary)
                background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 12).toFloat())
                setOnClickListener {
                    val currentUrl = dashboardAddressLan?.tag as? String ?: ""
                    if (currentUrl.isNotEmpty()) {
                        showQrDialog(currentUrl)
                    } else {
                        Toast.makeText(activity, "Inicie o servidor para exibir o QR Code", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            actionRow.addView(
                dashboardQrBtn,
                LinearLayout.LayoutParams(0, UiTheme.dp(activity, 48), 0.8f).apply {
                    leftMargin = UiTheme.dp(activity, 8)
                }
            )

            addView(actionRow, UiTheme.marginTop(activity, 14))
        }
    }

    private fun buildMetricsSection(active: Boolean, snapshot: ServerSnapshot?): View {
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        container.addView(
            UiTheme.label(activity, "ATIVIDADE DO SERVIDOR", 11, UiTheme.mint, bold = true).apply {
                setPadding(UiTheme.dp(activity, 2), 0, 0, UiTheme.dp(activity, 8))
            }
        )

        val statsCard = UiTheme.card(activity)
        val uptime = if (active) FormatUtils.formatDuration(System.currentTimeMillis() - (snapshot?.startedAt ?: 0)) else "—"

        val row1 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(createMetric("REQUISIÇÕES", snapshot?.requests?.toString() ?: "0"), LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(createMetric("CONEXÕES ATIVAS", snapshot?.clients?.toString() ?: "0"), LinearLayout.LayoutParams(0, -2, 1f))
        statsCard.addView(row1)

        val row2 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, UiTheme.dp(activity, 14), 0, 0)
        }
        row2.addView(createMetric("ARQUIVOS HOSPEDADOS", snapshot?.files?.toString() ?: "0"), LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(createMetric("TEMPO ATIVO", uptime), LinearLayout.LayoutParams(0, -2, 1f))
        statsCard.addView(row2)

        val row3 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, UiTheme.dp(activity, 14), 0, 0)
        }
        row3.addView(createMetric("DADOS RECEBIDOS", FormatUtils.formatBytes(snapshot?.bytesReceived ?: 0)), LinearLayout.LayoutParams(0, -2, 1f))
        row3.addView(createMetric("DADOS ENVIADOS", FormatUtils.formatBytes(snapshot?.bytesSent ?: 0)), LinearLayout.LayoutParams(0, -2, 1f))
        statsCard.addView(row3)

        container.addView(statsCard)
        return container
    }

    private fun createMetric(caption: String, value: String): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(UiTheme.label(activity, caption, 11, UiTheme.textSubtle, bold = true))
        val valueView = UiTheme.label(activity, value, 20, UiTheme.textPrimary, bold = true).apply {
            setPadding(0, UiTheme.dp(activity, 3), 0, 0)
        }
        metricViews[caption] = valueView
        addView(valueView)
    }

    private fun buildLogsSection(): View {
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        container.addView(
            UiTheme.label(activity, "REGISTRO DE EVENTOS RECENTES", 11, UiTheme.mint, bold = true).apply {
                setPadding(UiTheme.dp(activity, 2), 0, 0, UiTheme.dp(activity, 8))
            }
        )

        val logCard = UiTheme.card(activity).apply {
            val logWrapper = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 10).toFloat())
                setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 10), UiTheme.dp(activity, 12), UiTheme.dp(activity, 10))
            }
            dashboardLog = TextView(activity).apply {
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(UiTheme.textMuted)
                setLineSpacing(UiTheme.dp(activity, 3).toFloat(), 1f)
            }
            logWrapper.addView(dashboardLog)
            addView(logWrapper)
        }
        container.addView(logCard)
        return container
    }

    fun update(
        snapshot: ServerSnapshot?,
        selectedScope: String,
        selectedType: String,
        isTablet: Boolean
    ) {
        val port = snapshot?.port
        val active = snapshot?.running == true && port != null
        val isInternet = selectedScope == "internet"
        val prefs = activity.getSharedPreferences("andserver", Context.MODE_PRIVATE)

        dashboardBadge?.apply {
            text = if (active) "●  ONLINE" else "○  PARADO"
            setTextColor(if (active) UiTheme.mint else UiTheme.amber)
            background = UiTheme.roundedDrawable(activity, if (active) UiTheme.mintDark else UiTheme.amberDark, 0, 0, UiTheme.dp(activity, 12).toFloat())
        }

        dashboardScopeBadge?.apply {
            text = if (isInternet) "🌍 TODA A INTERNET" else "🏠 REDE LOCAL"
            setTextColor(if (isInternet) UiTheme.skyBlue else UiTheme.textMuted)
            background = UiTheme.roundedDrawable(activity, if (isInternet) UiTheme.skyBlueDark else UiTheme.panelAlt, 0, 0, UiTheme.dp(activity, 12).toFloat())
        }

        dashboardPowerBadge?.apply {
            val powerText = snapshot?.powerProfile.orEmpty()
            if (powerText.isNotEmpty()) {
                visibility = View.VISIBLE
                val isCharging = powerText.contains("⚡")
                text = if (isCharging) "⚡ POTÊNCIA MÁXIMA" else "🔋 MODO EFICIENTE"
                setTextColor(if (isCharging) UiTheme.mint else UiTheme.textMuted)
                background = UiTheme.roundedDrawable(activity, if (isCharging) UiTheme.mintDark else UiTheme.panelAlt, 0, 0, UiTheme.dp(activity, 12).toFloat())
            } else {
                visibility = View.GONE
            }
        }

        dashboardTitle?.text = if (active) {
            if (isInternet) "Servidor ativo para toda a Internet" else "Servidor ativo na Rede Local"
        } else {
            "Servidor parado"
        }

        val lan = FormatUtils.getLanAddress()
        val lanUrl = if (active) "https://$lan:$port" else ""
        dashboardAddressLan?.apply {
            val typeHint = when (selectedType) {
                "drive" -> "\n💾 Modo Drive (WebDAV): $lanUrl/webdav\nToque para copiar o endereço"
                "streaming" -> "\n🎬 Streaming M3U (VLC / Smart TV): $lanUrl/stream/playlist.m3u\nToque para copiar"
                else -> "\nToque para copiar o endereço local"
            }
            text = if (active) "$lanUrl$typeHint" else "Inicie o servidor para liberar o endereço local."
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
            setTextColor(if (active) Color.WHITE else UiTheme.bg)
            background = UiTheme.roundedDrawable(
                activity,
                if (active) Color.rgb(185, 28, 28) else UiTheme.mint,
                0, 0, UiTheme.dp(activity, 12).toFloat()
            )
        }

        metricViews["REQUISIÇÕES"]?.text = snapshot?.requests?.toString() ?: "0"
        metricViews["CONEXÕES ATIVAS"]?.text = snapshot?.clients?.toString() ?: "0"
        metricViews["ARQUIVOS HOSPEDADOS"]?.text = snapshot?.files?.toString() ?: "0"
        metricViews["TEMPO ATIVO"]?.text = if (active) FormatUtils.formatDuration(System.currentTimeMillis() - (snapshot?.startedAt ?: 0)) else "—"
        metricViews["DADOS RECEBIDOS"]?.text = FormatUtils.formatBytes(snapshot?.bytesReceived ?: 0)
        metricViews["DADOS ENVIADOS"]?.text = FormatUtils.formatBytes(snapshot?.bytesSent ?: 0)

        val events = snapshot?.recentEvents.orEmpty()
        dashboardLog?.text = if (events.isEmpty()) {
            "Nenhuma requisição recebida ainda. O registro de conexões aparecerá aqui."
        } else {
            events.take(if (isTablet) 10 else 6).joinToString("\n\n")
        }

        val configName = ServerProfiles.nameOf(selectedType)
        val storageLabel = if (snapshot?.storageLocation == "external") "SD/Externo" else "Interno"
        val powerInfo = snapshot?.powerProfile?.takeIf { it.isNotEmpty() } ?: "Modo Inteligente"
        dashboardProfile?.text = "Perfil: $configName · Modo: ${if (isInternet) "Internet" else "Local"} · Armazenamento: $storageLabel\n$powerInfo · Porta ${prefs.getInt("port", ServerService.DEFAULT_TLS_PORT)} · HTTPS"
    }

    private fun showQrDialog(url: String) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = UiTheme.roundedDrawable(activity, UiTheme.panel, UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 20).toFloat())
            val pad = UiTheme.dp(activity, 24)
            setPadding(pad, pad, pad, pad)
        }

        view.addView(UiTheme.label(activity, "Conectar via QR Code", 18, UiTheme.textPrimary, bold = true))
        view.addView(UiTheme.label(activity, "Aponte a câmera de outro celular, tablet ou PC:", 13, UiTheme.textMuted).apply {
            setPadding(0, UiTheme.dp(activity, 4), 0, UiTheme.dp(activity, 16))
            gravity = Gravity.CENTER_HORIZONTAL
        })

        val matrix = QrGenerator.encode(url)
        val bitmap = QrGenerator.toBitmap(matrix, UiTheme.dp(activity, 240))
        val qrImage = ImageView(activity).apply {
            setImageBitmap(bitmap)
            background = UiTheme.roundedDrawable(activity, Color.WHITE, 0, 0, UiTheme.dp(activity, 12).toFloat())
            val p = UiTheme.dp(activity, 10)
            setPadding(p, p, p, p)
        }
        view.addView(qrImage, LinearLayout.LayoutParams(UiTheme.dp(activity, 250), UiTheme.dp(activity, 250)))

        val urlText = TextView(activity).apply {
            text = url
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(UiTheme.mint)
            setPadding(0, UiTheme.dp(activity, 14), 0, UiTheme.dp(activity, 14))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        view.addView(urlText)

        val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val copyBtn = Button(activity).apply {
            text = "COPIAR LINK"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(UiTheme.bg)
            background = UiTheme.roundedDrawable(activity, UiTheme.mint, 0, 0, UiTheme.dp(activity, 10).toFloat())
            setOnClickListener { copyToClipboard("Link do Servidor", url) }
        }
        btnRow.addView(copyBtn, LinearLayout.LayoutParams(0, UiTheme.dp(activity, 42), 1f))

        val closeBtn = Button(activity).apply {
            text = "FECHAR"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(UiTheme.textPrimary)
            background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 10).toFloat())
            setOnClickListener { dialog.dismiss() }
        }
        btnRow.addView(closeBtn, LinearLayout.LayoutParams(0, UiTheme.dp(activity, 42), 1f).apply {
            leftMargin = UiTheme.dp(activity, 8)
        })
        view.addView(btnRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clip = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(activity, "$label copiado para a área de transferência!", Toast.LENGTH_SHORT).show()
    }
}
