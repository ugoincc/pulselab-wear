package br.unioeste.pulselab.wear.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import br.unioeste.pulselab.wear.presentation.home.HomeScreen
import br.unioeste.pulselab.wear.presentation.theme.PulseLabWearTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            PulseLabWearTheme {
                HomeScreen()
            }
        }
    }
}
