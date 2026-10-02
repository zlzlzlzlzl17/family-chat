package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AuthFeedbackTest {
    @Test
    fun credentialErrorIsLocalizedInsteadOfShowingServerCode() {
        val chinese = friendlyErrorMessage("invalid_credentials", AppLanguage.ZH)
        val english = friendlyErrorMessage("invalid_credentials", AppLanguage.EN)

        assertEquals("用户 ID 或密码错误", chinese)
        assertEquals("Incorrect user ID or password", english)
        assertFalse(chinese.contains("invalid_credentials"))
    }

    @Test
    fun networkExceptionsUseReadableOfflineMessage() {
        assertEquals(
            "网络连接不可用，请检查网络后重试",
            friendlyErrorMessage("Unable to resolve host example.com", AppLanguage.ZH),
        )
    }

    @Test
    fun unknownInternalCodesDoNotLeakToTheUi() {
        assertEquals(
            "Something went wrong. Please try again",
            friendlyErrorMessage("unexpected_internal_code", AppLanguage.EN),
        )
    }

    @Test
    fun rawNetworkFailuresNeverReachTheUserVerbatim() {
        // The case that prompted generalising this: OkHttp surfaces "Unable to
        // resolve host ..." and it was shown raw on every screen except sign-in,
        // because the mapper was only applied there.
        val raw = "java.net.UnknownHostException: Unable to resolve host \"chat.example.top\""

        val english = friendlyErrorMessage(raw, AppLanguage.EN)
        val chinese = friendlyErrorMessage(raw, AppLanguage.ZH)

        assertEquals("No network connection. Check your connection and try again", english)
        assertFalse("exception type leaked to the user: $english", english.contains("Exception"))
        assertFalse("host detail leaked to the user: $english", english.contains("resolve host"))
        assertFalse("exception type leaked to the user: $chinese", chinese.contains("Exception"))
    }

    @Test
    fun genericFailuresDoNotClaimSignInFailed() {
        // request_failed is the fallback for conversation refresh, management
        // actions and the push self-test. It was mapped to the sign-in wording,
        // so a failed avatar upload told the user their sign-in had failed.
        val generic = friendlyErrorMessage("request_failed", AppLanguage.EN)
        assertEquals("Something went wrong. Please try again", generic)
        assertFalse("generic failure claimed to be a sign-in problem: $generic", generic.contains("Sign-in"))

        // The genuinely sign-in specific code keeps its wording.
        assertEquals("Sign-in failed. Please try again", friendlyErrorMessage("login failed", AppLanguage.EN))
    }

    @Test
    fun blogFailuresSayWhichThingFailed() {
        // Every blog failure previously landed on the generic fallback, so a
        // post that failed to publish and a feed that failed to load produced
        // the same sentence - and neither said the draft had been kept.
        val publish = friendlyErrorMessage("blog_publish_failed", AppLanguage.EN)
        val load = friendlyErrorMessage("blog_refresh_failed", AppLanguage.EN)
        val comment = friendlyErrorMessage("blog_comment_failed", AppLanguage.EN)

        assertFalse("publish failure was left generic: $publish", publish == "Something went wrong. Please try again")
        assertNotEquals("publish and load failures read identically", publish, load)
        assertNotEquals("comment and publish failures read identically", comment, publish)

        for (raw in listOf(
            "blog_refresh_failed",
            "blog_publish_failed",
            "blog_comments_failed",
            "blog_comment_failed",
            "blog_delete_failed",
            "blog_comment_delete_failed",
        )) {
            for (language in AppLanguage.entries) {
                val shown = friendlyErrorMessage(raw, language)
                assertFalse("raw code leaked: $shown", shown.contains("_"))
            }
        }
    }

    @Test
    fun internalCodesAndExceptionNamesAreNeverShownVerbatim() {
        // reportError previously fell back to error.javaClass.simpleName, so a
        // family member could be shown a Java class name as a red line.
        for (raw in listOf("request_failed", "some_internal_code", "IllegalStateException")) {
            val shown = friendlyErrorMessage(raw, AppLanguage.EN)
            assertFalse("raw code leaked: $shown", shown.contains("_"))
            assertFalse("exception name leaked: $shown", shown.contains("Exception"))
        }
    }
}
