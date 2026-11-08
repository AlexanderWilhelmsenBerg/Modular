package io.github.alexanderwilhelmsenberg.modular.organization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.math.*

@Serializable
sealed interface FolderCondition {
    val id: String
    val enabled: Boolean

    @Serializable @SerialName("headset")
    data class Headset(override val id: String, override val enabled: Boolean = true,
        val kind: HeadsetKind = HeadsetKind.ANY) : FolderCondition

    @Serializable @SerialName("schedule")
    data class Schedule(override val id: String, override val enabled: Boolean = true,
        val intervals: List<WeeklyInterval> = emptyList()) : FolderCondition

    @Serializable @SerialName("wifi")
    data class Wifi(override val id: String, override val enabled: Boolean = true,
        val ssids: List<String> = emptyList()) : FolderCondition

    @Serializable @SerialName("area")
    data class Area(override val id: String, override val enabled: Boolean = true,
        val areas: List<SavedArea> = emptyList()) : FolderCondition
}

@Serializable enum class HeadsetKind { ANY, WIRED, USB, BLUETOOTH }

/** ISO weekdays, Monday=1. End is exclusive. Equal start/end means a full day. */
@Serializable data class WeeklyInterval(
    val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val startMinute: Int = 9 * 60,
    val endMinute: Int = 17 * 60,
)

@Serializable data class SavedArea(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
)

enum class ConditionOutcome { MATCH, NO_MATCH, UNKNOWN }

/** Reasons are safe to log. They never contain network names or coordinates. */
enum class ConditionReason {
    MATCHED, NOT_MATCHED, NO_ENABLED_RULES, MISSING_DEFINITION, NO_SELECTION,
    PERMISSION_REQUIRED, LOCATION_DISABLED, WIFI_UNREADABLE, NO_FIX,
    STALE_FIX, INACCURATE_FIX, BOUNDARY_UNCERTAIN, SIGNAL_UNAVAILABLE,
    ALWAYS_SHOWN, MANUALLY_HIDDEN, UNKNOWN_SHOWN, UNKNOWN_HIDDEN,
}

data class ConditionResult(val outcome: ConditionOutcome, val reason: ConditionReason,
    val evaluatedAt: Instant)

data class FolderVisibilityStatus(
    val eligible: Boolean,
    val outcome: ConditionOutcome,
    val reason: ConditionReason,
    val evaluatedAt: Instant,
    val conditions: Map<String, ConditionResult> = emptyMap(),
)

data class LocationFix(val latitude: Double, val longitude: Double,
    val accuracyMeters: Double, val capturedAt: Instant)

data class FolderSignals(
    /** null means the signal is unavailable, empty means conclusively disconnected. */
    val headsetOutputs: Set<HeadsetKind>? = null,
    /** null is unknown; an empty string means conclusively no connected Wi-Fi. */
    val connectedSsid: String? = null,
    val locationFix: LocationFix? = null,
    val locationPermission: Boolean = false,
    val locationEnabled: Boolean = false,
    val wifiPermission: Boolean = false,
)

data class RetainedAreaDecision(val outcome: ConditionOutcome, val at: Instant)

object FolderRuleEvaluator {
    const val MAX_FIX_AGE_SECONDS = 120L
    const val MAX_RETAINED_SECONDS = 120L

