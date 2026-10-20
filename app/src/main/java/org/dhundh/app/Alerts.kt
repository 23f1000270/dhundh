package org.dhundh.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import org.dhundh.core.HazardMessage
import org.dhundh.core.Relevance
import java.util.Locale

/** Sound, vibration and a spoken distance. Repeats at most every few seconds. */
class Alerts(context: Context) : TextToSpeech.OnInitListener {

    private val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val tts = TextToSpeech(context, this)
    private var ttsReady = false
    private var lastAt = 0L

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.ENGLISH) >= TextToSpeech.LANG_AVAILABLE
    }

    fun warn(type: HazardMessage.Type, v: Relevance.Verdict) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < 4000) return
        lastAt = now
        val urgent = v.level == Relevance.Level.URGENT
        tone.startTone(if (urgent) ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK else ToneGenerator.TONE_PROP_BEEP2, 1500)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 120, 300, 120, 300), -1))
        val metres = (v.distanceM / 10).toInt() * 10
        say("${label(type)}. $metres metres. Slow down.")
        Status.update { it.copy(lastEvent = "${label(type)}: $metres m ahead") }
    }

    fun test(distanceM: Double) {
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 300)
        if (!distanceM.isNaN()) say("Test received, ${distanceM.toInt()} metres.")
    }

    private fun say(text: String) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dhundh")
    }

    fun close() {
        tone.release()
        tts.shutdown()
    }
}
