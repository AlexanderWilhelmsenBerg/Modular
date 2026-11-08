package io.github.alexanderwilhelmsenberg.modular.organization

import android.app.Application
import android.animation.ValueAnimator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp

/** Keeps accepted sorter writes alive through activity recreation. */
class OrganizationViewModel(application: Application) : AndroidViewModel(application) {
    val store = OrganizationStore(application)
    val runtime = OrganizationRuntime(application, store)
    var sorter by mutableStateOf<MegaSorterController?>(null)
        private set

    fun startSorter(snapshot: OrganizationSnapshot, apps: List<LaunchableApp>, left: String?, right: String?) {
        exitSorter()
        // The caller supplies customized visible labels. Capture their locale order once;
        // later catalog changes must not move rows or alphabet sections in this session.
        val sessionApps = sorterSessionApps(apps)
        sorter = MegaSorterController(store, viewModelScope, snapshot, sessionApps, left, right,
            animateMotion = ValueAnimator.areAnimatorsEnabled(),
            onBackgroundFailure = { message -> android.widget.Toast.makeText(applicationContext,
                message, android.widget.Toast.LENGTH_LONG).show() })
    }

    fun exitSorter() {
        sorter?.exit()
        sorter = null
    }

    override fun onCleared() { runtime.close() }

    private val applicationContext get() = getApplication<Application>()
}
