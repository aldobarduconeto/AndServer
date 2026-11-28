package com.andserver

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tokens de design e componentes de interface modernos do AndServer (Dark Slate, Emerald & Sky Blue).
 */
object UiTheme {
    // Superfícies e paleta
    val bg = Color.rgb(11, 15, 23)           // #0B0F17 Fundo profundo
    val panel = Color.rgb(19, 27, 44)        // #131B2C Cartões e seções
    val panelAlt = Color.rgb(27, 38, 62)     // #1B263E Superfície interna/destaque
    val panelBorder = Color.rgb(38, 52, 80)  // #263450 Bordas sutis
    val mint = Color.rgb(16, 185, 129)       // #10B981 Emerald vibrante
    val mintDark = Color.rgb(6, 78, 59)      // #064E3B Fundo do badge ativo
    val skyBlue = Color.rgb(56, 189, 248)    // #38BDF8 Sky Blue para WAN
    val skyBlueDark = Color.rgb(12, 74, 110) // #0C4A6E Fundo do badge WAN
    val textPrimary = Color.rgb(248, 250, 252) // #F8FAFC Texto principal
    val textMuted = Color.rgb(148, 163, 184)   // #94A3B8 Texto secundário
    val textSubtle = Color.rgb(100, 116, 139)  // #64748B Legendas menores
    val amber = Color.rgb(245, 158, 11)        // #F59E0B Alerta/Inativo
    val amberDark = Color.rgb(120, 53, 15)     // #78350F Fundo inativo

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun roundedDrawable(
        context: Context,
        bgColor: Int,
        strokeColor: Int = 0,
        strokeWidth: Int = 0,
        radius: Float = dp(context, 14).toFloat()
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(bgColor)
        if (strokeWidth > 0 && strokeColor != 0) {
            setStroke(strokeWidth, strokeColor)
        }
    }

    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedDrawable(context, panel, panelBorder, dp(context, 1), dp(context, 16).toFloat())
        val p = dp(context, 16)
        setPadding(p, p, p, p)
    }

    fun label(
        context: Context,
        text: String,
        size: Int,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    fun marginTop(context: Context, v: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, v)
        }

    fun sectionTitle(context: Context, title: String, subtitle: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(context, title, 18, textPrimary, bold = true))
            addView(label(context, subtitle, 13, textMuted).apply {
                setPadding(0, dp(context, 4), 0, dp(context, 12))
            })
        }

    fun actionButton(
        context: Context,
        title: String,
        action: () -> Unit
    ): Button = Button(context).apply {
        text = title
        textSize = 14f
        setTextColor(textPrimary)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = roundedDrawable(context, panelAlt, panelBorder, dp(context, 1), dp(context, 12).toFloat())
        setOnClickListener { action() }
    }
}
