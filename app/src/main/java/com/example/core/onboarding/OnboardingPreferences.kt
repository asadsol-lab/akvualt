package com.example.core.onboarding

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class OnboardingPreferences(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "vault_onboarding_storage"
        private const val KEY_ONBOARDING_COMPLETED = "key_onboarding_completed"
    }

    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _onboardingCompletedFlow = MutableStateFlow(isOnboardingCompletedSync())
    val onboardingCompletedFlow: Flow<Boolean> = _onboardingCompletedFlow.asStateFlow()

    fun isOnboardingCompletedSync(): Boolean {
        return sharedPreferences.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun isOnboardingCompleted(): Flow<Boolean> {
        return _onboardingCompletedFlow.asStateFlow()
    }

    suspend fun setOnboardingCompleted(completed: Boolean) = withContext(Dispatchers.IO) {
        sharedPreferences.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).commit()
        _onboardingCompletedFlow.value = completed
    }

    suspend fun resetOnboarding() = withContext(Dispatchers.IO) {
        sharedPreferences.edit().remove(KEY_ONBOARDING_COMPLETED).commit()
        _onboardingCompletedFlow.value = false
    }
}