    fun evaluate(folder: FolderDefinition, definitions: Map<String, FolderCondition>,
        signals: FolderSignals, now: ZonedDateTime,
        retained: Map<String, RetainedAreaDecision> = emptyMap()): FolderVisibilityStatus {
        val instant = now.toInstant()
        if (folder.visibilityMode == FolderVisibilityMode.ALWAYS)
            return FolderVisibilityStatus(true, ConditionOutcome.MATCH, ConditionReason.ALWAYS_SHOWN, instant)
        if (folder.visibilityMode == FolderVisibilityMode.HIDDEN)
            return FolderVisibilityStatus(false, ConditionOutcome.NO_MATCH, ConditionReason.MANUALLY_HIDDEN, instant)
        val enabled = folder.conditionIds.mapNotNull { definitions[it] }.filter { it.enabled }
        val results = folder.conditionIds.associateWith { id ->
            val rule = definitions[id]
            when {
                rule == null -> ConditionResult(ConditionOutcome.UNKNOWN, ConditionReason.MISSING_DEFINITION, instant)
                !rule.enabled -> ConditionResult(ConditionOutcome.UNKNOWN, ConditionReason.NO_ENABLED_RULES, instant)
                else -> evaluateCondition(rule, signals, now, retained[id])
            }
        }.filterKeys { definitions[it]?.enabled != false }
        val outcomes = results.values.map { it.outcome }
        if (enabled.isEmpty() && results.isEmpty())
            return FolderVisibilityStatus(folder.unknownFallback == UnknownFallback.SHOW,
                ConditionOutcome.UNKNOWN, ConditionReason.NO_ENABLED_RULES, instant)
        val outcome = combine(outcomes, folder.matchMode)
        val eligible = when (outcome) {
            ConditionOutcome.MATCH -> true
            ConditionOutcome.NO_MATCH -> false
            ConditionOutcome.UNKNOWN -> folder.unknownFallback == UnknownFallback.SHOW
        }
        val reason = when (outcome) {
            ConditionOutcome.MATCH -> ConditionReason.MATCHED
            ConditionOutcome.NO_MATCH -> ConditionReason.NOT_MATCHED
            ConditionOutcome.UNKNOWN -> if (eligible) ConditionReason.UNKNOWN_SHOWN else ConditionReason.UNKNOWN_HIDDEN
        }
        return FolderVisibilityStatus(eligible, outcome, reason, instant, results)
    }

    fun combine(values: List<ConditionOutcome>, mode: FolderMatchMode): ConditionOutcome {
        if (values.isEmpty()) return ConditionOutcome.UNKNOWN
        return if (mode == FolderMatchMode.ANY) when {
            ConditionOutcome.MATCH in values -> ConditionOutcome.MATCH
            values.all { it == ConditionOutcome.NO_MATCH } -> ConditionOutcome.NO_MATCH
            else -> ConditionOutcome.UNKNOWN
        } else when {
            ConditionOutcome.NO_MATCH in values -> ConditionOutcome.NO_MATCH
            values.all { it == ConditionOutcome.MATCH } -> ConditionOutcome.MATCH
            else -> ConditionOutcome.UNKNOWN
        }
    }

    fun evaluateCondition(rule: FolderCondition, signals: FolderSignals, now: ZonedDateTime,
        retained: RetainedAreaDecision? = null): ConditionResult {
        val instant = now.toInstant()
        fun result(value: ConditionOutcome, reason: ConditionReason) = ConditionResult(value, reason, instant)
        return when (rule) {
            is FolderCondition.Headset -> {
                val outputs = signals.headsetOutputs ?: return result(ConditionOutcome.UNKNOWN, ConditionReason.SIGNAL_UNAVAILABLE)
                val matched = if (rule.kind == HeadsetKind.ANY) outputs.isNotEmpty() else rule.kind in outputs
                result(if (matched) ConditionOutcome.MATCH else ConditionOutcome.NO_MATCH,
                    if (matched) ConditionReason.MATCHED else ConditionReason.NOT_MATCHED)
            }
            is FolderCondition.Schedule -> {
                if (rule.intervals.isEmpty()) return result(ConditionOutcome.UNKNOWN, ConditionReason.NO_SELECTION)
                val matched = rule.intervals.any { scheduleMatches(it, now) }
                result(if (matched) ConditionOutcome.MATCH else ConditionOutcome.NO_MATCH,
                    if (matched) ConditionReason.MATCHED else ConditionReason.NOT_MATCHED)
            }
            is FolderCondition.Wifi -> {
                if (rule.ssids.isEmpty()) return result(ConditionOutcome.UNKNOWN, ConditionReason.NO_SELECTION)
                if (!signals.wifiPermission) return result(ConditionOutcome.UNKNOWN, ConditionReason.PERMISSION_REQUIRED)
                val ssid = signals.connectedSsid ?: return result(ConditionOutcome.UNKNOWN, ConditionReason.WIFI_UNREADABLE)
                val matched = ssid.isNotEmpty() && ssid in rule.ssids
                result(if (matched) ConditionOutcome.MATCH else ConditionOutcome.NO_MATCH,
                    if (matched) ConditionReason.MATCHED else ConditionReason.NOT_MATCHED)
            }
            is FolderCondition.Area -> {
                if (rule.areas.isEmpty()) return result(ConditionOutcome.UNKNOWN, ConditionReason.NO_SELECTION)
                if (!signals.locationPermission) return result(ConditionOutcome.UNKNOWN, ConditionReason.PERMISSION_REQUIRED)
                if (!signals.locationEnabled) return result(ConditionOutcome.UNKNOWN, ConditionReason.LOCATION_DISABLED)
                val fix = signals.locationFix ?: return result(ConditionOutcome.UNKNOWN, ConditionReason.NO_FIX)
                val age = instant.epochSecond - fix.capturedAt.epochSecond
                if (age !in 0..MAX_FIX_AGE_SECONDS) return result(ConditionOutcome.UNKNOWN, ConditionReason.STALE_FIX)
                if (!fix.accuracyMeters.isFinite() || fix.accuracyMeters < 0.0)
                    return result(ConditionOutcome.UNKNOWN, ConditionReason.INACCURATE_FIX)
                val decisions = rule.areas.map { classifyArea(it, fix) }
                when {
                    ConditionOutcome.MATCH in decisions -> result(ConditionOutcome.MATCH, ConditionReason.MATCHED)
                    decisions.all { it == ConditionOutcome.NO_MATCH } -> result(ConditionOutcome.NO_MATCH, ConditionReason.NOT_MATCHED)
                    retained != null && retained.outcome != ConditionOutcome.UNKNOWN &&
                        instant.epochSecond - retained.at.epochSecond in 0..MAX_RETAINED_SECONDS ->
                        result(retained.outcome, ConditionReason.BOUNDARY_UNCERTAIN)
                    else -> result(ConditionOutcome.UNKNOWN, ConditionReason.BOUNDARY_UNCERTAIN)
                }
            }
        }
    }

