package io.github.alexanderwilhelmsenberg.modular.capabilities

import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel

object AppSortResultValidator {
    fun apply(
        input: List<LaunchableApp>,
        orderedKeys: List<AppKeyParcel>,
    ): Result<List<LaunchableApp>> =
        runCatching {
            val byKey = input.associateBy { it.key }

            require(byKey.size == input.size) {
                "Input app list contains duplicate AppKey values"
            }

            require(orderedKeys.size == input.size) {
                "Sort provider returned the wrong number of keys"
            }

            val seen = HashSet<AppKey>(orderedKeys.size)

            orderedKeys.map { parcel ->
                val key = AppKey(
                    packageName = parcel.packageName,
                    activityName = parcel.activityName,
                    profileSerial = parcel.profileSerial,
                )

                require(seen.add(key)) {
                    "Sort provider returned a duplicate key: " +
                        key.stableId
                }

                requireNotNull(byKey[key]) {
                    "Sort provider returned an unknown key: " +
                        key.stableId
                }
            }
        }
}
