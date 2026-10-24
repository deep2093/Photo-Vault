package com.deepak.photovault.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IntroLogicTest {
    private val now = WhatsNew.ID

    @Test
    fun aNewPersonGetsTheTourOnceAndNoWhatsNewAfterIt() {
        val first = IntroLogic.decide("", false, false, now)
        assertEquals(IntroKind.TOUR, first.show)
        val again = IntroLogic.decide(first.seenId, first.tourDone, false, now)
        assertNull(again.show)
    }

    @Test
    fun someoneWhoAlreadyUsedTheAppGetsWhatsNewNotTheTour() {
        val first = IntroLogic.decide("", false, true, now)
        assertEquals(IntroKind.WHATS_NEW, first.show)
        assertNull(IntroLogic.decide(first.seenId, first.tourDone, true, now).show)
    }

    @Test
    fun aNewListShowsAgainAfterAnUpdate() {
        assertEquals(IntroKind.WHATS_NEW, IntroLogic.decide("2026-01-01", true, false, now).show)
    }

    @Test
    fun introStateRemembersAndCanBeReopened() {
        val store = MemoryIntroStore(existing = false)
        val intro = IntroState(store)
        intro.start()
        assertEquals(IntroKind.TOUR, intro.showing)
        intro.close()
        intro.open(IntroKind.WHATS_NEW)
        assertEquals(IntroKind.WHATS_NEW, intro.showing)
        val next = IntroState(store)
        next.start()
        assertNull(next.showing)
    }
}