    fun scheduleMatches(interval: WeeklyInterval, now: ZonedDateTime): Boolean {
        if (interval.weekdays.isEmpty() || interval.startMinute !in 0..1439 || interval.endMinute !in 0..1439) return false
        val minute = now.hour * 60 + now.minute
        val today = now.dayOfWeek.value
        val yesterday = now.minusDays(1).dayOfWeek.value
        return if (interval.startMinute < interval.endMinute)
            today in interval.weekdays && minute >= interval.startMinute && minute < interval.endMinute
        else if (interval.startMinute > interval.endMinute)
            (today in interval.weekdays && minute >= interval.startMinute) ||
                (yesterday in interval.weekdays && minute < interval.endMinute)
        else today in interval.weekdays || (yesterday in interval.weekdays && minute < interval.startMinute)
    }

    fun classifyArea(area: SavedArea, fix: LocationFix): ConditionOutcome {
        if (area.latitude !in -90.0..90.0 || area.longitude !in -180.0..180.0 ||
            !area.radiusMeters.isFinite() || area.radiusMeters <= 0.0) return ConditionOutcome.UNKNOWN
        val d = distanceMeters(area.latitude, area.longitude, fix.latitude, fix.longitude)
        return when {
            d + fix.accuracyMeters <= area.radiusMeters -> ConditionOutcome.MATCH
            d - fix.accuracyMeters > area.radiusMeters -> ConditionOutcome.NO_MATCH
            else -> ConditionOutcome.UNKNOWN
        }
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val a = sin(Math.toRadians(lat2 - lat1) / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(Math.toRadians(lon2 - lon1) / 2).pow(2)
        return 2 * 6371000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/** Pure rate gate; a failed or timed-out request still consumes its minute. */
class LocationRequestBudget(private val minimumIntervalSeconds: Long = 60) {
    private var lastRequest: Instant? = null
    fun claim(now: Instant): Boolean {
        val previous = lastRequest
        if (previous != null && now.epochSecond - previous.epochSecond < minimumIntervalSeconds) return false
        lastRequest = now
        return true
    }
}

fun folderVisibilitySummary(folder: FolderDefinition, status: FolderVisibilityStatus?): String = when {
    folder.visibilityMode == FolderVisibilityMode.ALWAYS -> "Always shown"
    folder.visibilityMode == FolderVisibilityMode.HIDDEN -> "Manually hidden"
    status == null -> "Smart visibility: checking"
    status.outcome == ConditionOutcome.UNKNOWN -> if (status.eligible) "Shown: signal unavailable" else "Hidden until signal is known"
    status.eligible -> "Shown: rules match"
    else -> "Hidden: rules do not match"
}
