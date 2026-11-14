package com.example.tvremoteime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tvremoteime.ui.TvRemoteScreen
import com.example.tvremoteime.ui.TvRemoteViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme()
            ) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val vm: TvRemoteViewModel = viewModel()
                    TvRemoteScreen(viewModel = vm)
                }
            }
        }
    }
}
