package com.radiohonda.radaruy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.radiohonda.radaruy.routing.Maneuver
import com.radiohonda.radaruy.routing.Navigator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Capa sobre el mapa (estilo Waze): velocímetro, cartel de límite de velocidad y aviso
 * de radar. En Android Auto se dibuja solo dentro del área visible ([contentArea]),
 * así no queda tapada por la tarjeta de Spotify en la pantalla dividida.
 */
class HudView(
    context: Context,
    private val scale: Float = 1f,
    /** En el teléfono se dibuja el cartel de indicaciones; en Android Auto lo dibuja el auto. */
    private val showNavBanner: Boolean = false,
) : View(context) {

    private var state = Drive.State()
    private var contentArea: Rect? = null
    private val dp = context.resources.displayMetrics.density * scale

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    private var nav = Navigator.State()
    private val arrows = HashMap<Maneuver.Type, android.graphics.Bitmap>()
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setNav(n: Navigator.State) {
        nav = n
        invalidate()
    }

    fun setState(s: Drive.State) {
        state = s
        invalidate()
    }

    fun setContentArea(r: Rect?) {
        contentArea = r
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val area = contentArea ?: Rect(0, 0, width, height)
        val margin = 14 * dp

        // --- Velocímetro (abajo a la izquierda) ---
        val r = 38 * dp
        val cx = area.left + margin + r
        val cy = area.bottom - margin - r
        p.style = Paint.Style.FILL
        p.color = if (state.overLimit) 0xFFE53935.toInt() else 0xEE1B2442.toInt()
        canvas.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3 * dp
        p.color = if (state.overLimit) Color.WHITE else 0xFF3E5089.toInt()
        canvas.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        p.typeface = bold
        p.textAlign = Paint.Align.CENTER
        p.textSize = 30 * dp
        canvas.drawText(if (state.hasFix) state.speedKmh.toString() else "--", cx, cy + 8 * dp, p)
        p.textSize = 11 * dp
        p.typeface = Typeface.DEFAULT
        p.color = 0xCCFFFFFF.toInt()
        canvas.drawText("km/h", cx, cy + 24 * dp, p)

        // --- Cartel de límite (encima del velocímetro) ---
        state.limit?.let { lim ->
            val lr = 27 * dp
            val lx = cx + r * 0.62f
            val ly = cy - r - lr + 4 * dp
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            canvas.drawCircle(lx, ly, lr, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 5.5f * dp
            p.color = 0xFFE53935.toInt()
            canvas.drawCircle(lx, ly, lr - 2.75f * dp, p)
            p.style = Paint.Style.FILL
            p.color = 0xFF111111.toInt()
            p.typeface = bold
            p.textSize = (if (lim.kmh >= 100) 18 else 21) * dp
            canvas.drawText(lim.kmh.toString(), lx, ly + 7.5f * dp, p)
            if (lim.estimated) {
                p.textSize = 9 * dp
                p.typeface = Typeface.DEFAULT
                p.color = 0xFF555555.toInt()
                canvas.drawText("aprox.", lx, ly + 17 * dp, p)
            }
        }

        // --- Navegación (solo teléfono) ---
        // En el teléfono, sin navegar, arriba está la barra de búsqueda
        var top = area.top.toFloat() + if (showNavBanner && !nav.active) 72 * dp else 0f
        if (showNavBanner && (nav.active || nav.error != null)) {
            top = drawBanner(canvas, area, margin) + 8 * dp
            nav.route?.let { drawEta(canvas, cx + r + 12 * dp, cy, area) }
        }

        // --- Aviso de radar (arriba) ---
        state.radar?.let { ahead ->
            val kind = when (ahead.radar.kind) {
                "average" -> "Radar de tramo"
                "redlight" -> "Cámara de semáforo"
                else -> "Radar"
            }
            val lim = state.radarLimit
            val title = "$kind a ${ahead.distanceM} m"
            val sub = if (lim != null) "Máx. $lim km/h" else "Reducí la velocidad"
            val w = minOf(area.width() - 2 * margin, 300 * dp)
            val box = RectF(area.left + margin, top + margin, area.left + margin + w, top + margin + 62 * dp)
            p.style = Paint.Style.FILL
            p.color = if (lim != null && state.speedKmh > lim + 3) 0xF2E53935.toInt() else 0xF21B2442.toInt()
            canvas.drawRoundRect(box, 14 * dp, 14 * dp, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2 * dp
            p.color = 0xFFE53935.toInt()
            canvas.drawRoundRect(box, 14 * dp, 14 * dp, p)
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            p.textAlign = Paint.Align.LEFT
            p.typeface = bold
            p.textSize = 18 * dp
            canvas.drawText(title, box.left + 16 * dp, box.top + 27 * dp, p)
            p.typeface = Typeface.DEFAULT
            p.textSize = 14 * dp
            p.color = 0xDDFFFFFF.toInt()
            canvas.drawText(sub, box.left + 16 * dp, box.top + 49 * dp, p)
        }
    }

    /** Cartel superior con la próxima indicación. Devuelve el borde inferior. */
    private fun drawBanner(canvas: Canvas, area: Rect, margin: Float): Float {
        val box = RectF(area.left + margin, area.top + margin, area.right - margin, area.top + margin + 96 * dp)
        p.style = Paint.Style.FILL
        p.color = 0xF20E2A3D.toInt()
        canvas.drawRoundRect(box, 18 * dp, 18 * dp, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2 * dp
        p.color = 0xFF2FE6E9.toInt()
        canvas.drawRoundRect(box, 18 * dp, 18 * dp, p)

        val next = nav.next
        tp.color = Color.WHITE
        if (nav.route == null || next == null) {
            tp.textSize = 18 * dp
            tp.typeface = bold
            val msg = nav.error ?: "Calculando ruta…"
            canvas.drawText(msg, box.left + 20 * dp, box.centerY() + 6 * dp, tp)
            return box.bottom
        }
        val icon = arrows.getOrPut(next.type) { Icons.maneuver(next.type, (60 * dp).toInt()) }
        canvas.drawBitmap(icon, box.left + 14 * dp, box.centerY() - icon.height / 2f, null)
        val tx = box.left + 88 * dp
        val avail = box.right - tx - 14 * dp
        tp.typeface = bold
        tp.textSize = 28 * dp
        canvas.drawText(Navigator.formatDistance(nav.distToNext), tx, box.top + 40 * dp, tp)
        tp.typeface = Typeface.DEFAULT
        tp.textSize = 16 * dp
        tp.color = 0xEEFFFFFF.toInt()
        val text = TextUtils.ellipsize(next.text(), tp, avail, TextUtils.TruncateAt.END).toString()
        canvas.drawText(text, tx, box.top + 70 * dp, tp)
        return box.bottom
    }

    /** Hora de llegada, tiempo y distancia restantes (abajo, junto al velocímetro). */
    private fun drawEta(canvas: Canvas, left: Float, cy: Float, area: Rect) {
        val w = minOf(170 * dp, area.right - left - 140 * dp)
        if (w < 110 * dp) return
        val box = RectF(left, cy - 30 * dp, left + w, cy + 30 * dp)
        p.style = Paint.Style.FILL
        p.color = 0xEE1B2442.toInt()
        canvas.drawRoundRect(box, 16 * dp, 16 * dp, p)
        val arrival = clock.format(Date(System.currentTimeMillis() + (nav.remainingTime * 1000).toLong()))
        tp.color = 0xFF2FE6E9.toInt()
        tp.typeface = bold
        tp.textSize = 20 * dp
        canvas.drawText("$arrival", box.left + 14 * dp, box.top + 26 * dp, tp)
        tp.color = 0xCCFFFFFF.toInt()
        tp.typeface = Typeface.DEFAULT
        tp.textSize = 13 * dp
        canvas.drawText(
            "${Navigator.formatDuration(nav.remainingTime)} · ${Navigator.formatDistance(nav.remainingDist)}",
            box.left + 14 * dp, box.top + 47 * dp, tp
        )
    }
}
