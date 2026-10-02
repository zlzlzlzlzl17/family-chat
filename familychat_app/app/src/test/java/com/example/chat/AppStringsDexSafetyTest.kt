package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStringsDexSafetyTest {

    @Test
    fun appStringsConstructorLeavesDexRegisterHeadroom() {
        val largestConstructor = AppStrings::class.java.declaredConstructors
            .maxOf { it.parameterCount }

        assertTrue(
            "AppStrings has $largestConstructor constructor parameters; keep it at or below 240 " +
                "so DEX invocation stays safely below Android's 255-register limit",
            largestConstructor <= 240,
        )
    }

    @Test
    fun bothLanguagesCanBeInstantiated() {
        val english = stringsFor(AppLanguage.EN)
        val chinese = stringsFor(AppLanguage.ZH)

        assertEquals("Family Chat", english.familyChat)
        assertEquals("Sent", english.messageStatusSent)
        assertEquals("Speaker", english.speaker)
        assertEquals("\u5df2\u53d1\u9001", chinese.messageStatusSent)
        assertEquals("\u514d\u63d0", chinese.speaker)
    }
}
