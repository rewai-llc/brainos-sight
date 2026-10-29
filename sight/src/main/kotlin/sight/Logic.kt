package sight

/**
 * Pure logic with no Android dependencies, so it can be unit-tested:
 * reading order, framing hints, what a spoken request means, and the prompts for Gemma.
 */
const val LOG_TAG = "BrainOS-Sight"

/** One block of recognized text and where it sits in the photo (pixels). */
data class TextBox(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val centerY: Int get() = (top + bottom) / 2
}

object Reading {
    /**
     * Puts text blocks in reading order: top to bottom, and left to right within a row.
     * Blocks whose vertical centers are close (within about half a line) count as one row.
     */
    fun order(boxes: List<TextBox>): List<TextBox> {
        if (boxes.size < 2) return boxes
        val byY = boxes.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<TextBox>>()
        for (b in byY) {
            val row = rows.lastOrNull()
            if (row != null) {
                val rowY = row.sumOf { it.centerY } / row.size
                val rowH = row.sumOf { it.height } / row.size
                if (kotlin.math.abs(b.centerY - rowY) <= maxOf(rowH, b.height) / 2) {
                    row.add(b)
                    continue
                }
            }
            rows.add(mutableListOf(b))
        }
        return rows.flatMap { r -> r.sortedBy { it.left } }
    }

    /** The text to speak, in reading order, one block per line. */
    fun text(boxes: List<TextBox>): String =
        order(boxes).map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    /**
     * If text touches an edge of the photo, part of it is probably cut off.
     * Returns a short spoken hint telling the wearer which way to turn, or null.
     * The camera faces forward, so the photo's right is the wearer's right.
     */
    fun framingHint(boxes: List<TextBox>, width: Int, height: Int): String? {
        if (boxes.isEmpty() || width <= 0 || height <= 0) return null
        val mx = width * 0.02
        val my = height * 0.02
        val cutLeft = boxes.any { it.left <= mx }
        val cutRight = boxes.any { it.right >= width - mx }
        val cutTop = boxes.any { it.top <= my }
        val cutBottom = boxes.any { it.bottom >= height - my }
        return when {
            cutLeft && cutRight -> "The text is wider than the view. Step back a little."
            cutRight -> "Text continues to the right. Turn slightly right."
            cutLeft -> "Text continues to the left. Turn slightly left."
            cutTop && cutBottom -> "The text is taller than the view. Step back a little."
            cutBottom -> "Text continues below. Tilt your head down a little."
            cutTop -> "Text continues above. Tilt your head up a little."
            else -> null
        }
    }

    /**
     * Splits long text into pieces the speech engine accepts, breaking at line ends
     * (or spaces) so no word is cut.
     */
    fun chunks(text: String, max: Int = 3_000): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (line in text.split('\n')) {
            var rest = line
            while (rest.length > max) {
                val cut = rest.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
                if (sb.isNotEmpty()) {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                out.add(rest.substring(0, cut))
                rest = rest.substring(cut).trimStart()
            }
            if (sb.length + rest.length + 1 > max && sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(rest)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}

/** What the wearer asked for. */
sealed class Request {
    data object Read : Request()
    data object Describe : Request()
    data class Question(val text: String) : Request()
}

object Requests {
    private val readWords = listOf("read", "what does it say", "what does this say", "what does that say", "text")
    private val describeWords = listOf(
        "describe", "what's in front", "what is in front", "what do you see", "what can you see",
        "where am i", "look around", "what's around", "what is around", "what's here",
    )

    /** Turns a spoken request into an action. Plain "read" / "describe" phrases map to those modes. */
    fun parse(spoken: String): Request? {
        val s = spoken.trim().lowercase().removePrefix("brainos").trim(' ', ',', '.')
        if (s.isEmpty()) return null
        if (readWords.any { s == it || s.startsWith("$it ") || s.startsWith("please $it") || s.startsWith("can you $it") }) {
            return Request.Read
        }
        if (describeWords.any { s.contains(it) } && s.split(' ').size <= 6) return Request.Describe
        return Request.Question(spoken.trim())
    }
}

object Prompts {
    private const val ROLE =
        "You are BrainOS Sight, helping a person who is blind or has low vision. " +
            "They are wearing a small camera that took this photo facing where they face. " +
            "Your words are spoken aloud in their earbud, so be brief and concrete, and never say " +
            "\"the image shows\" or \"in this photo\". Use left, right, ahead, near and far. " +
            "Never claim a path is safe or clear; you are not a mobility aid."

    fun describe(ocrText: String): String = buildString {
        append(ROLE)
        append("\n\nDescribe what is in front of them in two or three short sentences, most important first: ")
        append("people, signs, doors, steps, and the main objects.")
        appendOcr(ocrText)
    }

    fun question(question: String, ocrText: String): String = buildString {
        append(ROLE)
        append("\n\nAnswer their question about what is in front of them in one or two short sentences. ")
        append("If the photo does not show the answer, say so plainly.")
        appendOcr(ocrText)
        append("\n\nTheir question: ").append(question.trim())
    }

    private fun StringBuilder.appendOcr(ocrText: String) {
        val t = ocrText.trim()
        if (t.isNotEmpty()) {
            append("\n\nText that a text reader found in the photo (accurate, but may be partial):\n")
            append(t.take(1_500))
        }
    }
}
