package io.github.alexanderwilhelmsenberg.modular.apps

/** Presentation preferences are keyed by the complete launch identity, including profile. */
data class AppCustomizations(
    val labels: Map<String, String> = emptyMap(),
    val hidden: Set<String> = emptySet(),
) {
    fun labelFor(app: LaunchableApp): String = labels[app.key.stableId] ?: app.label

    fun isHidden(key: AppKey): Boolean = key.stableId in hidden

    /** Apply only to presentation lists; callers retain the raw catalog for capabilities. */
    fun visibleApps(apps: List<LaunchableApp>): List<LaunchableApp> = apps.mapNotNull { app ->
        if (isHidden(app.key)) null else app.copy(label = labelFor(app))
    }
}

const val MAX_CUSTOM_APP_LABEL_CODE_POINTS = 100

/** A blank label removes an override; the original Android label remains authoritative. */
fun normalizeCustomAppLabel(label: String?): String? {
    val trimmed = label?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val count = trimmed.codePointCount(0, trimmed.length)
    val end = trimmed.offsetByCodePoints(0, count.coerceAtMost(MAX_CUSTOM_APP_LABEL_CODE_POINTS))
    return trimmed.substring(0, end).trim().ifEmpty { null }
}
