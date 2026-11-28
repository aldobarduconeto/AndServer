package com.andserver

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Visualização e lógica da tela de Atualizações (Updates) do AndServer.
 * Integração com GitHub Releases API para consulta de versões, notas de atualização,
 * download assíncrono do APK e instalação pelo PackageInstaller nativo do Android.
 */
class UpdatesView(
    private val activity: Activity,
    private val registerInstallReceiver: (IntentFilter) -> Unit
) {
    private val releaseUpdater = GithubReleaseUpdater()
    private var releaseAction: Button? = null
    private var releaseStatusText: TextView? = null
    private var releaseProgressBar: ProgressBar? = null
    private var releaseBusy = false
    private var releaseReceiverRegistered = false

    fun render(container: LinearLayout) {
        container.addView(UiTheme.sectionTitle(activity, "Atualizações", "Consulte GitHub Releases e instale com confirmação do Android"))

        val currentVersion = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }

        val statusCard = UiTheme.card(activity).apply {
            addView(UiTheme.label(activity, "VERSÃO ATUAL INSTALADA", 11, UiTheme.skyBlue, bold = true))
            addView(UiTheme.label(activity, currentVersion, 22, UiTheme.textPrimary, bold = true).apply {
                setPadding(0, UiTheme.dp(activity, 4), 0, UiTheme.dp(activity, 8))
            })
            releaseStatusText = UiTheme.label(activity, "Toque no botão abaixo para verificar se há novas versões disponíveis no GitHub.", 13, UiTheme.textMuted).apply {
                setLineSpacing(UiTheme.dp(activity, 3).toFloat(), 1f)
            }
            addView(releaseStatusText)

            releaseProgressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false
                max = 100
                progress = 0
                visibility = View.GONE
            }
            addView(releaseProgressBar, UiTheme.marginTop(activity, 10))
        }
        container.addView(statusCard)

        releaseAction = Button(activity).apply {
            text = "VERIFICAR ATUALIZAÇÕES"
            isAllCaps = false
            setTextColor(UiTheme.bg)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = UiTheme.roundedDrawable(activity, UiTheme.skyBlue, 0, 0, UiTheme.dp(activity, 10).toFloat())
            setOnClickListener { checkForRelease() }
        }
        container.addView(releaseAction, LinearLayout.LayoutParams(-1, UiTheme.dp(activity, 52)).apply {
            topMargin = UiTheme.dp(activity, 12)
        })

        val note = TextView(activity).apply {
            text = "As atualizações são validadas criptograficamente e obtidas de forma segura diretamente das releases do GitHub. O instalador nativo do Android sempre solicita sua confirmação prévia."
            setTextColor(UiTheme.textSubtle)
            textSize = 12f
            setPadding(0, UiTheme.dp(activity, 12), 0, 0)
        }
        container.addView(note)
    }

    private fun checkForRelease() {
        if (releaseBusy) return
        releaseBusy = true
        releaseAction?.isEnabled = false
        releaseAction?.text = "Consultando GitHub…"
        releaseStatusText?.text = "Verificando se há novas versões disponíveis..."
        releaseProgressBar?.visibility = View.GONE

        Thread {
            try {
                val currentVersion = try {
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "1.0"
                } catch (_: Exception) { "1.0" }

                val check = releaseUpdater.checkUpdate(currentVersion, forceRefresh = true)
                activity.runOnUiThread {
                    releaseBusy = false
                    releaseAction?.isEnabled = true
                    releaseAction?.text = "VERIFICAR ATUALIZAÇÕES"

                    if (check.latestRelease == null) {
                        releaseStatusText?.text = check.message
                        showUpdaterMessage("Consulta de versão", check.message)
                        return@runOnUiThread
                    }

                    val release = check.latestRelease
                    val sizeFormatted = FormatUtils.formatBytes(release.sizeBytes)
                    val sizeText = if (release.sizeBytes > 0) " · Tamanho: $sizeFormatted" else ""

                    if (check.updateAvailable) {
                        releaseStatusText?.text = "Nova versão ${release.tag} disponível para download!"
                        AlertDialog.Builder(activity)
                            .setTitle("Nova versão encontrada: ${release.name.ifBlank { release.tag }}")
                            .setMessage("Versão instalada: $currentVersion\nNova versão: ${release.tag}$sizeText\n\nNotas da versão:\n${release.notes.take(1800).ifBlank { "Sem notas de versão." }}")
                            .setNegativeButton("Agora não", null)
                            .setPositiveButton("Baixar e instalar") { _, _ -> downloadRelease(release) }
                            .show()
                    } else {
                        releaseStatusText?.text = "O aplicativo está atualizado na versão mais recente ($currentVersion)."
                        showUpdaterMessage("Você já está atualizado", "Sua versão instalada ($currentVersion) é a mais recente disponível.")
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    releaseBusy = false
                    releaseAction?.isEnabled = true
                    releaseAction?.text = "VERIFICAR ATUALIZAÇÕES"
                    releaseStatusText?.text = "Falha ao consultar releases: ${e.message}"
                    showUpdaterMessage("Falha ao consultar releases", e.message ?: "Verifique sua conexão de rede e tente novamente.")
                }
            }
        }.start()
    }

    private fun downloadRelease(release: GithubReleaseUpdater.Release) {
        releaseBusy = true
        releaseAction?.isEnabled = false
        releaseAction?.text = "Baixando atualização… 0%"
        releaseStatusText?.text = "Iniciando download do pacote ${release.tag}..."
        releaseProgressBar?.apply {
            visibility = View.VISIBLE
            isIndeterminate = false
            progress = 0
        }

        Thread {
            val apk = File(activity.cacheDir, "AndServer-update.apk")
            try {
                releaseUpdater.download(release, apk) { downloaded, total, percent ->
                    activity.runOnUiThread {
                        releaseProgressBar?.progress = percent
                        releaseAction?.text = "Baixando atualização… $percent%"
                        releaseStatusText?.text = "Baixando: ${FormatUtils.formatBytes(downloaded)} de ${FormatUtils.formatBytes(total)} ($percent%)"
                    }
                }

                val info = activity.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                    ?: throw IllegalStateException("O arquivo baixado não é um APK válido.")
                if (info.packageName != activity.packageName) throw IllegalStateException("O APK pertence a outro aplicativo.")

                activity.runOnUiThread {
                    releaseBusy = false
                    releaseAction?.isEnabled = true
                    releaseAction?.text = "VERIFICAR ATUALIZAÇÕES"
                    releaseStatusText?.text = "Download concluído! Abrindo instalador do Android..."
                    releaseProgressBar?.visibility = View.GONE
                    installDownloadedRelease(apk)
                }
            } catch (e: Exception) {
                apk.delete()
                activity.runOnUiThread {
                    releaseBusy = false
                    releaseAction?.isEnabled = true
                    releaseAction?.text = "VERIFICAR ATUALIZAÇÕES"
                    releaseProgressBar?.visibility = View.GONE
                    releaseStatusText?.text = "Falha ao baixar: ${e.message}"
                    showUpdaterMessage("Falha ao baixar atualização", e.message ?: "Tente novamente.")
                }
            }
        }.start()
    }

    private fun installDownloadedRelease(apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Permita instalações para AndServer e toque em Buscar atualização novamente.", Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            return
        }
        try {
            val installer = activity.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(activity.packageName)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("AndServer-update.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                if (!releaseReceiverRegistered) {
                    val filter = IntentFilter("com.andserver.INSTALL_STATUS")
                    registerInstallReceiver(filter)
                    releaseReceiverRegistered = true
                }
                val callback = Intent("com.andserver.INSTALL_STATUS").setPackage(activity.packageName)
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
                val pending = PendingIntent.getBroadcast(activity, sessionId, callback, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            showUpdaterMessage("Não foi possível iniciar a instalação", e.message ?: "Tente novamente.")
        }
    }

    private fun showUpdaterMessage(title: String, message: String) {
        if (!activity.isFinishing) {
            AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
        }
    }
}
