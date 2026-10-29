package sight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test fun readsRowsLeftToRightThenDown() {
        val boxes = listOf(
            TextBox("Salmon", 400, 210, 600, 240),
            TextBox("MENU", 200, 100, 400, 140),
            TextBox("\$28", 700, 212, 760, 238),
            TextBox("Soup", 400, 300, 560, 330),
        )
        assertEquals("MENU\nSalmon\n\$28\nSoup", Reading.text(boxes))
    }

    @Test fun framingHintsPointTheRightWay() {
        val w = 1600
        val h = 1200
        assertNull(Reading.framingHint(listOf(TextBox("a", 200, 200, 800, 260)), w, h))
        assertEquals(
            "Text continues to the right. Turn slightly right.",
            Reading.framingHint(listOf(TextBox("a", 200, 200, 1595, 260)), w, h),
        )
        assertEquals(
            "Text continues to the left. Turn slightly left.",
            Reading.framingHint(listOf(TextBox("a", 3, 200, 800, 260)), w, h),
        )
        assertTrue(Reading.framingHint(listOf(TextBox("a", 2, 200, 1599, 260)), w, h)!!.contains("Step back"))
        assertTrue(Reading.framingHint(listOf(TextBox("a", 200, 600, 800, 1199)), w, h)!!.contains("down"))
    }

    @Test fun longTextIsSplitWithoutLosingWords() {
        val text = (1..400).joinToString(" ") { "word$it" } + "\n" + "last line"
        val pieces = Reading.chunks(text, 500)
        assertTrue(pieces.all { it.length <= 500 })
        assertEquals(text.replace('\n', ' ').split(' ').filter { it.isNotEmpty() },
            pieces.joinToString(" ").replace('\n', ' ').split(' ').filter { it.isNotEmpty() })
    }

    @Test fun spokenRequestsMapToActions() {
        assertEquals(Request.Read, Requests.parse("Read this"))
        assertEquals(Request.Read, Requests.parse("read"))
        assertEquals(Request.Read, Requests.parse("What does it say?".removeSuffix("?")))
        assertEquals(Request.Describe, Requests.parse("What's in front of me"))
        assertEquals(Request.Describe, Requests.parse("describe"))
        val q = Requests.parse("How much is the salmon")
        assertTrue(q is Request.Question)
        assertEquals("How much is the salmon", (q as Request.Question).text)
        assertNull(Requests.parse("  "))
    }

    @Test fun promptsCarryTheQuestionAndText() {
        val p = Prompts.question("How much is the salmon?", "Salmon \$28")
        assertTrue(p.contains("How much is the salmon?"))
        assertTrue(p.contains("Salmon \$28"))
        assertTrue(p.contains("not a mobility aid"))
        assertTrue(!Prompts.describe("").contains("text reader found"))
    }
}
