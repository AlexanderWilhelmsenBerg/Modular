package io.github.alexanderwilhelmsenberg.modular.modulesdk

/** Teardown failures must not kill the provider process or prevent remaining resources closing. */
internal fun cleanupWorkspaceSurface(
    closeScope: () -> Unit,
    destroyOwner: () -> Unit,
    releaseHost: () -> Unit,
    report: (String, RuntimeException) -> Unit,
) {
    for ((stage, action) in listOf("scope" to closeScope, "owner" to destroyOwner, "host" to releaseHost)) {
        try { action() } catch (failure: RuntimeException) { report(stage, failure) }
    }
}
