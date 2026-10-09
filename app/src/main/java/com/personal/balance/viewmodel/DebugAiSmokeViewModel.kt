package com.personal.balance.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.balance.data.ai.FirebaseAiAssistantRepository
import com.personal.balance.data.ai.FirebaseAiAuthProvider
import com.personal.balance.data.ai.SharedAiUsageController

import com.personal.balance.domain.ai.AiResult
import com.personal.balance.domain.ai.AiSmokeTest
import com.personal.balance.domain.ai.AskAiUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DebugAiSmokeUiState {
    object Idle : DebugAiSmokeUiState
    object Running : DebugAiSmokeUiState
    data class Done(val result: AiResult) : DebugAiSmokeUiState
}

/**
 * Debug-only smoke test that drives the existing AI pipeline end-to-end using
 * the same [AskAiUseCase] / [FirebaseAiAssistantRepository] production path.
 * Constructs a harmless request; never sends financial facts. Only created by
 * the debug-only screen (release builds never instantiate this).
 */
class DebugAiSmokeViewModel(
    private val askAi: AskAiUseCase = AskAiUseCase(
        authProvider = FirebaseAiAuthProvider(),
        usageController = SharedAiUsageController.instance,
        assistant = FirebaseAiAssistantRepository(),
    ),
) : ViewModel() {

    private val _uiState = MutableStateFlow<DebugAiSmokeUiState>(DebugAiSmokeUiState.Idle)
    val uiState: StateFlow<DebugAiSmokeUiState> = _uiState.asStateFlow()

    fun runSmokeTest() {
        viewModelScope.launch {
            _uiState.value = DebugAiSmokeUiState.Running
            val result = askAi(AiSmokeTest.request)
            Log.i(TAG, AiSmokeTest.render(result))
            _uiState.value = DebugAiSmokeUiState.Done(result)
        }
    }

    private companion object {
        const val TAG = "DebugAiSmokeTest"
    }
}