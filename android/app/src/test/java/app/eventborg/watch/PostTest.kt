package app.eventborg.watch

import app.eventborg.data.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostTest {
    private val hk = Target("hk", "HK", "u", "hk", listOf("hkctutten", "HK"))
    private val kommune = Target("sonderborg-dk", "Sønderborg Kommune", "u", "sonderborg-dk", listOf("sonderborgkommune", "Sønderborg Kommune"))
    private val targets = listOf(hk, kommune)

    @Test fun instagramPosts() {
        assertEquals(hk, matchPost("Instagram", "hkctutten shared a post.", targets))
        assertEquals(hk, matchPost("Instagram", "hkctutten just shared a photo", targets))
        assertEquals(hk, matchPost("hkctutten", "delte et opslag: Fredagsbar i aften!", targets))
        assertEquals(hk, matchPost("Instagram", "hkctutten har lige delt et opslag.", targets))
        assertEquals(hk, matchPost("Instagram", "hkctutten hat einen Beitrag geteilt.", targets))
        assertEquals(hk, matchPost("Instagram", "hkctutten posted 3 photos", targets))
    }

    @Test fun facebookPagePosts() {
        assertEquals(kommune, matchPost("Facebook", "Sønderborg Kommune tilføjede et nyt billede.", targets))
        assertEquals(kommune, matchPost("Facebook", "Sønderborg Kommune shared a post.", targets))
    }

    @Test fun notNewPosts() {
        assertNull(matchPost("Instagram", "hkctutten liked your post.", targets))
        assertNull(matchPost("Instagram", "hkctutten commented on your post: nice", targets))
        assertNull(matchPost("Instagram", "hkctutten shared your post to their story", targets))
        assertNull(matchPost("Facebook", "Anna posted in Sønderborg Kommune Borgere: new post", targets))
        assertNull(matchPost("Instagram", "hkctutten added to their story", targets))
        assertNull(matchPost("Instagram", "someone_else shared a post.", targets))
        assertNull(matchPost("Instagram", "hkctutten started following you", targets))
    }

    @Test fun storiesStayStories() {
        assertEquals(hk, matchStory("Instagram", "hkctutten har lige delt en ny historie", targets))
        assertNull(matchPost("Instagram", "hkctutten har lige delt en ny historie", targets))
    }

    @Test fun cleanUrls() {
        assertEquals("https://www.instagram.com/hkctutten/", cleanProfileUrl("https://www.instagram.com/hkctutten/?igsh=abc&utm_source=qr"))
        assertEquals("https://www.facebook.com/gotosonderborg", cleanProfileUrl("https://www.facebook.com/gotosonderborg?mibextid=ZbWKwL"))
        assertEquals("https://www.facebook.com/profile.php?id=123456", cleanProfileUrl("https://www.facebook.com/profile.php?ref=x&id=123456"))
        assertEquals("https://www.instagram.com/hk/", cleanProfileUrl("https://www.instagram.com/hk/"))
        assertFalse((parseLink("instagram.com/hkctutten?igsh=MXZ0") as ProfileLink).url.contains('?'))
    }

    @Test fun seedAddsMarkedSourcesOnce() {
        val src = listOf(
            Source("hk", "HK", kind = "organizer", ig = "hkctutten", dw = true, pri = true),
            Source("web", "Web only", kind = "venue", dw = true),
            Source("other", "Other", kind = "venue", ig = "other"),
        )
        val fresh = src.filter { it.dw && storyEligible(it) }
        assertEquals(listOf("hk"), fresh.map { it.id })
        assertTrue(profileFor(fresh[0]).url == "https://www.instagram.com/hkctutten/")
    }
}
