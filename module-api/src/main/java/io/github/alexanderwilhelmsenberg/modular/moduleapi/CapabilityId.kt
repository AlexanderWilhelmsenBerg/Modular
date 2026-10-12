package io.github.alexanderwilhelmsenberg.modular.moduleapi

/**
 * Stable capability identity carried across the public module boundary.
 *
 * Wire format:
 *   modular.<domain>.<name>/v<major>
 */
data class CapabilityId(
    val name: String,
    val majorVersion: Int,
) {
    init {
        require(NAME_PATTERN.matches(name)) {
            "Invalid capability name: $name"
        }
        require(majorVersion > 0) {
            "Capability major version must be positive"
        }
    }

    val wireValue: String
        get() = "$name/v$majorVersion"

    override fun toString(): String = wireValue

    companion object {
        private val NAME_PATTERN =
            Regex("""^modular(?:\.[a-z][a-z0-9_-]*){2,}$""")

        private val WIRE_PATTERN =
            Regex("""^(modular(?:\.[a-z][a-z0-9_-]*){2,})/v([1-9][0-9]*)$""")

        fun parse(value: String): CapabilityId? {
            val match = WIRE_PATTERN.matchEntire(value) ?: return null
            val major = match.groupValues[2].toIntOrNull() ?: return null
            return runCatching {
                CapabilityId(
                    name = match.groupValues[1],
                    majorVersion = major,
                )
            }.getOrNull()
        }
    }
}
