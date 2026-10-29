package sight

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android's built-in text-to-speech, played as media so it goes to the earbuds.
 * Speech is generated on the phone (with an offline voice installed, nothing goes online).
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false
    private val queued = mutableListOf<Pair<String, (() -> Unit)?>>()
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val ids = AtomicInteger()
    @Volatile var isSpeaking = false
        private set
    private var rate = 1.0f

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(LOG_TAG, "tts-init-failed $status")
            return
        }
        tts.language = Locale.US
        tts.setSpeechRate(rate)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                isSpeaking = true
            }

            override fun onDone(utteranceId: String) {
                isSpeaking = tts.isSpeaking
                callbacks.remove(utteranceId)?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                isSpeaking = false
                callbacks.remove(utteranceId)?.invoke()
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                isSpeaking = false
                callbacks.remove(utteranceId)
            }
        })
        synchronized(queued) {
            ready = true
            queued.forEachIndexed { i, (t, then) -> speakNow(t, i == 0, then) }
            queued.clear()
        }
    }

    fun setRate(r: Float) {
        rate = r
        if (ready) tts.setSpeechRate(r)
    }

    /** Speak [text], replacing anything being said. [then] runs after the last piece finishes. */
    fun say(text: String, then: (() -> Unit)? = null) = speak(text, flush = true, then = then)

    /** Speak [text] after whatever is being said now. */
    fun add(text: String, then: (() -> Unit)? = null) = speak(text, flush = false, then = then)

    private fun speak(text: String, flush: Boolean, then: (() -> Unit)?) {
        synchronized(queued) {
            if (!ready) {
                if (flush) queued.clear()
                queued.add(text to then)
                return
            }
        }
        speakNow(text, flush, then)
    }

    private fun speakNow(text: String, flush: Boolean, then: (() -> Unit)?) {
        val pieces = Reading.chunks(text, maxOf(200, minOf(3_000, TextToSpeech.getMaxSpeechInputLength() - 100)))
        pieces.forEachIndexed { i, piece ->
            val id = "u" + ids.incrementAndGet()
            if (i == pieces.lastIndex && then != null) callbacks[id] = then
            val mode = if (flush && i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(piece, mode, Bundle(), id)
        }
    }

    fun stop() {
        callbacks.clear()
        synchronized(queued) { queued.clear() }
        if (ready) tts.stop()
        isSpeaking = false
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}
