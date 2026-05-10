package com.desmond.ofd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.desmond.ofd.ui.AppRoot
import com.desmond.ofd.ui.theme.OPlusTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OPlusTheme {
                AppRoot()
            }
        }
    }
}
