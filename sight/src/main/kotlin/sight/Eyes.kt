package sight

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit

/** Result of reading a photo with the on-device text recognizer. */
class ReadResult(val text: String, val hint: String?, val dark: Boolean)

/**
 * Everything that looks at a photo, all on the phone:
 * - ML Kit text recognition (bundled model, offline) for reading.
 * - Gemma 3n through MediaPipe's LLM runtime for describing scenes and answering questions.
 * Call from a background thread.
 */
class Eyes(context: Context) {
    private val app = context.applicationContext
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var llm: LlmInference? = null

    val modelFile: File get() = File(app.filesDir, MODEL_NAME)
    val modelInstalled: Boolean get() = modelFile.isFile && modelFile.length() > 100L * 1024 * 1024
    val modelLoaded: Boolean get() = llm != null

    fun decode(jpeg: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)

    fun read(bitmap: Bitmap): ReadResult {
        val text = try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 15, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "ocr-failed " + t.javaClass.simpleName)
            null
        }
        val boxes = text?.textBlocks.orEmpty().mapNotNull { b ->
            val r = b.boundingBox ?: return@mapNotNull null
            TextBox(b.text, r.left, r.top, r.right, r.bottom)
        }
        return ReadResult(
            text = Reading.text(boxes),
            hint = Reading.framingHint(boxes, bitmap.width, bitmap.height),
            dark = isDark(bitmap),
        )
    }

    /** Loads Gemma (a few seconds the first time). Returns an error message, or null when ready. */
    @Synchronized
    fun loadModel(): String? {
        if (llm != null) return null
        if (!modelInstalled) return "The scene model isn't installed yet."
        val base = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(1_024)
            .setMaxNumImages(1)
        for (backend in listOf(LlmInference.Backend.GPU, LlmInference.Backend.CPU)) {
            try {
                llm = LlmInference.createFromOptions(app, base.setPreferredBackend(backend).build())
                Log.i(LOG_TAG, "gemma-loaded backend=$backend")
                return null
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "gemma-load-failed backend=$backend " + t.javaClass.simpleName + ": " + t.message)
            }
        }
        return "The scene model couldn't start."
    }

    /** Asks Gemma about the photo. Returns the answer, or null on failure. */
    @Synchronized
    fun ask(bitmap: Bitmap, prompt: String): String? {
        val model = llm ?: return null
        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(10)
            .setTemperature(0.3f)
            .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
            .build()
        return try {
            LlmInferenceSession.createFromOptions(model, options).use { session ->
                session.addQueryChunk(prompt)
                session.addImage(BitmapImageBuilder(scaled(bitmap, 768)).build())
                session.generateResponse().trim().ifEmpty { null }
            }
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "gemma-failed " + t.javaClass.simpleName + ": " + t.message)
            null
        }
    }

    @Synchronized
    fun close() {
        runCatching { llm?.close() }
        llm = null
        runCatching { recognizer.close() }
    }

    private fun scaled(b: Bitmap, maxSide: Int): Bitmap {
        val side = maxOf(b.width, b.height)
        if (side <= maxSide) return b
        val f = maxSide.toFloat() / side
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    /** Average brightness of a small copy of the photo, 0..255. */
    private fun isDark(b: Bitmap): Boolean {
        val s = Bitmap.createScaledBitmap(b, 32, 24, true)
        var sum = 0L
        for (y in 0 until s.height) for (x in 0 until s.width) {
            val c = s.getPixel(x, y)
            sum += (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        return sum / (s.width * s.height) < 35
    }

    companion object {
        const val MODEL_NAME = "gemma.task"
    }
}
