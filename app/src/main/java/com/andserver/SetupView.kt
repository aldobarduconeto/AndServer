package com.andserver

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Toast

/**
 * Visualização e lógica da tela de Configuração (Setup) do AndServer.
 * Permite selecionar o alcance de rede (LAN vs Internet WAN), o perfil do servidor,
 * filtros de tipos de arquivo permitidos, porta HTTPS e senha HTTP Basic.
 */
class SetupView(
    private val activity: Activity,
    private val onSaved: () -> Unit
) {
    private var scopeRadios: List<RadioButton> = emptyList()
    private var typeRadios: List<RadioButton> = emptyList()
    private val fileChecks = linkedMapOf<String, CheckBox>()

    fun render(
        container: LinearLayout,
        isTablet: Boolean,
        initialScope: String,
        initialType: String,
        initialFilters: LinkedHashSet<String>,
        onScopeChanged: (String) -> Unit,
        onTypeChanged: (String) -> Unit
    ) {
        val prefs = activity.getSharedPreferences("andserver", Context.MODE_PRIVATE)
        var selectedScope = initialScope
        var selectedType = initialType

        container.addView(UiTheme.label(activity, "CONFIGURAÇÃO DO SERVIDOR", 11, UiTheme.mint, bold = true).apply {
            setPadding(UiTheme.dp(activity, 2), 0, 0, UiTheme.dp(activity, 4))
        })
        container.addView(UiTheme.label(activity, "Escolha o alcance de rede, perfil do servidor e parâmetros de segurança.", 14, UiTheme.textMuted).apply {
            setPadding(UiTheme.dp(activity, 2), 0, 0, UiTheme.dp(activity, 16))
        })

        // 1. ESCOPO DE REDE: LOCAL VS. TODA A INTERNET
        container.addView(UiTheme.label(activity, "ALCANCE DE REDE (LOCAL VS. INTERNET)", 11, UiTheme.textSubtle, bold = true).apply {
            setPadding(0, 0, 0, UiTheme.dp(activity, 8))
        })
        val scopes = listOf(
            Triple("local", "🏠 Apenas Rede Local (LAN)", "Conexões aceitas apenas de dispositivos na mesma Wi-Fi. Conexões externas são bloqueadas pelo firewall."),
            Triple("internet", "🌍 Toda a Internet (Público / WAN)", "Acessível de qualquer lugar do mundo. Mostra seu IP público e ativa o escudo anti-força bruta.")
        )
        scopeRadios = scopes.map { (id, title, desc) ->
            buildScopeCard(id, title, desc, selectedScope) { chosenId ->
                selectedScope = chosenId
                onScopeChanged(chosenId)
            }.also { container.addView(it, UiTheme.marginTop(activity, 6)) }
        }

        // 2. PERFIS DO SERVIDOR
        val profiles = ServerProfiles.ALL

        if (isTablet) {
            val columns = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val leftCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val rightCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

            leftCol.addView(UiTheme.label(activity, "PERFIL DE OPERAÇÃO", 11, UiTheme.textSubtle, bold = true).apply {
                setPadding(0, UiTheme.dp(activity, 16), 0, UiTheme.dp(activity, 8))
            })
            typeRadios = profiles.map { profile ->
                buildProfileCard(profile.id, "${profile.icon} ${profile.name}", profile.description, selectedType) { chosenId ->
                    selectedType = chosenId
                    onTypeChanged(chosenId)
                }.also { leftCol.addView(it, UiTheme.marginTop(activity, 8)) }
            }

            rightCol.addView(UiTheme.label(activity, "FORMATOS DE ARQUIVO PERMITIDOS", 11, UiTheme.textSubtle, bold = true).apply {
                setPadding(0, UiTheme.dp(activity, 16), 0, UiTheme.dp(activity, 8))
            })
            buildFileFilters(rightCol, initialFilters)

            columns.addView(leftCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
            columns.addView(View(activity), LinearLayout.LayoutParams(UiTheme.dp(activity, 20), 1))
            columns.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            container.addView(columns)
        } else {
            container.addView(UiTheme.label(activity, "PERFIL DE OPERAÇÃO", 11, UiTheme.textSubtle, bold = true).apply {
                setPadding(0, UiTheme.dp(activity, 16), 0, UiTheme.dp(activity, 6))
            })
            typeRadios = profiles.map { profile ->
                buildProfileCard(profile.id, "${profile.icon} ${profile.name}", profile.description, selectedType) { chosenId ->
                    selectedType = chosenId
                    onTypeChanged(chosenId)
                }.also { container.addView(it, UiTheme.marginTop(activity, 8)) }
            }

            container.addView(UiTheme.label(activity, "FORMATOS DE ARQUIVO PERMITIDOS", 11, UiTheme.mint, bold = true).apply {
                setPadding(0, UiTheme.dp(activity, 22), 0, UiTheme.dp(activity, 6))
            })
            buildFileFilters(container, initialFilters)
        }

        // Seção de Porta e Senha
        val networkCard = UiTheme.card(activity).apply {
            addView(UiTheme.label(activity, "PARÂMETROS DE REDE E SENHA", 11, UiTheme.textSubtle, bold = true))

            addView(UiTheme.label(activity, "Porta HTTPS (1024 a 65535)", 13, UiTheme.textPrimary, bold = true).apply {
                setPadding(0, UiTheme.dp(activity, 12), 0, UiTheme.dp(activity, 6))
            })
            val portInput = EditText(activity).apply {
                setText(prefs.getInt("port", ServerService.DEFAULT_TLS_PORT).toString())
                inputType = InputType.TYPE_CLASS_NUMBER
                isSingleLine = true
                setTextColor(UiTheme.textPrimary)
                textSize = 15f
                setPadding(UiTheme.dp(activity, 14), UiTheme.dp(activity, 12), UiTheme.dp(activity, 14), UiTheme.dp(activity, 12))
                background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 10).toFloat())
            }
            addView(portInput, LinearLayout.LayoutParams(-1, UiTheme.dp(activity, 48)))

            addView(UiTheme.label(activity, "Senha de Acesso (HTTP Basic - Recomendada para Internet)", 13, UiTheme.textPrimary, bold = true).apply {
                setPadding(0, UiTheme.dp(activity, 14), 0, UiTheme.dp(activity, 4))
            })
            addView(UiTheme.label(activity, "Deixe em branco para permitir acesso livre sem autenticação aos clientes da rede.", 12, UiTheme.textSubtle).apply {
                setPadding(0, 0, 0, UiTheme.dp(activity, 6))
            })
            val passwordInput = EditText(activity).apply {
                hint = "Mínimo 8 caracteres (ou em branco)"
                setText(prefs.getString("password", ""))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSingleLine = true
                setTextColor(UiTheme.textPrimary)
                setHintTextColor(UiTheme.textSubtle)
                textSize = 15f
                setPadding(UiTheme.dp(activity, 14), UiTheme.dp(activity, 12), UiTheme.dp(activity, 14), UiTheme.dp(activity, 12))
                background = UiTheme.roundedDrawable(activity, UiTheme.panelAlt, UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 10).toFloat())
            }
            addView(passwordInput, LinearLayout.LayoutParams(-1, UiTheme.dp(activity, 48)))

            val saveBtn = Button(activity).apply {
                text = "SALVAR E APLICAR CONFIGURAÇÃO"
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(UiTheme.bg)
                background = UiTheme.roundedDrawable(activity, UiTheme.mint, 0, 0, UiTheme.dp(activity, 10).toFloat())
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
                        .putStringSet("filters", initialFilters.toSet())
                        .putInt("port", value)
                        .putString("password", passwordInput.text.toString())
                        .apply()

                    Toast.makeText(activity, "Configuração atualizada com sucesso!", Toast.LENGTH_SHORT).show()
                    onSaved()
                }
            }
            addView(saveBtn, UiTheme.marginTop(activity, 18))
        }

        container.addView(networkCard, UiTheme.marginTop(activity, 16))
    }

    private fun buildScopeCard(
        id: String,
        title: String,
        desc: String,
        currentScope: String,
        onSelect: (String) -> Unit
    ): RadioButton {
        val radio = RadioButton(activity).apply {
            text = "$title\n$desc"
            textSize = 13f
            setTextColor(UiTheme.textPrimary)
            buttonTintList = ColorStateList.valueOf(if (id == "internet") UiTheme.skyBlue else UiTheme.mint)
            isChecked = currentScope == id
            setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 8), UiTheme.dp(activity, 8), UiTheme.dp(activity, 8))
            background = UiTheme.roundedDrawable(
                activity,
                UiTheme.panel,
                if (currentScope == id) (if (id == "internet") UiTheme.skyBlue else UiTheme.mint) else UiTheme.panelBorder,
                UiTheme.dp(activity, 1),
                UiTheme.dp(activity, 12).toFloat()
            )
        }
        radio.setOnClickListener {
            onSelect(id)
            scopeRadios.forEach { item ->
                val checked = item == radio
                item.isChecked = checked
                item.background = UiTheme.roundedDrawable(
                    activity,
                    UiTheme.panel,
                    if (checked) (if (id == "internet") UiTheme.skyBlue else UiTheme.mint) else UiTheme.panelBorder,
                    UiTheme.dp(activity, 1),
                    UiTheme.dp(activity, 12).toFloat()
                )
            }
        }
        return radio
    }

    private fun buildProfileCard(
        id: String,
        title: String,
        desc: String,
        currentType: String,
        onSelect: (String) -> Unit
    ): RadioButton {
        val radio = RadioButton(activity).apply {
            text = "$title\n$desc"
            textSize = 13f
            setTextColor(UiTheme.textPrimary)
            buttonTintList = ColorStateList.valueOf(UiTheme.mint)
            isChecked = currentType == id
            setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 8), UiTheme.dp(activity, 8), UiTheme.dp(activity, 8))
            background = UiTheme.roundedDrawable(
                activity,
                UiTheme.panel,
                if (currentType == id) UiTheme.mint else UiTheme.panelBorder,
                UiTheme.dp(activity, 1),
                UiTheme.dp(activity, 12).toFloat()
            )
        }
        radio.setOnClickListener {
            onSelect(id)
            typeRadios.forEach { item ->
                val checked = item == radio
                item.isChecked = checked
                item.background = UiTheme.roundedDrawable(
                    activity,
                    UiTheme.panel,
                    if (checked) UiTheme.mint else UiTheme.panelBorder,
                    UiTheme.dp(activity, 1),
                    UiTheme.dp(activity, 12).toFloat()
                )
            }
        }
        return radio
    }

    private fun buildFileFilters(container: ViewGroup, filters: LinkedHashSet<String>) {
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
            val filterId = id
            val filterCard = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = UiTheme.roundedDrawable(activity, UiTheme.panel, UiTheme.panelBorder, UiTheme.dp(activity, 1), UiTheme.dp(activity, 10).toFloat())
                setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 6), UiTheme.dp(activity, 10), UiTheme.dp(activity, 6))

                val check = CheckBox(activity).apply {
                    text = title
                    textSize = 13f
                    setTextColor(UiTheme.textPrimary)
                    buttonTintList = ColorStateList.valueOf(UiTheme.mint)
                    isChecked = filterId in filters
                    setOnCheckedChangeListener { _, checked ->
                        if (filterId == "all") {
                            if (checked) {
                                filters.clear()
                                filters.add("all")
                                fileChecks.forEach { (key, chk) -> if (key != "all") chk.isChecked = false }
                            } else {
                                filters.remove("all")
                            }
                        } else {
                            if (checked) {
                                filters.remove("all")
                                fileChecks["all"]?.isChecked = false
                                filters.add(filterId)
                            } else {
                                filters.remove(filterId)
                            }
                        }
                    }
                }
                fileChecks[filterId] = check
                addView(check)
            }
            container.addView(filterCard, UiTheme.marginTop(activity, 6))
        }
    }
}
