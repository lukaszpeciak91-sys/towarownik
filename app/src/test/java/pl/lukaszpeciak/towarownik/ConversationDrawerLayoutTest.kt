package pl.lukaszpeciak.towarownik

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationDrawerLayoutTest {
    @Test
    fun `drawer keeps a usable reveal on narrow phone windows`() {
        assertEquals(264, conversationDrawerWidthDp(320))
        assertEquals(304, conversationDrawerWidthDp(360))
    }

    @Test
    fun `drawer caps width on wider phone windows`() {
        assertEquals(320, conversationDrawerWidthDp(384))
        assertEquals(320, conversationDrawerWidthDp(412))
    }

    @Test
    fun `drawer never produces a negative width`() {
        assertEquals(0, conversationDrawerWidthDp(40))
    }
}
