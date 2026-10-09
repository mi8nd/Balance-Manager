package com.personal.balance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.balance.ui.theme.BalanceManagerTheme
import com.personal.balance.ui.navigation.AppNavigation
import com.personal.balance.viewmodel.ThemeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeViewModel: ThemeViewModel = viewModel()
            val themeMode by themeViewModel.themeMode.collectAsState()

            BalanceManagerTheme(themeMode = themeMode) {
                AppNavigation(
                    themeMode = themeMode,
                    onToggleTheme = { themeViewModel.toggleTheme() }
                )
            }
        }
    }
}
