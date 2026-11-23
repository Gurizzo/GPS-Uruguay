package com.radiohonda.radaruy.routing

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Indicaciones habladas en español. Baja el volumen de Spotify mientras habla
 * (como Google Maps / Waze) y lo devuelve al terminar.
 */
object Voice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private lateinit var audio: AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs).build()
    }
    private var pending: String? = null

    fun init(context: Context) {
        if (tts != null) return
        val app = context.applicationContext
        audio = app.getSystemService(AudioManager::class.java)
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                val r = t.setLanguage(Locale("es", "UY"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    t.setLanguage(Locale("es"))
                }
                t.setAudioAttributes(attrs)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { audio.abandonAudioFocusRequest(focus) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { audio.abandonAudioFocusRequest(focus) }
                })
                ready = true
                pending?.let { say(it) }
                pending = null
            }
        }
    }

    fun say(text: String) {
        val t = tts
        if (t == null || !ready) { pending = text; return }
        audio.requestAudioFocus(focus)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav")
    }
}
