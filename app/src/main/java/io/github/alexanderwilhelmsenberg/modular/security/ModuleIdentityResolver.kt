package io.github.alexanderwilhelmsenberg.modular.security

interface ModuleIdentityResolver {
    fun resolve(packageName: String): Result<ModuleIdentity>

    fun isSameSignerAsCore(
        identity: ModuleIdentity,
    ): Boolean
}
