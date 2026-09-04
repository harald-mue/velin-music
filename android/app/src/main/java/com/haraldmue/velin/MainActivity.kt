package com.haraldmue.velin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.haraldmue.velin.ui.VelinApp
import com.haraldmue.velin.ui.theme.VelinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VelinTheme {
                VelinApp()
            }
        }
    }
}
