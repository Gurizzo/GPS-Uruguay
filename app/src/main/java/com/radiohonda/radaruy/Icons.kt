package com.radiohonda.radaruy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Dibuja los íconos del mapa: círculos oscuros con símbolo blanco (como en el estilo
 * de referencia), los radares y la flecha del auto. Todo se dibuja en un espacio de
 * -20..20 unidades y se escala al tamaño real.
 */
object Icons {
    private const val BG = 0xFF1B2442.toInt()

    private fun canvasFor(sizePx: Int): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(sizePx / 2f, sizePx / 2f)
        c.scale(sizePx / 44f, sizePx / 44f)
        return bmp to c
    }

    private fun fill(color: Int = Color.WHITE) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    private fun stroke(color: Int = Color.WHITE, w: Float = 2.2f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = w
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

    private fun text(c: Canvas, s: String, size: Float, color: Int = Color.WHITE) {
        val p = fill(color).apply {
            textSize = size; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        c.drawText(s, 0f, -(p.ascent() + p.descent()) / 2f, p)
    }

    private const val GLYPH = 0xFF262B40.toInt()
    private const val TILE = 0xFFC9CEDC.toInt()

    /** Mezcla dos colores: t = 0 -> a, t = 1 -> b. */
    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 0xff) * (1 - t) + ((b shr sh) and 0xff) * t).toInt() and 0xff
        return (0xff shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Ícono de negocio: baldosa cuadrada redondeada, gris azulado claro con un toque
     * del color de la categoría, borde fino y símbolo oscuro.
     */
    fun poi(cat: PoiCategory, sizePx: Int): Bitmap {
        val (bmp, c) = canvasFor(sizePx)
        val tile = mix(TILE, cat.color, 0.22f)
        val box = RectF(-18.5f, -18.5f, 18.5f, 18.5f)
        // sombra suave
        c.drawRoundRect(RectF(-18.5f, -17f, 18.5f, 20f), 8f, 8f, fill(0x55000000))
        // relleno con degradé (más claro arriba)
        val grad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(0f, -18.5f, 0f, 18.5f,
                mix(tile, Color.WHITE, 0.35f), tile, android.graphics.Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(box, 8f, 8f, grad)
        c.drawRoundRect(box, 8f, 8f, stroke(mix(tile, 0xFF1B2442.toInt(), 0.45f), 1.6f))
        val s = stroke(GLYPH, 2.5f)
        val f = fill(GLYPH)
        when (cat) {
            PoiCategory.FUEL -> {
                c.drawRoundRect(RectF(-8f, -9f, 2f, 9f), 1.5f, 1.5f, s)
                c.drawRect(-6f, -7f, 0f, -2f, f)
                val p = Path().apply { moveTo(2f, -5f); lineTo(6f, -2f); lineTo(6f, 6f); quadTo(6f, 8f, 4.5f, 7f) }
                c.drawPath(p, s)
            }
            PoiCategory.CHARGING -> {
                val p = Path().apply {
                    moveTo(2f, -11f); lineTo(-7f, 2f); lineTo(-1f, 2f); lineTo(-3f, 11f)
                    lineTo(7f, -2f); lineTo(1f, -2f); close()
                }
                c.drawPath(p, f)
            }
            PoiCategory.RESTAURANT -> {
                c.drawLine(-5f, -9f, -5f, 9f, s)
                c.drawLine(-8f, -9f, -8f, -4f, s)
                c.drawLine(-2f, -9f, -2f, -4f, s)
                c.drawArc(RectF(-8f, -7f, -2f, -1f), 0f, 180f, false, s)
                val k = Path().apply { moveTo(5f, 9f); lineTo(5f, -9f); quadTo(9f, -6f, 8.5f, 2f); lineTo(5f, 2f) }
                c.drawPath(k, s)
            }
            PoiCategory.FAST_FOOD -> {
                c.drawArc(RectF(-9f, -9f, 9f, 3f), 180f, 180f, true, f)
                c.drawLine(-9f, 1f, 9f, 1f, stroke(GLYPH, 2.6f))
                c.drawRoundRect(RectF(-9f, 4f, 9f, 8f), 2f, 2f, f)
            }
            PoiCategory.CAFE -> {
                val p = Path().apply { moveTo(-8f, -2f); lineTo(-7f, 8f); lineTo(3f, 8f); lineTo(4f, -2f); close() }
                c.drawPath(p, s)
                c.drawArc(RectF(2f, -1f, 9f, 6f), -90f, 180f, false, s)
                c.drawLine(-5f, -9f, -5f, -5f, stroke(GLYPH, 1.6f))
                c.drawLine(-1f, -10f, -1f, -5f, stroke(GLYPH, 1.6f))
            }
            PoiCategory.BAR -> {
                val p = Path().apply { moveTo(-8f, -8f); lineTo(8f, -8f); lineTo(0f, 1f); close() }
                c.drawPath(p, s)
                c.drawLine(0f, 1f, 0f, 8f, s)
                c.drawLine(-4f, 8f, 4f, 8f, s)
            }
            PoiCategory.SUPERMARKET -> {
                val p = Path().apply { moveTo(-10f, -8f); lineTo(-7f, -8f); lineTo(-4f, 4f); lineTo(7f, 4f) }
                c.drawPath(p, s)
                val b = Path().apply { moveTo(-6f, -5f); lineTo(9f, -5f); lineTo(7f, 1f); lineTo(-4.5f, 1f); close() }
                c.drawPath(b, f)
                c.drawCircle(-3f, 8f, 1.8f, f)
                c.drawCircle(6f, 8f, 1.8f, f)
            }
            PoiCategory.PHARMACY -> {
                val g = fill(0xFF2E7D32.toInt())
                c.drawRoundRect(RectF(-3f, -9f, 3f, 9f), 1f, 1f, g)
                c.drawRoundRect(RectF(-9f, -3f, 9f, 3f), 1f, 1f, g)
            }
            PoiCategory.BANK -> text(c, "$", 21f, GLYPH)
            PoiCategory.PARKING -> text(c, "P", 21f, GLYPH)
            PoiCategory.HEALTH -> {
                // asterisco médico rojo
                val r = fill(0xFFC62828.toInt())
                for (a in 0 until 3) {
                    c.save(); c.rotate(a * 60f)
                    c.drawRoundRect(RectF(-2.6f, -10f, 2.6f, 10f), 1.5f, 1.5f, r)
                    c.restore()
                }
            }
            PoiCategory.HOTEL -> {
                c.drawLine(-9f, -6f, -9f, 8f, s)
                c.drawRoundRect(RectF(-9f, 0f, 9f, 4.5f), 1f, 1f, f)
                c.drawLine(9f, 4f, 9f, 8f, s)
                c.drawCircle(-4.5f, -3f, 2.4f, f)
                c.drawRoundRect(RectF(-1f, -5f, 9f, 0f), 2f, 2f, f)
            }
            PoiCategory.MONUMENT -> {
                // obelisco sobre una base
                val o = Path().apply { moveTo(0f, -11f); lineTo(3f, -7f); lineTo(2.5f, 6f); lineTo(-2.5f, 6f); lineTo(-3f, -7f); close() }
                c.drawPath(o, f)
                c.drawRect(-7f, 7f, 7f, 10f, f)
            }
            PoiCategory.NIGHTCLUB -> {
                // nota musical
                c.drawCircle(-4f, 6f, 3.4f, f)
                c.drawCircle(6f, 3f, 3.4f, f)
                c.drawLine(-1f, 6f, -1f, -8f, s)
                c.drawLine(9f, 3f, 9f, -11f, s)
                c.drawPath(Path().apply { moveTo(-1f, -8f); lineTo(9f, -11f); lineTo(9f, -7f); lineTo(-1f, -4f); close() }, f)
            }
            PoiCategory.POLICE -> {
                // escudo
                val sh = Path().apply {
                    moveTo(0f, -11f); lineTo(9f, -7f); lineTo(8f, 2f)
                    quadTo(6f, 8f, 0f, 11f); quadTo(-6f, 8f, -8f, 2f); lineTo(-9f, -7f); close()
                }
                c.drawPath(sh, f)
                c.drawCircle(0f, -1f, 3f, fill(mix(tile, Color.WHITE, 0.3f)))
            }
            PoiCategory.MALL -> {
                // bolsa de compras
                c.drawRoundRect(RectF(-8f, -4f, 8f, 10f), 2f, 2f, f)
                c.drawArc(RectF(-4.5f, -11f, 4.5f, -1f), 180f, 180f, false, s)
            }
            PoiCategory.CAR_REPAIR -> {
                c.save()
                c.rotate(45f)
                c.drawRoundRect(RectF(-1.8f, -3f, 1.8f, 11f), 1.5f, 1.5f, f)
                c.drawCircle(0f, -5f, 5.5f, f)
                c.drawRect(-1.8f, -12f, 1.8f, -5f, fill(mix(tile, Color.WHITE, 0.2f)))
                c.restore()
            }
        }
        return bmp
    }

    /** kind: "speed" (fijo), "average" (tramo), "redlight" (semáforo). */
    fun radar(kind: String, sizePx: Int): Bitmap {
        val (bmp, c) = canvasFor(sizePx)
        val ring = when (kind) {
            "average" -> 0xFF42A5F5.toInt()
            "redlight" -> 0xFFFFA726.toInt()
            else -> 0xFFE53935.toInt()
        }
        c.drawCircle(0f, 0f, 20f, fill(Color.WHITE))
        c.drawCircle(0f, 0f, 17.5f, stroke(ring, 4.5f))
        val dark = fill(0xFF1B2442.toInt())
        if (kind == "redlight") {
            c.drawRoundRect(RectF(-5f, -11f, 5f, 11f), 3f, 3f, dark)
            c.drawCircle(0f, -6.5f, 2.6f, fill(0xFFE53935.toInt()))
            c.drawCircle(0f, 0f, 2.6f, fill(0xFFFFCA28.toInt()))
            c.drawCircle(0f, 6.5f, 2.6f, fill(0xFF66BB6A.toInt()))
        } else {
            c.drawRoundRect(RectF(-10f, -5f, 4f, 6f), 2f, 2f, dark)
            val lens = Path().apply { moveTo(4f, -2f); lineTo(10f, -5f); lineTo(10f, 7f); lineTo(4f, 4f); close() }
            c.drawPath(lens, dark)
            c.drawCircle(-3f, 0.5f, 3.4f, fill(Color.WHITE))
            c.drawCircle(-3f, 0.5f, 1.8f, dark)
            if (kind == "average") c.drawRect(-10f, 8f, 10f, 10f, fill(ring))
        }
        return bmp
    }

    /** Flecha blanca dentro de un círculo, como la del auto en el estilo de referencia. */
    fun me(sizePx: Int): Bitmap {
        val (bmp, c) = canvasFor(sizePx)
        c.drawCircle(0f, 0f, 20f, fill(0x552FE6E9))
        c.drawCircle(0f, 0f, 14f, fill(0xFF26305A.toInt()))
        c.drawCircle(0f, 0f, 14f, stroke(Color.WHITE, 2f))
        val p = Path().apply { moveTo(0f, -10f); lineTo(7f, 8f); lineTo(0f, 4f); lineTo(-7f, 8f); close() }
        c.drawPath(p, fill(Color.WHITE))
        return bmp
    }

    /** Pin de destino. */
    fun destination(sizePx: Int, color: Int = 0xFF2FE6E9.toInt()): Bitmap {
        val (bmp, c) = canvasFor(sizePx)
        val p = Path().apply {
            moveTo(0f, 20f)
            cubicTo(-4f, 10f, -13f, 2f, -13f, -7f)
            arcTo(RectF(-13f, -20f, 13f, 6f), 180f, 180f)
            cubicTo(13f, 2f, 4f, 10f, 0f, 20f)
            close()
        }
        c.drawPath(p, fill(color))
        c.drawPath(p, stroke(0xFF131A2E.toInt(), 1.5f))
        c.drawCircle(0f, -7f, 5f, fill(0xFF131A2E.toInt()))
        return bmp
    }

    /** Flecha de la indicación (blanca, fondo transparente). */
    fun maneuver(type: com.radiohonda.radaruy.routing.Maneuver.Type, sizePx: Int, color: Int = Color.WHITE): Bitmap {
        val (bmp, c) = canvasFor(sizePx)
        val s = stroke(color, 4.2f)
        val f = fill(color)
        fun arrow(angle: Float) {
            // tallo desde abajo hasta el centro, luego hacia el ángulo indicado
            val rad = Math.toRadians(angle.toDouble())
            val ex = (kotlin.math.sin(rad) * 12).toFloat()
            val ey = (2 - kotlin.math.cos(rad) * 12).toFloat()
            val path = Path().apply { moveTo(0f, 18f); lineTo(0f, 2f); lineTo(ex, ey) }
            c.drawPath(path, s)
            c.save()
            c.translate(ex, ey)
            c.rotate(angle)
            c.drawPath(Path().apply { moveTo(0f, -6f); lineTo(6f, 2f); lineTo(-6f, 2f); close() }, f)
            c.restore()
        }
        when (type) {
            com.radiohonda.radaruy.routing.Maneuver.Type.DEPART,
            com.radiohonda.radaruy.routing.Maneuver.Type.STRAIGHT -> arrow(0f)
            com.radiohonda.radaruy.routing.Maneuver.Type.SLIGHT_LEFT,
            com.radiohonda.radaruy.routing.Maneuver.Type.RAMP_LEFT -> arrow(-45f)
            com.radiohonda.radaruy.routing.Maneuver.Type.SLIGHT_RIGHT,
            com.radiohonda.radaruy.routing.Maneuver.Type.RAMP_RIGHT -> arrow(45f)
            com.radiohonda.radaruy.routing.Maneuver.Type.LEFT -> arrow(-90f)
            com.radiohonda.radaruy.routing.Maneuver.Type.RIGHT -> arrow(90f)
            com.radiohonda.radaruy.routing.Maneuver.Type.SHARP_LEFT -> arrow(-135f)
            com.radiohonda.radaruy.routing.Maneuver.Type.SHARP_RIGHT -> arrow(135f)
            com.radiohonda.radaruy.routing.Maneuver.Type.UTURN -> {
                val path = Path().apply {
                    moveTo(6f, 18f); lineTo(6f, -4f)
                    arcTo(RectF(-6f, -10f, 6f, 2f), 0f, -180f)
                    lineTo(-6f, 8f)
                }
                c.drawPath(path, s)
                c.drawPath(Path().apply { moveTo(-6f, 15f); lineTo(-12f, 7f); lineTo(0f, 7f); close() }, f)
            }
            com.radiohonda.radaruy.routing.Maneuver.Type.ROUNDABOUT -> {
                c.drawCircle(0f, -2f, 7f, s)
                c.drawLine(0f, 18f, 0f, 5f, s)
                c.drawLine(5f, -7f, 11f, -13f, s)
                c.save(); c.translate(12f, -14f); c.rotate(45f)
                c.drawPath(Path().apply { moveTo(0f, -6f); lineTo(6f, 2f); lineTo(-6f, 2f); close() }, f)
                c.restore()
            }
            com.radiohonda.radaruy.routing.Maneuver.Type.ARRIVE -> {
                c.drawLine(-6f, 18f, -6f, -16f, s)
                val flag = Path().apply { moveTo(-6f, -16f); lineTo(12f, -10f); lineTo(-6f, -3f); close() }
                c.drawPath(flag, f)
            }
        }
        return bmp
    }

    /** Íconos de los botones de la interfaz (blancos sobre transparente). */
    enum class Ui { SEARCH, NEARBY, LAYERS, SOUND_ON, SOUND_OFF, VIEW_3D, VIEW_2D, RECENTER, CLOSE, CHECK, BACK, HOME, WORK, EDIT }

    fun ui(icon: Ui, sizePx: Int, color: Int = Color.WHITE): Bitmap {
        val (bmp, c) = canvasFor(sizePx)
        val s = stroke(color, 2.6f)
        val f = fill(color)
        when (icon) {
            Ui.SEARCH -> {
                c.drawCircle(-2.5f, -2.5f, 7f, s)
                c.drawLine(2.6f, 2.6f, 9f, 9f, stroke(color, 3.2f))
            }
            Ui.NEARBY -> {
                val p = Path().apply {
                    moveTo(0f, 11f); cubicTo(-3f, 6f, -8f, 1f, -8f, -4f)
                    arcTo(RectF(-8f, -12f, 8f, 4f), 180f, 180f)
                    cubicTo(8f, 1f, 3f, 6f, 0f, 11f); close()
                }
                c.drawPath(p, s)
                c.drawCircle(0f, -4f, 2.6f, f)
            }
            Ui.LAYERS -> {
                fun diamond(y: Float, paint: Paint) = c.drawPath(Path().apply {
                    moveTo(0f, y - 6f); lineTo(10f, y); lineTo(0f, y + 6f); lineTo(-10f, y); close()
                }, paint)
                diamond(4f, s)
                diamond(-3f, f)
            }
            Ui.SOUND_ON, Ui.SOUND_OFF -> {
                c.drawPath(Path().apply {
                    moveTo(-10f, -4f); lineTo(-5f, -4f); lineTo(1f, -9f); lineTo(1f, 9f); lineTo(-5f, 4f); lineTo(-10f, 4f); close()
                }, f)
                if (icon == Ui.SOUND_ON) {
                    c.drawArc(RectF(-2f, -6f, 8f, 6f), -50f, 100f, false, s)
                    c.drawArc(RectF(-2f, -10f, 12f, 10f), -50f, 100f, false, s)
                } else {
                    c.drawLine(4f, -5f, 11f, 5f, s)
                    c.drawLine(11f, -5f, 4f, 5f, s)
                }
            }
            Ui.VIEW_3D -> {
                // cubo
                val top = Path().apply { moveTo(0f, -10f); lineTo(9f, -5f); lineTo(0f, 0f); lineTo(-9f, -5f); close() }
                c.drawPath(top, f)
                c.drawPath(Path().apply { moveTo(-9f, -5f); lineTo(-9f, 6f); lineTo(0f, 11f); lineTo(9f, 6f); lineTo(9f, -5f) }, s)
                c.drawLine(0f, 0f, 0f, 11f, s)
            }
            Ui.VIEW_2D -> {
                c.drawRoundRect(RectF(-9f, -9f, 9f, 9f), 2f, 2f, s)
                c.drawLine(-9f, 0f, 9f, 0f, s)
                c.drawLine(0f, -9f, 0f, 9f, s)
            }
            Ui.RECENTER -> {
                c.drawPath(Path().apply { moveTo(0f, -10f); lineTo(8f, 9f); lineTo(0f, 4f); lineTo(-8f, 9f); close() }, f)
            }
            Ui.CLOSE -> {
                c.drawLine(-7f, -7f, 7f, 7f, stroke(color, 3.2f))
                c.drawLine(7f, -7f, -7f, 7f, stroke(color, 3.2f))
            }
            Ui.HOME -> {
                c.drawPath(Path().apply { moveTo(-11f, -1f); lineTo(0f, -11f); lineTo(11f, -1f) }, stroke(color, 3f))
                c.drawPath(Path().apply {
                    moveTo(-7.5f, -3f); lineTo(-7.5f, 10f); lineTo(-2.5f, 10f); lineTo(-2.5f, 3f)
                    lineTo(2.5f, 3f); lineTo(2.5f, 10f); lineTo(7.5f, 10f); lineTo(7.5f, -3f); lineTo(0f, -9.5f); close()
                }, f)
            }
            Ui.WORK -> {
                c.drawRoundRect(RectF(-11f, -5f, 11f, 10f), 2.5f, 2.5f, f)
                c.drawRoundRect(RectF(-4.5f, -10f, 4.5f, -3f), 2f, 2f, s)
                c.drawRect(-11f, 1f, 11f, 2.6f, fill(0xFF1B2442.toInt()))
            }
            Ui.EDIT -> {
                c.save(); c.rotate(45f)
                c.drawRoundRect(RectF(-2.8f, -11f, 2.8f, 6f), 1f, 1f, f)
                c.drawPath(Path().apply { moveTo(-2.8f, 8f); lineTo(2.8f, 8f); lineTo(0f, 12f); close() }, f)
                c.restore()
            }
            Ui.CHECK -> c.drawPath(Path().apply { moveTo(-8f, 0f); lineTo(-2f, 6f); lineTo(9f, -6f) }, stroke(color, 3.4f))
            Ui.BACK -> c.drawPath(Path().apply { moveTo(4f, -9f); lineTo(-5f, 0f); lineTo(4f, 9f) }, stroke(color, 3.2f))
        }
        return bmp
    }
}
