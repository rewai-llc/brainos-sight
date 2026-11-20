package sight

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * One spoken question, recognized on the phone when the device supports it (Pixel phones do).
 * Otherwise the only option is the system recognizer, which may send the audio to its online
 * service even with "offline preferred"; [SightService] only uses that if the user has allowed it.
 * Must be used on the main thread.
 */
class Ears(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    /** True when this phone can recognize speech entirely on the device (Android 12+, model installed). */
    val onDevice: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Some recognizer exists, on the phone or through the system's (possibly online) speech service. */
    val available: Boolean get() = onDevice || SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(onResult: (String?) -> Unit) {
        cancel()
        val sr = if (Build.VERSION.SDK_INT >= 31 && onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = sr
        var done = false
        fun finish(text: String?) {
            if (done) return
            done = true
            cancel()
            onResult(text)
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                finish(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }

            override fun onError(error: Int) {
                Log.i(LOG_TAG, "speech-error $error")
                finish(null)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            sr.startListening(intent)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "speech-start-failed " + t.javaClass.simpleName)
            finish(null)
        }
    }

    fun cancel() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }
}
