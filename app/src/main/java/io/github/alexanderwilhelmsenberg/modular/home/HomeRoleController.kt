package io.github.alexanderwilhelmsenberg.modular.home

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent

class HomeRoleController(context: Context) {
    private val roleManager = context.getSystemService(RoleManager::class.java)

    fun isHomeRoleAvailable(): Boolean =
        roleManager?.isRoleAvailable(RoleManager.ROLE_HOME) == true

    fun isHomeRoleHeld(): Boolean =
        roleManager?.isRoleHeld(RoleManager.ROLE_HOME) == true

    fun createHomeRoleRequestIntent(): Intent? {
        val manager = roleManager ?: return null
        if (!manager.isRoleAvailable(RoleManager.ROLE_HOME)) return null
        if (manager.isRoleHeld(RoleManager.ROLE_HOME)) return null
        return manager.createRequestRoleIntent(RoleManager.ROLE_HOME)
    }
}
