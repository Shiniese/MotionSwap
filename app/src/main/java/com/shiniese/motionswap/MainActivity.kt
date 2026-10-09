package com.shiniese.motionswap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shiniese.motionswap.ui.MainScreen
import com.shiniese.motionswap.ui.theme.MotionSwapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MotionSwapTheme {
                MainScreen()
            }
        }
    }
}