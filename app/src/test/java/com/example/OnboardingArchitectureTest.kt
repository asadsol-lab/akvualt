package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.onboarding.OnboardingPreferences
import com.example.feature.onboarding.OnboardingViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingArchitectureTest {

    private lateinit var context: Context
    private lateinit var preferences: OnboardingPreferences
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        preferences = OnboardingPreferences(context)
        viewModel = OnboardingViewModel(preferences)
    }

    @Test
    fun testOnboardingPreferencesDefaultAndSet() = runBlocking {
        preferences.resetOnboarding()
        assertFalse("Onboarding should default to incomplete", preferences.isOnboardingCompletedSync())
        assertFalse("Flow should emit false initially", preferences.isOnboardingCompleted().first())

        preferences.setOnboardingCompleted(true)
        assertTrue("Onboarding should be marked completed", preferences.isOnboardingCompletedSync())
        assertTrue("Flow should emit true after set", preferences.isOnboardingCompleted().first())

        preferences.resetOnboarding()
        assertFalse("Onboarding should be reset", preferences.isOnboardingCompletedSync())
    }

    @Test
    fun testOnboardingViewModelCompletionCallback() = runBlocking {
        preferences.resetOnboarding()
        var finishCalled = false

        viewModel.completeOnboarding {
            finishCalled = true
        }

        var retries = 0
        while (!finishCalled && retries < 20) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            kotlinx.coroutines.delay(50)
            retries++
        }

        assertTrue("Completion callback should be invoked", finishCalled)
        assertTrue("Preferences should be set to completed", preferences.isOnboardingCompletedSync())
    }

    @Test
    fun testRequiredPermissionsListNotEmpty() {
        val permissions = viewModel.getRequiredPermissionsList()
        assertNotNull("Permissions list should not be null", permissions)
        assertTrue("Permissions list should contain entries", permissions.isNotEmpty())
    }
}
