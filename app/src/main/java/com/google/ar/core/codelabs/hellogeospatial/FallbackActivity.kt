package com.google.ar.core.codelabs.hellogeospatial

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * A simple fallback activity to use if the main activity has compatibility issues
 */
class FallbackActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Create a simple text view
        val textView = TextView(this)
        textView.text = "AR Navigation App"
        textView.textSize = 24f
        
        // Set as content view
        setContentView(textView)
    }
} 