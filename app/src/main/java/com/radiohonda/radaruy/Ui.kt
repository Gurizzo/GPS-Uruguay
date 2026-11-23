package com.radiohonda.radaruy

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Componentes de interfaz con el mismo lenguaje visual que el mapa nocturno:
 * azul noche, tarjetas redondeadas, acento cian y los mismos íconos.
 */
object Ui {
    const val BG = 0xFF131A2E.toInt()
    const val CARD = 0xFF1B2442.toInt()
    const val CARD_PRESSED = 0xFF26305A.toInt()
    const val STROKE = 0xFF2E3B66.toInt()
    const val ACCENT = 0xFF2FE6E9.toInt()
    const val TEXT = Color.WHITE
    const val TEXT_DIM = 0xFF9AA6CC.toInt()
    const val DANGER = 0xFFE53935.toInt()

    fun dp(c: Context, v: Float) = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusPx: Float, stroke: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (stroke != null) setStroke(strokePx, stroke)
    }

    private fun pressable(v: View, color: Int, radius: Float, stroke: Int? = null) {
        val d = v.context.resources.displayMetrics.density
        v.background = rounded(color, radius, stroke, (1.2f * d).toInt())
        v.isClickable = true
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.alpha = 0.7f
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.alpha = 1f
            }
            false
        }
    }

    /** Botón redondo flotante con ícono. */
    fun roundButton(c: Context, icon: Icons.Ui, sizeDp: Float = 54f, onClick: () -> Unit): ImageView {
        val size = dp(c, sizeDp)
        return ImageView(c).apply {
            setImageBitmap(Icons.ui(icon, dp(c, 30f)))
            scaleType = ImageView.ScaleType.CENTER
            pressable(this, 0xF01B2442.toInt(), size / 2f, STROKE)
            elevation = dp(c, 6f).toFloat()
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(size, size).apply { topMargin = dp(c, 12f) }
        }
    }

    /** Botón tipo pastilla: principal (cian) o secundario. */
    fun pill(c: Context, label: String, primary: Boolean = true, color: Int = ACCENT, onClick: () -> Unit): TextView =
        TextView(c).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (primary) BG else TEXT)
            setPadding(dp(c, 20f), dp(c, 10f), dp(c, 20f), dp(c, 10f))
            pressable(this, if (primary) color else CARD, dp(c, 22f).toFloat(), if (primary) null else STROKE)
            setOnClickListener { onClick() }
        }

    /** Fila de lista: ícono, título, subtítulo y botón "Ir" opcional. */
    fun row(
        c: Context, icon: Bitmap?, title: String, subtitle: String?,
        action: String? = null, onAction: (() -> Unit)? = null, onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 14f), dp(c, 10f), dp(c, 12f), dp(c, 10f))
            pressable(this, CARD, dp(c, 18f).toFloat())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(c, 8f) }
        }
        if (icon != null) {
            r.addView(ImageView(c).apply { setImageBitmap(icon) },
                LinearLayout.LayoutParams(dp(c, 40f), dp(c, 40f)).apply { rightMargin = dp(c, 12f) })
        }
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(c).apply {
            text = title; setTextColor(TEXT); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (!subtitle.isNullOrEmpty()) texts.addView(TextView(c).apply {
            text = subtitle; setTextColor(TEXT_DIM); textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null && onAction != null) {
            r.addView(pill(c, action) { onAction() }.apply { textSize = 14f; setPadding(dp(c, 18f), dp(c, 8f), dp(c, 18f), dp(c, 8f)) })
        }
        if (onClick != null) r.setOnClickListener { onClick() }
        return r
    }

    /** Fila con interruptor (círculo cian con tilde). */
    fun toggleRow(c: Context, icon: Bitmap?, title: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        var on = checked
        val mark = ImageView(c)
        fun paint() {
            mark.setImageBitmap(if (on) Icons.ui(Icons.Ui.CHECK, dp(c, 22f), BG) else null)
            mark.background = if (on) rounded(ACCENT, dp(c, 14f).toFloat())
            else rounded(Color.TRANSPARENT, dp(c, 14f).toFloat(), TEXT_DIM, dp(c, 2f))
        }
        paint()
        val r = row(c, icon, title, null)
        mark.scaleType = ImageView.ScaleType.CENTER
        r.addView(mark, LinearLayout.LayoutParams(dp(c, 28f), dp(c, 28f)))
        r.setOnClickListener { on = !on; paint(); onChange(on) }
        return r
    }

    /** Título de sección. */
    fun header(c: Context, text: String) = TextView(c).apply {
        this.text = text; setTextColor(TEXT_DIM); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.1f
        setPadding(dp(c, 6f), dp(c, 8f), 0, dp(c, 8f))
    }

    /** Campo de búsqueda redondeado. */
    fun searchField(c: Context, hint: String) = EditText(c).apply {
        this.hint = hint
        setHintTextColor(TEXT_DIM)
        setTextColor(TEXT)
        textSize = 17f
        setSingleLine()
        background = rounded(CARD, dp(c, 24f).toFloat(), STROKE, dp(c, 1.2f))
        setPadding(dp(c, 48f), dp(c, 14f), dp(c, 16f), dp(c, 14f))
        val icon = Icons.ui(Icons.Ui.SEARCH, dp(c, 26f), TEXT_DIM)
        setCompoundDrawablesWithIntrinsicBounds(android.graphics.drawable.BitmapDrawable(c.resources, icon), null, null, null)
        compoundDrawablePadding = dp(c, 8f)
        setPadding(dp(c, 14f), dp(c, 14f), dp(c, 16f), dp(c, 14f))
    }

    /**
     * Hoja inferior (bottom sheet) con título, botón de cerrar y contenido desplazable.
     * Devuelve el diálogo y el contenedor donde agregar filas.
     */
    class Sheet(val activity: Activity, title: String, onBack: (() -> Unit)? = null, scroll: Boolean = true) {
        val dialog = Dialog(activity)
        val content: LinearLayout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val root: LinearLayout

        init {
            val c = activity
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            root = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(c, 16f), dp(c, 10f), dp(c, 16f), dp(c, 16f))
                background = GradientDrawable().apply {
                    setColor(BG)
                    val r = dp(c, 28f).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
            }
            // manija
            root.addView(View(c).apply { background = rounded(STROKE, dp(c, 3f).toFloat()) },
                LinearLayout.LayoutParams(dp(c, 42f), dp(c, 5f)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(c, 10f)
                })
            val bar = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (onBack != null) bar.addView(roundButton(c, Icons.Ui.BACK, 40f) { dialog.dismiss(); onBack() }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(c, 40f), dp(c, 40f)).apply { rightMargin = dp(c, 10f) }
            })
            bar.addView(TextView(c).apply {
                text = title; setTextColor(TEXT); textSize = 21f; typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(roundButton(c, Icons.Ui.CLOSE, 40f) { dialog.dismiss() }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(c, 40f), dp(c, 40f))
            })
            root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(c, 12f) })
            if (scroll) {
                val sv = ScrollView(c).apply { isVerticalScrollBarEnabled = false }
                sv.addView(content)
                root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            } else {
                root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            dialog.setContentView(root)
        }

        /** Alto: fracción de la pantalla (o WRAP si es null). */
        fun show(heightFraction: Float? = 0.72f) {
            val w = dialog.window ?: return
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            val h = if (heightFraction == null) WindowManager.LayoutParams.WRAP_CONTENT
            else (activity.resources.displayMetrics.heightPixels * heightFraction).toInt()
            if (heightFraction == null) {
                (root.getChildAt(root.childCount - 1).layoutParams as LinearLayout.LayoutParams).apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT; weight = 0f
                }
            }
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, h)
            w.setDimAmount(0.45f)
            w.attributes.windowAnimations = android.R.style.Animation_InputMethod
            dialog.show()
        }

        fun dismiss() = dialog.dismiss()
    }

    /** Contenedor de dos columnas para "chips" de categorías. */
    fun grid(c: Context, items: List<View>): LinearLayout {
        val g = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        items.chunked(2).forEach { pair ->
            val line = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, v ->
                line.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i == 0) rightMargin = dp(c, 8f)
                    bottomMargin = dp(c, 8f)
                })
            }
            if (pair.size == 1) line.addView(FrameLayout(c), LinearLayout.LayoutParams(0, 1, 1f))
            g.addView(line)
        }
        return g
    }

    /** Chip de categoría: ícono arriba y nombre. */
    fun chip(c: Context, icon: Bitmap, label: String, onClick: () -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(c, 12f), dp(c, 12f), dp(c, 10f), dp(c, 12f))
        pressable(this, CARD, dp(c, 18f).toFloat())
        addView(ImageView(c).apply { setImageBitmap(icon) }, LinearLayout.LayoutParams(dp(c, 36f), dp(c, 36f)).apply { rightMargin = dp(c, 10f) })
        addView(TextView(c).apply {
            text = label; setTextColor(TEXT); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; maxLines = 2
        })
        setOnClickListener { onClick() }
    }
}
