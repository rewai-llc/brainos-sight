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
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import java.io.File
import java.util.concurrent.CountDownLatch
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
        // The camera can sit at any angle on the headset, and people hold pages any which way:
        // if nothing is found upright, try the photo turned a quarter, three quarters and half way.
        for (rotation in intArrayOf(0, 90, 270, 180)) {
            val text = try {
                Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, rotation)), 15, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "ocr-failed " + t.javaClass.simpleName)
                null
            }
            val blocks = text?.textBlocks.orEmpty()
            Log.i(LOG_TAG, "ocr rotation=$rotation blocks=${blocks.size} size=${bitmap.width}x${bitmap.height}")
            if (blocks.isEmpty()) continue
            // Boxes come back in the rotated frame, so reading order and framing hints still work.
            val (w, h) = if (rotation == 90 || rotation == 270) bitmap.height to bitmap.width else bitmap.width to bitmap.height
            val boxes = blocks.mapNotNull { b ->
                val r = b.boundingBox ?: return@mapNotNull null
                TextBox(b.text, r.left, r.top, r.right, r.bottom)
            }
            return ReadResult(Reading.text(boxes), Reading.framingHint(boxes, w, h), isDark(bitmap))
        }
        return ReadResult(text = "", hint = null, dark = isDark(bitmap))
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

    /**
     * Asks Gemma about the photo and hands back each sentence as soon as it is written, so it
     * can be spoken while the rest is still being generated. Returns the full answer, or null.
     */
    @Synchronized
    fun askStreaming(bitmap: Bitmap, prompt: String, onSentence: (String) -> Unit): String? {
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
                val stream = SentenceStream()
                val done = CountDownLatch(1)
                session.generateResponseAsync(
                    ProgressListener<String> { partial, finished ->
                        stream.feed(partial ?: "").forEach(onSentence)
                        if (finished) {
                            stream.finish()?.let(onSentence)
                            done.countDown()
                        }
                    },
                )
                if (!done.await(90, TimeUnit.SECONDS)) {
                    runCatching { session.cancelGenerateResponseAsync() }
                    Log.w(LOG_TAG, "gemma-timeout")
                }
                stream.text.ifEmpty { null }
            }
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "gemma-stream-failed " + t.javaClass.simpleName + ": " + t.message)
            null
        }
    }

    /** "Mostly navy blue, with some white." from the middle of the photo, where people point. */
    fun colors(bitmap: Bitmap): String {
        val w = bitmap.width
        val h = bitmap.height
        val center = Bitmap.createBitmap(bitmap, w / 4, h / 4, w / 2, h / 2)
        val small = Bitmap.createScaledBitmap(center, 40, 30, true)
        val counts = HashMap<String, Int>()
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            val n = Colors.name(Color.red(c), Color.green(c), Color.blue(c))
            counts[n] = (counts[n] ?: 0) + 1
        }
        val total = (small.width * small.height).toFloat()
        val shares = counts.entries.sortedByDescending { it.value }.map { it.key to it.value / total }
        if (center != bitmap) center.recycle()
        small.recycle()
        return Colors.sentence(shares)
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
        val avg = sum / (s.width * s.height)
        if (s != b) s.recycle()
        return avg < 35
    }

    companion object {
        const val MODEL_NAME = "gemma.task"
    }
}
