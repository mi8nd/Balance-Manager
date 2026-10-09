package com.personal.balance.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.balance.domain.ThemeMode
import com.personal.balance.domain.ThemePreferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ThemeViewModel(application: Application) : AndroidViewModel(application) {

    private val themePreferences = ThemePreferencesDataStore(application)

    private val _themeMode = MutableStateFlow(ThemeMode.DARK)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    init {
        loadThemePreference()
    }

    private fun loadThemePreference() {
        viewModelScope.launch {
            themePreferences.themeMode.collect { mode ->
                _themeMode.value = mode
            }
        }
    }

    fun toggleTheme() {
        viewModelScope.launch {
            val newMode = if (_themeMode.value == ThemeMode.LIGHT) ThemeMode.DARK else ThemeMode.LIGHT
            _themeMode.value = newMode
            themePreferences.setThemeMode(newMode)
        }
    }
}
