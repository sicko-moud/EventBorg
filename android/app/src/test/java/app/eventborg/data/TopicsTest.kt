package app.eventborg.data

import app.eventborg.watch.Profile
import app.eventborg.watch.WatchState
import org.junit.Assert.assertEquals
import org.junit.Test

class TopicsTest {
    private val watch = WatchState(profiles = listOf(Profile("ig", "mejerietsonderborg", "Mejeriet", "u", src = "mejeriet")))

    @Test fun favouritesModeAddsWatchedAndPrioritySources() {
        val p = UserPrefs(loaded = true, notify = NOTIFY_FAV, stories = NOTIFY_FAV, favSrcs = setOf("alsion"), favCats = setOf("music"))
        assertEquals(
            setOf("c_music", "s_alsion", "s_mejeriet", "s_hk", "st_alsion", "st_mejeriet", "st_hk"),
            wantedTopics(p, watch, setOf("hk")),
        )
    }

    @Test fun sourceSwitchesAndMuteRemoveTopics() {
        val p = UserPrefs(loaded = true, notify = NOTIFY_FAV, stories = NOTIFY_FAV, srcOff = setOf("e:hk", "s:mejeriet"))
        assertEquals(setOf("s_mejeriet", "st_hk"), wantedTopics(p, watch, setOf("hk")))
        assertEquals(setOf("s_mejeriet", "s_hk"), wantedTopics(p.copy(srcOff = emptySet()), watch.copy(muted = setOf("mejeriet", "hk")), setOf("hk")))
    }

    @Test fun allAndOff() {
        assertEquals(setOf("all", "st_all"), wantedTopics(UserPrefs(notify = NOTIFY_ALL, stories = NOTIFY_ALL), watch, setOf("hk")))
        assertEquals(emptySet<String>(), wantedTopics(UserPrefs(notify = NOTIFY_OFF, stories = NOTIFY_OFF), watch, setOf("hk")))
    }
}
