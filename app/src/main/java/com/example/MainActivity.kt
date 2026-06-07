package com.example

import android.content.Intent
import android.os.Bundle
import android.view.animation.AlphaAnimation
import androidx.appcompat.app.AppCompatActivity
import com.example.databinding.ActivityMainBinding
import com.example.ui.CameraActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Use XML ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up professional 0.5-second (500ms) fade-in animation
        val fadeInAnimation = AlphaAnimation(0.0f, 1.0f).apply {
            duration = 500
            fillAfter = true
        }
        binding.logoContainer.startAnimation(fadeInAnimation)

        // Transition to main camera interface after 1.5 seconds
        binding.root.postDelayed({
            val intent = Intent(this, CameraActivity::class.java)
            startActivity(intent)
            
            // Premium smooth crossfade transition animation
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 1500)
    }
}
