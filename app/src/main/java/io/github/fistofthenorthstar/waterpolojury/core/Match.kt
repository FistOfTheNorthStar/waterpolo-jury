package io.github.fistofthenorthstar.waterpolojury.core

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/*
 * Pure match logic: no Android imports, so it is unit-testable on the JVM.
 * Every operation takes `now` (a monotonic millisecond timestamp) so that
 * clock edits are exact whether the clock is running or stopped.
 */

@Serializable
enum class Team {
    WHITE, BLUE;

    val short: String get() = if (this == WHITE) "W" else "B"
}

@Serializable
enum class EventType(val code: String, val label: String) {
    GOAL("G", "Goal"),
    EXCLUSION("E", "Exclusion"),
    PENALTY("P", "Penalty foul"),
    MISCONDUCT("EM", "Misconduct"),
    BRUTALITY("EB", "Brutality"),
    TIMEOUT("TO", "Timeout"),
    YELLOW_CARD("YC", "Yellow card"),
    RED_CARD("RC", "Red card"),
    NOTE("N", "Note");

    /** Counts towards the personal foul limit of the player. */
    val personalFoul: Boolean
        get() = this == EXCLUSION || this == PENALTY || this == MISCONDUCT || this == BRUTALITY

    /** Player takes no further part in the match. */
    val outForGame: Boolean
        get() = this == MISCONDUCT || this == BRUTALITY || this == RED_CARD

    /** Starts an exclusion countdown. */
    val timed: Boolean
        get() = this == EXCLUSION || this == MISCONDUCT || this == BRUTALITY

    val needsCap: Boolean
        get() = this != TIMEOUT && this != NOTE
}

@Serializable
data class MatchConfig(
    val whiteName: String = "WHITE",
    val blueName: String = "BLUE",
    val periods: Int = 4,
    val periodMs: Long = 8 * 60_000L,
    /** Full possession time. */
    val shotMs: Long = 28_000,
    /** Reduced possession time (exclusion, corner, rebound). */
    val shotShortMs: Long = 18_000,
    val exclusionMs: Long = 18_000,
    val brutalityMs: Long = 4 * 60_000L,
    val timeoutMs: Long = 60_000,
    val timeoutsPerTeam: Int = 2,
    val intervalMs: Long = 2 * 60_000L,
    val halftimeMs: Long = 3 * 60_000L,
    val foulLimit: Int = 3,
    val caps: Int = 14,
) {
    fun name(team: Team): String = if (team == Team.WHITE) whiteName else blueName
}

@Serializable
data class MatchEvent(
    val id: Long,
    val type: EventType,
    val team: Team? = null,
    val cap: Int? = null,
    val period: Int,
    /** Game clock (time remaining in the period) when the event happened. */
    val gameMs: Long,
    val note: String = "",
)

@Serializable
data class ActiveExclusion(
    val eventId: Long,
    val team: Team,
    val cap: Int? = null,
    val remainingMs: Long,
    /** Brutality: not cancelled by a goal. */
    val long: Boolean = false,
)

