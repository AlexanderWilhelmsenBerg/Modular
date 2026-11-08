package io.github.alexanderwilhelmsenberg.modular.organization

import android.content.pm.ApplicationInfo
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp

/** Android's local manifest/installer hint is partial. Unknown values are not guessed. */
object CategoryClassifier {
    fun categorySignal(androidCategory: Int, isLegacyGame: Boolean): Int =
        if (androidCategory == ApplicationInfo.CATEGORY_UNDEFINED && isLegacyGame) {
            ApplicationInfo.CATEGORY_GAME
        } else androidCategory

    fun matches(categoryId: String, app: LaunchableApp): Boolean = when (app.androidCategory) {
        ApplicationInfo.CATEGORY_GAME -> categoryId == BuiltInCategories.GAMES
        ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO,
        ApplicationInfo.CATEGORY_IMAGE -> categoryId == BuiltInCategories.MEDIA
        ApplicationInfo.CATEGORY_SOCIAL -> categoryId == BuiltInCategories.SOCIAL
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> categoryId == BuiltInCategories.PRODUCTIVITY
        else -> false
    }

    fun membership(snapshot: OrganizationSnapshot, category: CategoryDefinition,
        app: LaunchableApp): Boolean = when (snapshot.overrideFor(category.id, app.key)) {
        MembershipOverride.INCLUDE -> true
        MembershipOverride.EXCLUDE -> false
        MembershipOverride.AUTO -> category.automatic && matches(category.id, app)
    }

    fun provenance(snapshot: OrganizationSnapshot, category: CategoryDefinition,
        app: LaunchableApp): MembershipProvenance = when {
        snapshot.overrideFor(category.id, app.key) == MembershipOverride.INCLUDE ->
            MembershipProvenance.MANUAL
        membership(snapshot, category, app) -> MembershipProvenance.ANDROID_CATEGORY
        else -> MembershipProvenance.NONE
    }
}
