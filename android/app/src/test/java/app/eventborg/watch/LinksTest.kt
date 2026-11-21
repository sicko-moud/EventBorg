package app.eventborg.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinksTest {
    private fun profile(s: String) = parseLink(s) as? ProfileLink
    private fun group(s: String) = parseLink(s) as? GroupLink

    @Test fun instagramProfile() {
        val p = profile("https://www.instagram.com/Mejeriet.Sonderborg?igsh=MXZ0bnB5")!!
        assertEquals(IG, p.net)
        assertEquals("mejeriet.sonderborg", p.handle)
        assertEquals("https://www.instagram.com/mejeriet.sonderborg/", p.url)
        assertNull(p.name)
    }

    @Test fun instagramShareTextWithName() {
        val p = profile("Check out Alsion Koncertsal (@alsion_dk) on Instagram: https://instagram.com/alsion_dk")!!
        assertEquals("alsion_dk", p.handle)
        assertEquals("Alsion Koncertsal", p.name)
    }

    @Test fun instagramBareAndStories() {
        assertEquals("sonderborghus", profile("follow instagram.com/sonderborghus.")!!.handle)
        assertEquals("sdu_sonderborg", profile("https://www.instagram.com/stories/sdu_sonderborg/3412345/")!!.handle)
        assertEquals("abc", profile("https://instagram.com/_u/abc")!!.handle)
    }

    @Test fun instagramNonProfiles() {
        assertNull(parseLink("https://www.instagram.com/p/C1x2y3z/"))
        assertNull(parseLink("https://www.instagram.com/reel/C1x2y3z/?igsh=1"))
        assertNull(parseLink("https://www.instagram.com/explore/tags/sonderborg/"))
        assertNull(parseLink("https://www.instagram.com/stories/highlights/1789/"))
        assertNull(parseLink("https://www.instagram.com/"))
    }

    @Test fun facebookPages() {
        val p = profile("https://m.facebook.com/MejerietSonderborg/?ref=share")!!
        assertEquals(FB, p.net)
        assertEquals("mejerietsonderborg", p.handle)
        assertEquals("https://www.facebook.com/mejerietsonderborg", p.url)
        assertEquals("100064123456789", profile("https://www.facebook.com/profile.php?id=100064123456789&mibextid=ZbWKwL")!!.handle)
        val people = profile("https://www.facebook.com/people/Studenterhuset-Sønderborg/100090000000001/")!!
        assertEquals("100090000000001", people.handle)
        assertEquals("Studenterhuset Sønderborg", people.name)
        assertEquals("cafeabc", profile("fb.com/pg/CafeAbc/about")!!.handle)
    }

    @Test fun facebookShareLinks() {
        val p = profile("https://www.facebook.com/share/1AbCdEfGh/?mibextid=wwXIfr")!!
        assertNull(p.handle)
        assertEquals("https://www.facebook.com/share/1AbCdEfGh/", p.url)
        val g = group("https://www.facebook.com/share/g/15XyZ/")!!
        assertEquals("share/g/15XyZ", g.id)
        assertNull(parseLink("https://www.facebook.com/share/p/1Q2w3E/"))
        assertNull(parseLink("https://www.facebook.com/share/r/1Q2w3E/"))
    }

    @Test fun facebookGroups() {
        val g = group("Join us https://www.facebook.com/groups/SonderborgEvents/permalink/123/")!!
        assertEquals("sonderborgevents", g.id)
        assertEquals("https://www.facebook.com/groups/SonderborgEvents/", g.url)
        assertEquals("1234567890", group("https://facebook.com/groups/1234567890")!!.id)
    }

    @Test fun watchShareOnlyForLinkShares() {
        assertEquals(true, isWatchShare("Check out Alsion (@alsion_dk) on Instagram: https://instagram.com/alsion_dk"))
        assertEquals(true, isWatchShare("https://www.facebook.com/groups/SonderborgEvents/"))
        assertEquals(false, isWatchShare("Koncert lørdag 13. december kl. 20 på Mejeriet med lokale bands, billetter i døren 150 kr. Alle er velkomne. Mere info og billetter: https://instagram.com/mejeriet"))
        assertEquals(false, isWatchShare("https://www.instagram.com/p/C1x2y3z/"))
    }

    @Test fun facebookNonProfiles() {
        assertNull(parseLink("https://www.facebook.com/events/123456/"))
        assertNull(parseLink("https://www.facebook.com/watch/?v=1"))
        assertNull(parseLink("https://www.facebook.com/photo.php?fbid=1"))
        assertNull(parseLink("https://www.facebook.com/"))
        assertNull(parseLink("https://example.com/instagram"))
        assertNull(parseLink("no link here"))
    }
}
