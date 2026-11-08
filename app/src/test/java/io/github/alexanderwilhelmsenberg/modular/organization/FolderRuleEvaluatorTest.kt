package io.github.alexanderwilhelmsenberg.modular.organization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

class FolderRuleEvaluatorTest {
    private val zone = ZoneId.of("Europe/Oslo")
    private val monday = ZonedDateTime.of(2026, 11, 9, 9, 0, 0, 0, zone)

    @Test fun anyAndAllFollowThreeValuedLogic() {
        val match = ConditionOutcome.MATCH
        val no = ConditionOutcome.NO_MATCH
        val unknown = ConditionOutcome.UNKNOWN
        assertEquals(match, FolderRuleEvaluator.combine(listOf(match, unknown), FolderMatchMode.ANY))
        assertEquals(no, FolderRuleEvaluator.combine(listOf(no, no), FolderMatchMode.ANY))
        assertEquals(unknown, FolderRuleEvaluator.combine(listOf(no, unknown), FolderMatchMode.ANY))
        assertEquals(no, FolderRuleEvaluator.combine(listOf(no, unknown), FolderMatchMode.ALL))
        assertEquals(match, FolderRuleEvaluator.combine(listOf(match, match), FolderMatchMode.ALL))
        assertEquals(unknown, FolderRuleEvaluator.combine(listOf(match, unknown), FolderMatchMode.ALL))
    }

    @Test fun overnightScheduleCarriesMondayIntoTuesdayAndUsesWallClockAcrossDst() {
        val interval = WeeklyInterval(setOf(1), 22 * 60, 6 * 60)
        assertTrue(FolderRuleEvaluator.scheduleMatches(interval, monday.withHour(23)))
        assertTrue(FolderRuleEvaluator.scheduleMatches(interval, monday.plusDays(1).withHour(5)))
        assertFalse(FolderRuleEvaluator.scheduleMatches(interval, monday.plusDays(1).withHour(6)))
        assertFalse(FolderRuleEvaluator.scheduleMatches(interval, monday.plusDays(2).withHour(5)))
        val sunday = WeeklyInterval(setOf(7), 1 * 60, 4 * 60)
        val dst = ZonedDateTime.of(2026, 10, 25, 3, 30, 0, 0, zone)
        assertTrue(FolderRuleEvaluator.scheduleMatches(sunday, dst))
    }

    @Test fun areaRequiresAccuracyCircleToClearBoundaryAndRetentionExpires() {
        val area = SavedArea("area", "Office", 60.0, 10.0, 100.0)
        val now = monday.toInstant()
        assertEquals(ConditionOutcome.MATCH, FolderRuleEvaluator.classifyArea(area,
            LocationFix(60.0, 10.0, 10.0, now)))
        assertEquals(ConditionOutcome.NO_MATCH, FolderRuleEvaluator.classifyArea(area,
            LocationFix(60.003, 10.0, 10.0, now)))
        val uncertain = LocationFix(60.00085, 10.0, 20.0, now)
        assertEquals(ConditionOutcome.UNKNOWN, FolderRuleEvaluator.classifyArea(area, uncertain))
        val rule = FolderCondition.Area("rule", areas = listOf(area))
        val signals = FolderSignals(locationFix = uncertain, locationPermission = true, locationEnabled = true)
        assertEquals(ConditionOutcome.MATCH, FolderRuleEvaluator.evaluateCondition(rule, signals, monday,
            RetainedAreaDecision(ConditionOutcome.MATCH, now.minusSeconds(119))).outcome)
        assertEquals(ConditionOutcome.UNKNOWN, FolderRuleEvaluator.evaluateCondition(rule, signals, monday,
            RetainedAreaDecision(ConditionOutcome.MATCH, now.minusSeconds(121))).outcome)
        assertEquals(ConditionReason.LOCATION_DISABLED, FolderRuleEvaluator.evaluateCondition(rule,
            signals.copy(locationEnabled = false), monday,
            RetainedAreaDecision(ConditionOutcome.MATCH, now)).reason)
    }

    @Test fun unknownFallbackAndMissingRecordDoNotClaimMatch() {
        val folder = FolderDefinition("f", "Folder", visibilityMode = FolderVisibilityMode.SMART,
            unknownFallback = UnknownFallback.HIDE, conditionIds = listOf("missing"))
        val result = FolderRuleEvaluator.evaluate(folder, emptyMap(), FolderSignals(), monday)
        assertEquals(ConditionOutcome.UNKNOWN, result.outcome)
        assertFalse(result.eligible)
        assertEquals(ConditionReason.MISSING_DEFINITION, result.conditions["missing"]?.reason)
    }

    @Test fun connectedWifiRequiresReadableCurrentIdentity() {
        val rule = FolderCondition.Wifi("wifi", ssids = listOf("Office"))
        assertEquals(ConditionOutcome.UNKNOWN, FolderRuleEvaluator.evaluateCondition(rule,
            FolderSignals(wifiPermission = true, connectedSsid = null), monday).outcome)
        assertEquals(ConditionOutcome.NO_MATCH, FolderRuleEvaluator.evaluateCondition(rule,
            FolderSignals(wifiPermission = true, connectedSsid = ""), monday).outcome)
        assertEquals(ConditionOutcome.MATCH, FolderRuleEvaluator.evaluateCondition(rule,
            FolderSignals(wifiPermission = true, connectedSsid = "Office"), monday).outcome)
    }

    @Test fun locationRequestGateBoundsRepeatedForegroundRetries() {
        val gate = LocationRequestBudget()
        val start = Instant.parse("2026-11-08T12:00:00Z")
        assertTrue(gate.claim(start))
        assertFalse(gate.claim(start.plusSeconds(1)))
        assertFalse(gate.claim(start.plusSeconds(59)))
        assertTrue(gate.claim(start.plusSeconds(60)))
    }

    @Test fun unknownTransportSsidAllowsReadableFallbackIdentity() {
        assertEquals("Office Wi-Fi", chooseConnectedSsid("\"<unknown ssid>\"") { "\"Office Wi-Fi\"" })
        assertEquals("Home", chooseConnectedSsid("\"Home\"") { error("No fallback needed") })
        assertEquals(null, chooseConnectedSsid("") { null })
    }

    @Test fun scheduleWallClockUsesCurrentDeviceTimezoneAfterChange() {
        val previous = TimeZone.getDefault()
        val clock = Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneId.of("UTC"))
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals(12, currentWallClock(clock).hour)
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Oslo"))
            assertEquals(13, currentWallClock(clock).hour)
        } finally { TimeZone.setDefault(previous) }
    }
}