@Serializable
data class MatchState(
    val config: MatchConfig = MatchConfig(),
    val period: Int = 1,
    val gameMs: Long = config.periodMs,
    val shotMs: Long = config.shotMs,
    /** One switch drives game clock, shot clock and exclusion timers together. */
    val running: Boolean = false,
    /** Timestamp at which the clock values above were exact. */
    val anchor: Long = 0,
    /** Timeout / interval countdown; runs on wall time, independent of `running`. */
    val breakMs: Long = 0,
    val breakLabel: String = "",
    val exclusions: List<ActiveExclusion> = emptyList(),
    val events: List<MatchEvent> = emptyList(),
    val nextId: Long = 1,
    /** Incremented whenever the horn must sound. */
    val hornSeq: Int = 0,
) {
    val isShootout: Boolean get() = period > config.periods
    val periodLabel: String get() = if (isShootout) "PSO" else period.toString()

    fun score(team: Team): Int = events.count { it.type == EventType.GOAL && it.team == team }

    fun fouls(team: Team, cap: Int): Int =
        events.count { it.type.personalFoul && it.team == team && it.cap == cap }

    fun isOut(team: Team, cap: Int): Boolean =
        fouls(team, cap) >= config.foulLimit ||
            events.any { it.type.outForGame && it.team == team && it.cap == cap }

    fun timeoutsLeft(team: Team): Int =
        max(0, config.timeoutsPerTeam - events.count { it.type == EventType.TIMEOUT && it.team == team })

    /** Bring all clocks forward to `now`. Returns `this` when nothing is ticking. */
    fun advance(now: Long): MatchState {
        if (!running && breakMs <= 0) return this
        val dt: Long = max(0L, now - anchor)
        if (dt == 0L) return this

        var game = gameMs
        var shot = shotMs
        var run = running
        var excl = exclusions
        var horn = hornSeq
        var brk = breakMs
        var brkLabel = breakLabel

        if (brk > 0) {
            brk = max(0L, brk - dt)
            if (brk == 0L) {
                horn++
                brkLabel = ""
            }
        }
        if (run) {
            val step = min(dt, min(game, shot))
            game -= step
            shot -= step
            excl = excl.map { it.copy(remainingMs = it.remainingMs - step) }.filter { it.remainingMs > 0 }
            if (game <= 0 || shot <= 0) {
                run = false
                horn++
                if (game <= 0 && period < config.periods) {
                    val half = period * 2 == config.periods
                    brk = if (half) config.halftimeMs else config.intervalMs
                    brkLabel = if (half) "HALF TIME" else "INTERVAL"
                }
            }
        }
        return copy(
            gameMs = game, shotMs = shot, running = run, anchor = now,
            breakMs = brk, breakLabel = brkLabel, exclusions = excl, hornSeq = horn,
        )
    }

    private fun at(now: Long): MatchState = advance(now).copy(anchor = now)

    fun toggleRun(now: Long): MatchState {
        val s = at(now)
        return when {
            s.running -> s.copy(running = false)
            s.gameMs <= 0 -> s
            else -> s.copy(
                running = true,
                shotMs = if (s.shotMs <= 0) min(config.shotMs, s.gameMs) else s.shotMs,
                breakMs = 0,
                breakLabel = "",
            )
        }
    }

    fun setShot(now: Long, ms: Long): MatchState = at(now).copy(shotMs = ms.coerceIn(0, 99_000))

    fun adjustShot(now: Long, deltaMs: Long): MatchState {
        val s = at(now)
        return s.copy(shotMs = (s.shotMs + deltaMs).coerceIn(0, 99_000))
    }

    fun setGame(now: Long, ms: Long): MatchState = at(now).copy(gameMs = ms.coerceIn(0, 99 * 60_000L))

    fun adjustGame(now: Long, deltaMs: Long): MatchState {
        val s = at(now)
        return s.copy(gameMs = (s.gameMs + deltaMs).coerceIn(0, 99 * 60_000L))
    }

    fun addEvent(now: Long, type: EventType, team: Team?, cap: Int?, note: String = ""): MatchState {
        val s = at(now)
        val ev = MatchEvent(s.nextId, type, team, cap, s.period, s.gameMs, note)
        val r = s.copy(events = s.events + ev, nextId = s.nextId + 1)
        return when (type) {
            // Clock is dead after a goal; excluded players may re-enter.
            EventType.GOAL -> r.copy(
                running = false,
                shotMs = config.shotMs,
                exclusions = r.exclusions.filter { it.long },
            )
            EventType.EXCLUSION, EventType.MISCONDUCT ->
                if (team == null) r else r.copy(
                    exclusions = r.exclusions + ActiveExclusion(ev.id, team, cap, config.exclusionMs),
                    // Attack gets at least the reduced possession time.
                    shotMs = max(r.shotMs, config.shotShortMs),
                )
            EventType.BRUTALITY ->
                if (team == null) r else r.copy(
                    exclusions = r.exclusions + ActiveExclusion(ev.id, team, cap, config.brutalityMs, long = true),
                )
            EventType.TIMEOUT -> r.copy(
                running = false,
                breakMs = config.timeoutMs,
                breakLabel = "TIMEOUT" + (team?.let { " " + config.name(it) } ?: ""),
            )
            else -> r
        }
    }

    fun updateEvent(now: Long, ev: MatchEvent): MatchState {
        val s = at(now)
        return s.copy(
            events = s.events.map { if (it.id == ev.id) ev else it },
            exclusions = s.exclusions.mapNotNull {
                when {
                    it.eventId != ev.id -> it
                    !ev.type.timed || ev.team == null -> null
                    else -> it.copy(team = ev.team, cap = ev.cap)
                }
            },
        )
    }

    fun deleteEvent(now: Long, id: Long): MatchState {
        val s = at(now)
        return s.copy(
            events = s.events.filter { it.id != id },
            exclusions = s.exclusions.filter { it.eventId != id },
        )
    }

    fun clearExclusion(now: Long, eventId: Long): MatchState {
        val s = at(now)
        return s.copy(exclusions = s.exclusions.filter { it.eventId != eventId })
    }

    fun startBreak(now: Long, ms: Long, label: String): MatchState =
        at(now).copy(breakMs = ms, breakLabel = label)

    fun cancelBreak(now: Long): MatchState = at(now).copy(breakMs = 0, breakLabel = "")

    fun nextPeriod(now: Long): MatchState {
        val s = at(now)
        val p = s.period + 1
        return s.copy(
            period = p,
            gameMs = if (p <= config.periods) config.periodMs else 0L,
            shotMs = config.shotMs,
            running = false,
            breakMs = 0,
            breakLabel = "",
        )
    }

    fun previousPeriod(now: Long): MatchState {
        val s = at(now)
        return if (s.period <= 1) s else s.copy(period = s.period - 1, running = false)
    }

    fun withConfig(now: Long, c: MatchConfig): MatchState {
        val s = at(now)
        val untouched = s.events.isEmpty() && !s.running && s.period == 1 && s.gameMs == s.config.periodMs
        return if (untouched) s.copy(config = c, gameMs = c.periodMs, shotMs = c.shotMs) else s.copy(config = c)
    }

    /** Fresh match with the same settings. hornSeq keeps counting so displays don't honk. */
    fun newMatch(now: Long): MatchState = MatchState(config = config, anchor = now, hornSeq = hornSeq)

    fun describe(ev: MatchEvent): String {
        val who = ev.team?.let { t -> config.name(t) + (ev.cap?.let { " #$it" } ?: "") } ?: ""
        val periodText = if (ev.period > config.periods) "PSO" else "P${ev.period}"
        return listOf(periodText, formatGame(ev.gameMs), who, ev.type.label, ev.note)
            .filter { it.isNotBlank() }.joinToString(" ")
    }

    fun toDisplay(): DisplayState = DisplayState(
        whiteName = config.whiteName.take(24),
        blueName = config.blueName.take(24),
        whiteScore = score(Team.WHITE),
        blueScore = score(Team.BLUE),
        period = periodLabel,
        gameMs = gameMs,
        shotMs = shotMs,
        running = running,
        breakMs = breakMs,
        breakLabel = breakLabel.take(40),
        // Bounded so a snapshot always fits in one Bluetooth message.
        exclusions = exclusions.take(12).map { DisplayExclusion(it.team, it.cap, it.remainingMs) },
        whiteTimeouts = timeoutsLeft(Team.WHITE),
        blueTimeouts = timeoutsLeft(Team.BLUE),
        hornSeq = hornSeq,
    )

    fun exportText(): String = buildString {
        appendLine("${config.whiteName} ${score(Team.WHITE)} - ${score(Team.BLUE)} ${config.blueName}")
        appendLine()
        appendLine("period;time;team;cap;code;event;score;note")
        var w = 0
        var b = 0
        for (e in events) {
            if (e.type == EventType.GOAL) {
                if (e.team == Team.WHITE) w++ else if (e.team == Team.BLUE) b++
            }
            val p = if (e.period > config.periods) "PSO" else e.period.toString()
            val teamName = e.team?.let { config.name(it) } ?: ""
            val scoreText = if (e.type == EventType.GOAL) "$w-$b" else ""
            appendLine(
                listOf(p, formatGame(e.gameMs), teamName, e.cap?.toString() ?: "", e.type.code, e.type.label, scoreText, e.note)
                    .joinToString(";")
            )
        }
        for (t in Team.values()) {
            val caps = events.filter { it.type.personalFoul && it.team == t && it.cap != null }
                .groupBy { it.cap!! }.toSortedMap()
            if (caps.isNotEmpty()) {
                appendLine()
                appendLine("Personal fouls ${config.name(t)}:")
                for ((cap, list) in caps) {
                    val flag = if (isOut(t, cap)) "  OUT" else ""
                    appendLine("  #$cap: ${list.joinToString(" ") { it.type.code }}$flag")
                }
            }
        }
    }
}

@Serializable
data class DisplayExclusion(val team: Team, val cap: Int? = null, val ms: Long)

/** Compact snapshot sent to the display tablets over Bluetooth. */
@Serializable
data class DisplayState(
    val whiteName: String = "WHITE",
    val blueName: String = "BLUE",
    val whiteScore: Int = 0,
    val blueScore: Int = 0,
    val period: String = "1",
    val gameMs: Long = 0,
    val shotMs: Long = 0,
    val running: Boolean = false,
    val breakMs: Long = 0,
    val breakLabel: String = "",
    val exclusions: List<DisplayExclusion> = emptyList(),
    val whiteTimeouts: Int = 0,
    val blueTimeouts: Int = 0,
    val hornSeq: Int = 0,
) {
    /** Local extrapolation on the display between two snapshots. */
    fun extrapolate(dtMs: Long): DisplayState {
        val d: Long = max(0L, dtMs)
        val step: Long = if (running) min(d, min(gameMs, shotMs)) else 0L
        return copy(
            gameMs = gameMs - step,
            shotMs = shotMs - step,
            breakMs = max(0L, breakMs - d),
            exclusions = exclusions.map { it.copy(ms = it.ms - step) }.filter { it.ms > 0 },
        )
    }
}
