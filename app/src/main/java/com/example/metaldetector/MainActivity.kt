package com.example.metaldetector

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStart = findViewById<Button>(R.id.btnStartScanner)
        val illustration = findViewById<ImageView>(R.id.ivHeroIllustration)

        // --- Start the Animation ---
        startPulseAnimation(illustration)

        btnStart.setOnClickListener {
            // Launch the Scanner Screen
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }
    }

    private fun startPulseAnimation(view: View) {
        // Create animators for scaling X and Y simultaneously
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.05f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.05f)

        val animator = ObjectAnimator.ofPropertyValuesHolder(view, scaleX, scaleY).apply {
            duration = 1500 // 1.5 seconds for one pulse direction
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator() // Smooth start and end
        }

        animator.start()
    }
}