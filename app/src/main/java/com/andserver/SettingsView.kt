package com.andserver

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import java.io.File

/**
 * Visualização e lógica da tela de Ajustes (Settings) do AndServer.
 * Otimização de armazenamento (Interno vs Cartão SD), controle térmico e energético,
 * conexões concorrentes adaptadas ao hardware, drive WebDAV e streaming M3U.
 */
class SettingsView(
    private val activity: Activity,
    private val onRestartServer: (Boolean) -> Unit,
    private val onRefreshScreen: () -> Unit
) {

    fun render(
        container: LinearLayout,
        isTablet: Boolean,
        selectedType: String,
        selectedScope: String,
        onTypeChanged: (String) -> Unit
    ) {
        val prefs = activity.getSharedPreferences("andserver", Context.MODE_PRIVATE)

        container.addView(UiTheme.label(activity, "AJUSTES, HARDWARE E ARMAZENAMENTO", 11, UiTheme.mint, bold = true).apply {
            setPadding(UiTheme.dp(activity, 2), 0, 0, UiTheme.dp(activity, 4))
        })
        container.addView(UiTheme.label(activity, "Potencialize o uso de armazenamento e processamento do dispositivo com máxima eficiência energética.", 14, UiTheme.textMuted).apply {
            setPadding(UiTheme.dp(activity, 2), 0, 0, UiTheme.dp(activity, 16))
        })

        // Cartão de Hardware & Eficiência Energética
        container.addView(buildHardwarePowerCard())

        val content = if (isTablet) {
            val cols = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val leftCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val rightCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

            leftCol.addView(buildStoragePartitionCard())
            leftCol.addView(buildDriveCard(onTypeChanged), UiTheme.marginTop(activity, 12))
            leftCol.addView(buildWorkerCard(), UiTheme.marginTop(activity, 12))
            leftCol.addView(buildScreenCard(), UiTheme.marginTop(activity, 12))

            rightCol.addView(buildStreamingCard(onTypeChanged))
            rightCol.addView(buildSiteManagerCard(onTypeChanged), UiTheme.marginTop(activity, 12))
            rightCol.addView(buildStorageCard(), UiTheme.marginTop(activity, 12))

            cols.addView(leftCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            cols.addView(View(activity), LinearLayout.LayoutParams(UiTheme.dp(activity, 18), 1))
            cols.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            cols
        } else {
            val stack = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            stack.addView(buildStoragePartitionCard())
            stack.addView(buildDriveCard(onTypeChanged), UiTheme.marginTop(activity, 12))
            stack.addView(buildStreamingCard(onTypeChanged), UiTheme.marginTop(activity, 12))
            stack.addView(buildSiteManagerCard(onTypeChanged), UiTheme.marginTop(activity, 12))
            stack.addView(buildWorkerCard(), UiTheme.marginTop(activity, 12))
            stack.addView(buildScreenCard(), UiTheme.marginTop(activity, 12))
            stack.addView(buildStorageCard(), UiTheme.marginTop(activity, 12))
            stack
        }
        container.addView(content, UiTheme.marginTop(activity, 14))

        val footer = UiTheme.label(
            activity,
            "AndServer · Perfil: ${ServerProfiles.nameOf(selectedType)} · Modo: ${if (selectedScope == "internet") "Internet (WAN)" else "Rede Local (LAN)"} · Porta ${prefs.getInt("port", ServerService.DEFAULT_TLS_PORT)}",
            12, UiTheme.textSubtle
        ).apply {
            setPadding(UiTheme.dp(activity, 4), UiTheme.dp(activity, 20), UiTheme.dp(activity, 4), 0)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        container.addView(footer)
    }

    private fun buildHardwarePowerCard(): View = UiTheme.card(activity).apply {
        val power = DevicePowerManager(activity)
        addView(UiTheme.label(activity, "⚡ HARDWARE & EFICIÊNCIA ENERGÉTICA", 13, UiTheme.skyBlue, bold = true))
        val profileDesc = power.getPowerProfileLabel()
        val cores = power.cpuCores
        val memoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        addView(UiTheme.label(
            activity,
            "• Processador: $cores núcleos de CPU disponíveis para processamento\n• Memória Heap: Até ${memoryMb} MB com buffers zero-copy de 128 KB\n• Energia & Bateria: $profileDesc\n• Compressão GZIP: Ativa (reduz tráfego Wi-Fi em até 80%)\n• Otimização Doze: Wi-Fi lock ativado sob demanda, liberando repouso quando ocioso",
            12, UiTheme.textMuted
        ).apply {
            setPadding(0, UiTheme.dp(activity, 6), 0, UiTheme.dp(activity, 4))
            setLineSpacing(UiTheme.dp(activity, 4).toFloat(), 1f)
        })
    }

    private fun buildStoragePartitionCard(): View = UiTheme.card(activity).apply {
        val prefs = activity.getSharedPreferences("andserver", Context.MODE_PRIVATE)
        val currentLoc = prefs.getString("storage_location", "internal") ?: "internal"

        addView(UiTheme.label(activity, "💽 CAPACIDADE & LOCAL DE ARMAZENAMENTO", 13, UiTheme.mint, bold = true))
        addView(UiTheme.label(activity, "Aproveite a capacidade total de armazenamento do aparelho. Alterne entre o armazenamento interno privado e o armazenamento compartilhado/Cartão SD externo.", 12, UiTheme.textMuted).apply {
            setPadding(0, UiTheme.dp(activity, 4), 0, UiTheme.dp(activity, 10))
        })

        val internalStat = try { StatFs(activity.filesDir.path) } catch (_: Exception) { null }
        val internalFree = internalStat?.availableBytes ?: 0L
        val internalTotal = internalStat?.totalBytes ?: 0L

        val extDir = activity.getExternalFilesDir(null)
        val externalStat = try { extDir?.let { StatFs(it.path) } } catch (_: Exception) { null }
        val externalFree = externalStat?.availableBytes ?: 0L
        val externalTotal = externalStat?.totalBytes ?: 0L

        val internalRadio = RadioButton(activity).apply {
            text = "💾 Armazenamento Interno Seguro\nLivre: ${FormatUtils.formatBytes(internalFree)} / Total: ${FormatUtils.formatBytes(internalTotal)}"
            textSize = 13f
            setTextColor(UiTheme.textPrimary)
            isChecked = currentLoc == "internal"
            buttonTintList = ColorStateList.valueOf(UiTheme.mint)
            background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, if (currentLoc == "internal") UiTheme.mint else UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 10).toFloat())
            setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 8), UiTheme.dp(activity, 8), UiTheme.dp(activity, 8))
        }

        val externalRadio = RadioButton(activity).apply {
            text = "💽 Armazenamento Externo / Cartão SD (Maior Espaço)\nLivre: ${FormatUtils.formatBytes(externalFree)} / Total: ${FormatUtils.formatBytes(externalTotal)}"
            textSize = 13f
            setTextColor(UiTheme.textPrimary)
            isChecked = currentLoc == "external"
            buttonTintList = ColorStateList.valueOf(UiTheme.skyBlue)
            background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, if (currentLoc == "external") UiTheme.skyBlue else UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 10).toFloat())
            setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 8), UiTheme.dp(activity, 8), UiTheme.dp(activity, 8))
        }

        internalRadio.setOnClickListener {
            prefs.edit().putString("storage_location", "internal").apply()
            internalRadio.isChecked = true
            externalRadio.isChecked = false
            internalRadio.background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, UiTheme.mint, UiTheme.dp(activity, 1), UiTheme.dp(activity, 10).toFloat())
            externalRadio.background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 10).toFloat())
            Toast.makeText(activity, "Armazenamento Interno ativado!", Toast.LENGTH_SHORT).show()
            onRestartServer(false)
        }

        externalRadio.setOnClickListener {
            prefs.edit().putString("storage_location", "external").apply()
            externalRadio.isChecked = true
            internalRadio.isChecked = false
            externalRadio.background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, UiTheme.skyBlue, UiTheme.dp(activity, 1), UiTheme.dp(activity, 10).toFloat())
            internalRadio.background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 10).toFloat())
            Toast.makeText(activity, "Armazenamento Externo / SD ativado!", Toast.LENGTH_SHORT).show()
            onRestartServer(false)
        }

        addView(internalRadio)
        addView(externalRadio, UiTheme.marginTop(activity, 8))
    }

    private fun buildWorkerCard(): View = UiTheme.card(activity).apply {
        val prefs = activity.getSharedPreferences("andserver", Context.MODE_PRIVATE)
        val power = DevicePowerManager(activity)
        addView(UiTheme.label(activity, "CONEXÕES SIMULTÂNEAS (WORKERS)", 13, UiTheme.textPrimary, bold = true))
        addView(UiTheme.label(activity, "Gerencia a concorrência de CPU. Em modo carregador, até 32 conexões são processadas em paralelo sem travar.", 12, UiTheme.textMuted).apply {
            setPadding(0, UiTheme.dp(activity, 4), 0, UiTheme.dp(activity, 10))
        })
        val spinner = Spinner(activity).apply {
            background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 8).toFloat())
            setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 8), UiTheme.dp(activity, 10), UiTheme.dp(activity, 8))
        }
        val options = listOf(power.cpuCores, 2, 4, 8, 12, 16, 24, 32).distinct().sorted()
        spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, options.map {
            if (it == power.cpuCores) "$it conexões (Ideal para ${power.cpuCores} núcleos)" else "$it conexões simultâneas"
        })
        spinner.setSelection(options.indexOf(prefs.getInt("workers", power.cpuCores)).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val changed = prefs.getInt("workers", power.cpuCores) != options[position]
                prefs.edit().putInt("workers", options[position]).apply()
                if (changed) onRestartServer(false)
            }
        }
        addView(spinner)
    }

    private fun buildScreenCard(): View = UiTheme.card(activity).apply {
        val prefs = activity.getSharedPreferences("andserver", Context.MODE_PRIVATE)
        addView(UiTheme.label(activity, "MANTER TELA LIGADA", 13, UiTheme.textPrimary, bold = true))
        addView(UiTheme.label(activity, "Impede que o Android suspenda a rede Wi-Fi durante transferências longas ou apresentações.", 12, UiTheme.textMuted).apply {
            setPadding(0, UiTheme.dp(activity, 4), 0, UiTheme.dp(activity, 8))
        })
        val switch = Switch(activity).apply {
            text = "Evitar modo de repouso"
            setTextColor(UiTheme.textPrimary)
            isChecked = prefs.getBoolean("keep_screen", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("keep_screen", checked).apply()
                if (checked) {
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        addView(switch)
    }

    private fun buildSiteManagerCard(onTypeChanged: (String) -> Unit): View = UiTheme.card(activity).apply {
        val prefs = activity.getSharedPreferences("andserver", Context.MODE_PRIVATE)
        val loc = prefs.getString("storage_location", "internal") ?: "internal"
        val baseDir = if (loc == "external") (activity.getExternalFilesDir(null) ?: activity.filesDir) else activity.filesDir
        val hostedDir = File(baseDir, "hosted")
        val wwwDir = File(hostedDir, "www")
        val fileCount = if (wwwDir.exists()) wwwDir.walkTopDown().count { it.isFile } else 0

        addView(UiTheme.label(activity, "🌐 SITE WEB HOSPEDADO (PASTA WWW)", 13, UiTheme.mint, bold = true))
        addView(UiTheme.label(activity, "Hospede um site estático ou Single Page Application completo no dispositivo. Os arquivos ficam em /hosted/www e são servidos diretamente na raiz / no perfil 'Site Web Hospedado'.\nAtualmente $fileCount arquivo(s) presentes no site.", 12, UiTheme.textMuted).apply {
            setPadding(0, UiTheme.dp(activity, 4), 0, UiTheme.dp(activity, 12))
            setLineSpacing(UiTheme.dp(activity, 3).toFloat(), 1f)
        })

        val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

        val resetSiteBtn = Button(activity).apply {
            text = "RESTAURAR SITE PADRÃO"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(UiTheme.bg)
            background = UiTheme.roundedDrawable(activity, UiTheme.mint, 0, 0, UiTheme.dp(activity, 8).toFloat())
            setOnClickListener {
                SiteTemplateManager.createDefaultSiteFiles(hostedDir, overwrite = true)
                Toast.makeText(activity, "Site padrão restaurado em /hosted/www com sucesso!", Toast.LENGTH_SHORT).show()
                onRefreshScreen()
            }
        }
        btnRow.addView(resetSiteBtn, LinearLayout.LayoutParams(0, UiTheme.dp(activity, 40), 1f))

        val useProfileBtn = Button(activity).apply {
            text = "ATIVAR PERFIL SITE"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(UiTheme.textPrimary)
            background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 8).toFloat())
            setOnClickListener {
                prefs.edit().putString("type", "site").apply()
                onTypeChanged("site")
                Toast.makeText(activity, "Perfil 'Site Web Hospedado' ativado!", Toast.LENGTH_SHORT).show()
                onRestartServer(true)
            }
        }
        btnRow.addView(useProfileBtn, LinearLayout.LayoutParams(0, UiTheme.dp(activity, 40), 1f).apply {
            leftMargin = UiTheme.dp(activity, 8)
        })

        addView(btnRow)
    }

    private fun buildDriveCard(onTypeChanged: (String) -> Unit): View = UiTheme.card(activity).apply {
        val prefs = activity.getSharedPreferences("andserver", Context.MODE_PRIVATE)
        addView(UiTheme.label(activity, "💾 DRIVE NA REDE LOCAL (WEBDAV & NUVEM)", 13, UiTheme.mint, bold = true))
        addView(UiTheme.label(activity, "Mapeie este dispositivo como uma unidade de disco (Ex: Z:\\) no Windows Explorer, Mac Finder ou Linux. Permite salvar, editar e gerenciar arquivos pela rede sem cabos.", 12, UiTheme.textMuted).apply {
            setPadding(0, UiTheme.dp(activity, 4), 0, UiTheme.dp(activity, 12))
            setLineSpacing(UiTheme.dp(activity, 3).toFloat(), 1f)
        })

        val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

        val copyWebdavBtn = Button(activity).apply {
            text = "COPIAR URL WEBDAV"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(UiTheme.bg)
            background = UiTheme.roundedDrawable(activity, UiTheme.mint, 0, 0, UiTheme.dp(activity, 8).toFloat())
            setOnClickListener {
                val lan = FormatUtils.getLanAddress()
                val port = prefs.getInt("port", ServerService.DEFAULT_TLS_PORT)
                val url = "https://$lan:$port/webdav"
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("URL WebDAV", url))
                Toast.makeText(activity, "URL WebDAV copiada:\n$url\nMapeie como unidade de rede no Windows!", Toast.LENGTH_LONG).show()
            }
        }
        btnRow.addView(copyWebdavBtn, LinearLayout.LayoutParams(0, UiTheme.dp(activity, 40), 1f))

        val useDriveBtn = Button(activity).apply {
            text = "ATIVAR PERFIL DRIVE"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(UiTheme.textPrimary)
            background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 8).toFloat())
            setOnClickListener {
                prefs.edit().putString("type", "drive").apply()
                onTypeChanged("drive")
                Toast.makeText(activity, "Perfil 'Drive na Rede Local' ativado!", Toast.LENGTH_SHORT).show()
                onRestartServer(true)
            }
        }
        btnRow.addView(useDriveBtn, LinearLayout.LayoutParams(0, UiTheme.dp(activity, 40), 1f).apply {
            leftMargin = UiTheme.dp(activity, 8)
        })

        addView(btnRow)
    }

    private fun buildStreamingCard(onTypeChanged: (String) -> Unit): View = UiTheme.card(activity).apply {
        val prefs = activity.getSharedPreferences("andserver", Context.MODE_PRIVATE)
        addView(UiTheme.label(activity, "🎬 STREAMING DE MÍDIA & SMART TV (M3U)", 13, UiTheme.skyBlue, bold = true))
        addView(UiTheme.label(activity, "Transmita vídeos e músicas armazenados no celular para Smart TVs, VLC, Kodi e reprodutores de mídia na rede Wi-Fi via playlist M3U dinâmica ou player web.", 12, UiTheme.textMuted).apply {
            setPadding(0, UiTheme.dp(activity, 4), 0, UiTheme.dp(activity, 12))
            setLineSpacing(UiTheme.dp(activity, 3).toFloat(), 1f)
        })

        val btnRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

        val copyM3uBtn = Button(activity).apply {
            text = "COPIAR PLAYLIST M3U"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(UiTheme.bg)
            background = UiTheme.roundedDrawable(activity, UiTheme.skyBlue, 0, 0, UiTheme.dp(activity, 8).toFloat())
            setOnClickListener {
                val lan = FormatUtils.getLanAddress()
                val port = prefs.getInt("port", ServerService.DEFAULT_TLS_PORT)
                val url = "https://$lan:$port/stream/playlist.m3u"
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("URL Playlist M3U", url))
                Toast.makeText(activity, "URL M3U copiada:\n$url\nAbra no VLC ou Smart TV!", Toast.LENGTH_LONG).show()
            }
        }
        btnRow.addView(copyM3uBtn, LinearLayout.LayoutParams(0, UiTheme.dp(activity, 40), 1f))

        val useStreamBtn = Button(activity).apply {
            text = "ATIVAR STREAMING"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(UiTheme.textPrimary)
            background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 8).toFloat())
            setOnClickListener {
                prefs.edit().putString("type", "streaming").apply()
                onTypeChanged("streaming")
                Toast.makeText(activity, "Perfil 'Streaming de Mídia' ativado!", Toast.LENGTH_SHORT).show()
                onRestartServer(true)
            }
        }
        btnRow.addView(useStreamBtn, LinearLayout.LayoutParams(0, UiTheme.dp(activity, 40), 1f).apply {
            leftMargin = UiTheme.dp(activity, 8)
        })

        addView(btnRow)
    }

    private fun buildStorageCard(): View = UiTheme.card(activity).apply {
        val prefs = activity.getSharedPreferences("andserver", Context.MODE_PRIVATE)
        val loc = prefs.getString("storage_location", "internal") ?: "internal"
        val baseDir = if (loc == "external") (activity.getExternalFilesDir(null) ?: activity.filesDir) else activity.filesDir
        val hostedDir = File(baseDir, "hosted")

        addView(UiTheme.label(activity, "ARMAZENAMENTO DE ARQUIVOS", 13, UiTheme.textPrimary, bold = true))
        addView(UiTheme.label(activity, "Os arquivos enviados pela interface web ficam guardados no espaço selecionado (${if (loc == "external") "Cartão SD / Externo" else "Interno Privado"}).", 12, UiTheme.textMuted).apply {
            setPadding(0, UiTheme.dp(activity, 4), 0, UiTheme.dp(activity, 12))
        })
        val clearBtn = Button(activity).apply {
            text = "LIMPAR ARQUIVOS HOSPEDADOS"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = UiTheme.roundedDrawable(activity, Color.rgb(127, 29, 29), 0, 0, UiTheme.dp(activity, 8).toFloat())
            setOnClickListener {
                val count = hostedDir.listFiles()?.count { it.walkTopDown().count { child -> child.isFile } > 0 && it.deleteRecursively() } ?: 0
                Toast.makeText(activity, "$count item(ns) removido(s)", Toast.LENGTH_SHORT).show()
                onRefreshScreen()
            }
        }
        addView(clearBtn)
    }
}
