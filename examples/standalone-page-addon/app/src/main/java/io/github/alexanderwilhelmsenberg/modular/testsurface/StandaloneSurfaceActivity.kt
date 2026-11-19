package io.github.alexanderwilhelmsenberg.modular.testsurface

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Explicit workspace fallback for Android 10–12; all UI still belongs to the add-on APK. */
class StandaloneSurfaceActivity : Activity() {
    private lateinit var title: TextView
    private var detail = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + .5f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(22, 52, 72))
        }
        title = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.WHITE)
        }
        showDetail(false)
        column.addView(title)
        var taps = 0
        val counter = TextView(this).apply {
            text = "Surface taps: 0"
            contentDescription = text
            textSize = 18f
            setTextColor(Color.WHITE)
        }
        column.addView(counter)
        column.addView(Button(this).apply {
            text = "Tap native control"
            contentDescription = text
            setOnClickListener {
                taps++
                counter.text = "Surface taps: $taps"
                counter.contentDescription = counter.text
            }
        })
        column.addView(Button(this).apply {
            text = "Open native detail"
            contentDescription = text
            setOnClickListener { showDetail(true) }
        })
        if (BuildConfig.SURFACE_REVISION >= 2) column.addView(Button(this).also { button ->
            button.text = "New v2 native control"
            button.contentDescription = button.text
            button.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("New v2 native dialog")
                    .setPositiveButton("Confirm") { _, _ ->
                        button.text = "New v2 control pressed"
                        button.contentDescription = button.text
                    }
                    .show()
            }
        })
        setContentView(column)
    }

    private fun showDetail(value: Boolean) {
        detail = value
        title.text = if (value) "Independent detail v${BuildConfig.SURFACE_REVISION}"
            else "Independent surface v${BuildConfig.SURFACE_REVISION}"
        title.contentDescription = title.text
    }

    @Deprecated("Android system Back is exercised by the API 29–32 fallback proof")
    override fun onBackPressed() {
        if (detail) showDetail(false) else super.onBackPressed()
    }
}
