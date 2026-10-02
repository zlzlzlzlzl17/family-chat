package com.example.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class UserPreferenceState(
    val language: AppLanguage,
    val displayMode: AppDisplayMode,
    val dynamicColorsEnabled: Boolean,
    val textSize: AppTextSize,
    val e2eeEnabled: Boolean,
    val blogNotificationsEnabled: Boolean,
)

/** Single observable source for user-facing preferences. */
internal class UserPreferencesStore(
    private val preferences: ChatPreferences,
) {
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<UserPreferenceState> = mutableState.asStateFlow()

    fun setLanguage(value: AppLanguage) {
        preferences.language = value.name
        mutableState.update { it.copy(language = value) }
    }

    fun setDisplayMode(value: AppDisplayMode) {
        preferences.displayMode = value.name
        mutableState.update { it.copy(displayMode = value) }
    }

    fun setTextSize(value: AppTextSize) {
        preferences.textSize = value.name
        mutableState.update { it.copy(textSize = value) }
    }

    fun setDynamicColorsEnabled(value: Boolean) {
        preferences.dynamicColorsEnabled = value
        mutableState.update { it.copy(dynamicColorsEnabled = value) }
    }

    fun setE2eeEnabled(value: Boolean) {
        preferences.e2eeEnabled = value
        mutableState.update { it.copy(e2eeEnabled = value) }
    }

    fun setBlogNotificationsEnabled(value: Boolean) {
        preferences.blogNotificationsEnabled = value
        mutableState.update { it.copy(blogNotificationsEnabled = value) }
    }

    fun refresh() {
        mutableState.value = read()
    }

    private fun read() = UserPreferenceState(
        language = AppLanguage.fromStored(preferences.language),
        displayMode = AppDisplayMode.fromStored(preferences.displayMode),
        dynamicColorsEnabled = preferences.dynamicColorsEnabled,
        textSize = AppTextSize.fromStored(preferences.textSize),
        e2eeEnabled = preferences.e2eeEnabled,
        blogNotificationsEnabled = preferences.blogNotificationsEnabled,
    )
}
