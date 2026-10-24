package com.deepak.photovault

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Shown instead of the app after it closed unexpectedly (24 Oct), so the error can be copied or shared with
 * the developer. Plain Android views only: nothing here depends on the code that may have crashed.
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = runCatching { CrashNote.file(application).readText() }.getOrDefault("No details were saved.")
        CrashNote.markSeen(application)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad * 2)
            setBackgroundColor(Color.rgb(247, 249, 252))
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "Pixallery closed unexpectedly"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(20, 22, 28))
        })
        root.addView(TextView(this).apply {
            text = "Please copy or share the details below and send them to Deepak (paste them into the chat). " +
                "They say what went wrong in the app's code; no photos, names or passwords."
            textSize = 15f
            setTextColor(Color.rgb(70, 74, 84))
            setPadding(0, pad / 2, 0, pad / 2)
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Copy"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Pixallery crash", report))
                Toast.makeText(this@CrashActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(Button(this).apply {
            text = "Share"
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                startActivity(Intent.createChooser(send, "Share crash details"))
            }
        })
        buttons.addView(Button(this).apply {
            text = "Open again"
            setOnClickListener {
                startActivity(Intent(this@CrashActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        })
        root.addView(buttons)
        root.addView(ScrollView(this).apply {
            addView(TextView(this@CrashActivity).apply {
                text = report
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.rgb(30, 32, 38))
                setTextIsSelectable(true)
                setPadding(0, pad / 2, 0, pad)
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}
