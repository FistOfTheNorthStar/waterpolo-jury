package io.github.fistofthenorthstar.waterpolojury.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchTest {
    private val start = MatchState()

    @Test
    fun clocksRunTogetherAndStop() {
        var s = start.toggleRun(1_000)
        s = s.advance(6_000)
        assertEquals(475_000, s.gameMs)
        assertEquals(23_000, s.shotMs)
        s = s.toggleRun(7_000)
        assertFalse(s.running)
        assertEquals(474_000, s.gameMs)
        assertEquals(s, s.advance(60_000))
    }

    @Test
    fun shotClockExpiryStopsClockAndSoundsHorn() {
        val s = start.toggleRun(0).advance(40_000)
        assertFalse(s.running)
        assertEquals(0, s.shotMs)
        assertEquals(480_000 - 28_000, s.gameMs)
        assertEquals(1, s.hornSeq)
        // Starting again gives a fresh possession.
        assertEquals(28_000, s.toggleRun(41_000).shotMs)
    }

    @Test
    fun clockCanBeEditedWhileRunning() {
        var s = start.toggleRun(0)
        s = s.adjustGame(2_000, 5_000)
        assertTrue(s.running)
        assertEquals(483_000, s.gameMs)
        s = s.setShot(3_000, 18_000).advance(4_000)
        assertEquals(17_000, s.shotMs)
        assertEquals(481_000, s.gameMs)
    }

    @Test
    fun periodEndStartsIntervalAndNextPeriodResets() {
        var s = start.setGame(0, 5_000).toggleRun(0).advance(10_000)
        assertFalse(s.running)
        assertEquals(0, s.gameMs)
        assertEquals("INTERVAL", s.breakLabel)
        assertEquals(120_000, s.breakMs)
        s = s.nextPeriod(11_000)
        assertEquals(2, s.period)
        assertEquals(480_000, s.gameMs)
        assertEquals(0, s.breakMs)
        // End of period 2 of 4 is half time.
        s = s.setGame(12_000, 1_000).toggleRun(12_000).advance(20_000)
        assertEquals("HALF TIME", s.breakLabel)
    }

    @Test
    fun goalStopsClockResetsShotAndClearsExclusions() {
        var s = start.toggleRun(0)
        s = s.addEvent(15_000, EventType.EXCLUSION, Team.BLUE, 5)
        assertEquals(1, s.exclusions.size)
        assertEquals(18_000, s.shotMs) // 13 s left -> raised to 18
        s = s.addEvent(20_000, EventType.BRUTALITY, Team.BLUE, 9)
        s = s.addEvent(25_000, EventType.GOAL, Team.WHITE, 7)
        assertFalse(s.running)
        assertEquals(1, s.score(Team.WHITE))
        assertEquals(0, s.score(Team.BLUE))
        assertEquals(28_000, s.shotMs)
        assertEquals(listOf(9), s.exclusions.map { it.cap })
        assertEquals(455_000, s.events.last().gameMs)
    }

    @Test
    fun exclusionTimerRunsOnGameTimeOnly() {
        var s = start.addEvent(0, EventType.EXCLUSION, Team.WHITE, 3)
        s = s.advance(60_000)
        assertEquals(18_000, s.exclusions.single().remainingMs)
        s = s.toggleRun(60_000).advance(70_000)
        assertEquals(8_000, s.exclusions.single().remainingMs)
        s = s.advance(79_000)
        assertTrue(s.exclusions.isEmpty())
    }

    @Test
    fun personalFoulsAndFoulOut() {
        var s = start
        s = s.addEvent(0, EventType.EXCLUSION, Team.WHITE, 4)
        s = s.addEvent(0, EventType.PENALTY, Team.WHITE, 4)
        assertFalse(s.isOut(Team.WHITE, 4))
        s = s.addEvent(0, EventType.EXCLUSION, Team.WHITE, 4)
        assertEquals(3, s.fouls(Team.WHITE, 4))
        assertTrue(s.isOut(Team.WHITE, 4))
        assertFalse(s.isOut(Team.BLUE, 4))
        s = s.addEvent(0, EventType.BRUTALITY, Team.BLUE, 2)
        assertTrue(s.isOut(Team.BLUE, 2))
        assertEquals(240_000, s.exclusions.last().remainingMs)
    }

    @Test
    fun editAndDeleteEvents() {
        var s = start.addEvent(0, EventType.GOAL, Team.WHITE, 7)
        s = s.addEvent(0, EventType.EXCLUSION, Team.BLUE, 5)
        val goal = s.events.first()
        s = s.updateEvent(0, goal.copy(team = Team.BLUE, cap = 8))
        assertEquals(0, s.score(Team.WHITE))
        assertEquals(1, s.score(Team.BLUE))
        s = s.deleteEvent(0, s.events.last().id)
        assertTrue(s.exclusions.isEmpty())
        assertEquals(1, s.events.size)
    }

    @Test
    fun timeoutStartsCountdownAndIsCounted() {
        var s = start.toggleRun(0).addEvent(5_000, EventType.TIMEOUT, Team.BLUE, null)
        assertFalse(s.running)
        assertEquals(60_000, s.breakMs)
        assertEquals(1, s.timeoutsLeft(Team.BLUE))
        assertEquals(2, s.timeoutsLeft(Team.WHITE))
        s = s.advance(70_000)
        assertEquals(0, s.breakMs)
        assertEquals(1, s.hornSeq)
        assertEquals(475_000, s.gameMs)
    }

    @Test
    fun displaySnapshotExtrapolates() {
        val d = start.toggleRun(0).toDisplay().extrapolate(3_000)
        assertEquals(477_000, d.gameMs)
        assertEquals(25_000, d.shotMs)
        assertEquals(0, d.extrapolate(100_000).shotMs)
        assertEquals(477_000 - 25_000, d.extrapolate(100_000).gameMs)
    }

    @Test
    fun formatting() {
        assertEquals("8:00", formatGame(480_000))
        assertEquals("7:59", formatGame(479_900))
        assertEquals("59.9", formatGame(59_999))
        assertEquals("0.0", formatGame(0))
        assertEquals("28", formatShot(27_001))
        assertEquals("0", formatShot(0))
        assertEquals(443_000L, parseClock("7", "23"))
        assertEquals(23_400L, parseClock("", "23,4"))
        assertEquals(null, parseClock("x", "1"))
    }
}
