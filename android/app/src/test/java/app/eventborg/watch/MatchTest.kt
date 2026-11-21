package app.eventborg.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchTest {
    private val mejeriet = Target("mejeriet", "Mejeriet", "u", "mejeriet", listOf("mejerietsonderborg", "Mejeriet"))
    private val mejerietBar = Target("w:ig:mejeriet.bar", "Mejeriet Bar", "u", null, listOf("mejeriet.bar", "Mejeriet Bar"))
    private val alsion = Target("alsion", "Alsion", "u", "alsion", listOf("alsion_dk", "Koncertsalen Alsion", "Alsion"))
    private val sdu = Target("w:ig:sdu", "SDU", "u", null, listOf("sdu"))
    private val targets = listOf(mejeriet, mejerietBar, alsion, sdu)

    @Test fun instagramStory() {
        assertEquals(mejeriet, matchStory("Instagram", "mejerietsonderborg just added to their story.", targets))
        assertEquals(alsion, matchStory("alsion_dk", "har lige delt en ny historie", targets))
        assertEquals(alsion, matchStory("", "Koncertsalen Alsion shared a story", targets))
    }

    @Test fun facebookStoryDanish() {
        assertEquals(mejeriet, matchStory("Facebook", "Mejeriet har tilføjet til sin historie.", targets))
    }

    @Test fun longestNameWins() {
        assertEquals(mejerietBar, matchStory("Instagram", "Mejeriet Bar posted a story", targets))
        assertEquals(mejerietBar, matchStory("Instagram", "mejeriet.bar added to their story", targets))
    }

    @Test fun ignoresOtherNotifications() {
        assertNull(matchStory("Instagram", "mejerietsonderborg liked your photo", targets))
        assertNull(matchStory("Instagram", "Mejeriet replied to your story", targets))
        assertNull(matchStory("Facebook", "Mejeriet kommenterede din historie", targets))
        assertNull(matchStory("Instagram", "someone_else added to their story", targets))
        assertNull(matchStory("Instagram", "Mejerietsonderborgfan added to their story", targets))
    }

    @Test fun shortNeedlesIgnored() {
        assertNull(matchStory("Instagram", "sdu added to their story", targets))
    }

    @Test fun groupMatch() {
        val g = GroupTarget("sonderborgevents", "Sønderborg Events", "u")
        val other = GroupTarget("loppe", "Loppemarked Als", "u")
        assertEquals(g, matchGroup("Sønderborg Events", "Anna Jensen: Koncert på lørdag", listOf(g, other)))
        assertEquals(g, matchGroup("Facebook", "Anna posted in Sonderborg Events: \"Quiz i aften\"", listOf(g, other)))
        assertNull(matchGroup("Facebook", "Anna commented on your post", listOf(g, other)))
    }

    @Test fun snippetDropsPoster() {
        assertEquals("Koncert lørdag kl. 20", postSnippet("Anna Jensen posted in Sønderborg Events: \"Koncert lørdag kl. 20\"", "Sønderborg Events"))
        assertEquals("Loppemarked søndag 10-14", postSnippet("Anna Jensen: Loppemarked søndag 10-14", "Andet navn"))
        assertEquals("", postSnippet("Anna Jensen har slået noget op i Sønderborg Events", "Sønderborg Events"))
        assertEquals("Fest kl. 20: alle er velkomne", postSnippet("Fest kl. 20: alle er velkomne", "X"))
    }

    @Test fun eventLike() {
        assertTrue(looksLikeEvent("Koncert på lørdag kl. 20 i Mejeriet"))
        assertTrue(looksLikeEvent("Loppemarked søndag"))
        assertTrue(looksLikeEvent("Julemarked 13. december 10-16"))
        assertTrue(looksLikeEvent("Tickets for Saturday's gig are on sale now"))
        assertTrue(looksLikeEvent("Heute Abend Konzert im Park, Eintritt frei"))
        assertTrue(looksLikeEvent("Husk generalforsamling 12/3 kl 19"))
        assertTrue(looksLikeEvent("Fællesspisning i aften, 40 kr"))
        assertTrue(looksLikeEvent("Quiz night 7pm, 50 kr"))
        assertTrue(looksLikeEvent("Åbent hus 2026-12-05"))
    }

    @Test fun notEventLike() {
        assertFalse(looksLikeEvent("Sælger barnevogn, 500 kr, kan afhentes lørdag"))
        assertFalse(looksLikeEvent("Er der nogen der har set en sort kat i dag?"))
        assertFalse(looksLikeEvent("Velkommen til alle nye medlemmer!"))
        assertFalse(looksLikeEvent("Mødes vi i morgen kl. 10?"))
        assertFalse(looksLikeEvent("Gratis sofa, skal hentes"))
        assertFalse(looksLikeEvent(""))
    }
}
