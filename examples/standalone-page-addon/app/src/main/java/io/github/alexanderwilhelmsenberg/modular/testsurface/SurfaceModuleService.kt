package io.github.alexanderwilhelmsenberg.modular.testsurface

import android.app.AlertDialog
import android.graphics.Color
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.modulesdk.BaseModuleService
import io.github.alexanderwilhelmsenberg.modular.modulesdk.ModuleInstanceContext
import io.github.alexanderwilhelmsenberg.modular.modulesdk.TrustedCorePolicy
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceEndpoint
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceScope

/** Ordinary APK fixture. All visible content is drawn by this process's Android View tree. */
class SurfaceModuleService : BaseModuleService() {
    override val descriptorJson: String by lazy {
        resources.openRawResource(R.raw.module_descriptor).bufferedReader().use { it.readText() }
    }

    override val trustedCorePolicy: TrustedCorePolicy by lazy {
        TrustedCorePolicy.pinnedToInstalled(this, setOf(TrustedCorePolicy.CORE_DEBUG_PACKAGE))
    }

    override fun createCapability(context: ModuleInstanceContext, capabilityId: String): IBinder? =
        if (capabilityId == StandardCapabilities.WorkspaceSurface.wireValue) {
            object : WorkspaceSurfaceEndpoint(this@SurfaceModuleService, context) {
                override fun onCreateView(scope: WorkspaceSurfaceScope): View = fixtureView(scope)
            }.asBinder()
        } else null

    private fun fixtureView(scope: WorkspaceSurfaceScope): View {
        val context = scope.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + .5f).toInt()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.rgb(22, 52, 72))
        }
        val title = TextView(context).apply {
            text = "Independent surface v${BuildConfig.SURFACE_REVISION}"
            contentDescription = text
            textSize = 24f
            setTextColor(Color.WHITE)
        }
        val counter = TextView(context).apply {
            text = "Surface taps: 0"
            contentDescription = text
            textSize = 18f
            setTextColor(Color.WHITE)
        }
        var taps = 0
        var detail = false
        fun showDetail(value: Boolean) {
            detail = value
            title.text = if (value) "Independent detail v${BuildConfig.SURFACE_REVISION}"
                else "Independent surface v${BuildConfig.SURFACE_REVISION}"
            title.contentDescription = title.text
            scope.updateState(canGoBack = value, exclusiveGestures = false)
        }
        scope.onBackRequested = {
            if (detail) { showDetail(false); true } else false
        }
        column.addView(title)
        column.addView(counter)
        val echo = TextView(context).apply {
            text = "Module text echo: empty"
            contentDescription = text
            textSize = 18f
            setTextColor(Color.WHITE)
        }
        val input = EditText(context).apply {
            hint = "Type in the module"
            contentDescription = "Module text input"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    echo.text = "Module text echo: ${s?.toString().orEmpty().ifEmpty { "empty" }}"
                    echo.contentDescription = echo.text
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        column.addView(input)
        column.addView(echo)
        column.addView(Button(context).apply {
            text = "Finish module input"
            contentDescription = text
            setOnClickListener {
                context.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(input.windowToken, 0)
                input.clearFocus()
                column.requestFocus()
            }
        })
        column.addView(Button(context).apply {
            text = "Tap native control"
            contentDescription = "Tap native control"
            setOnClickListener {
                taps++
                counter.text = "Surface taps: $taps"
                counter.contentDescription = counter.text
            }
        })
        column.addView(Button(context).apply {
            text = "Open native detail"
            contentDescription = "Open native detail"
            setOnClickListener { showDetail(true) }
        })
        column.addView(Button(context).apply {
            text = "Go to Apps from module"
            contentDescription = "Go to Apps from module"
            setOnClickListener { scope.requestNavigation("apps") }
        })
        if (BuildConfig.SURFACE_REVISION >= 2) column.addView(Button(context).also { button ->
            button.text = "New v2 native control"
            button.contentDescription = button.text
            button.setOnClickListener {
                val dialog = AlertDialog.Builder(context)
                    .setTitle("New v2 native dialog")
                    .setPositiveButton("Confirm") { _, _ ->
                        button.text = "New v2 control pressed"
                        button.contentDescription = button.text
                    }
                    .create()
                scope.configureDialog(dialog).show()
            }
        })
        repeat(8) { index ->
            column.addView(TextView(context).apply {
                text = "Native scroll row ${index + 1}"
                contentDescription = text
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, dp(8), 0, dp(8))
            })
        }
        showDetail(false)
        return ScrollView(context).apply {
            isFillViewport = true
            addView(column)
        }
    }
}
