package com.example.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlogApiTest {
    @Test
    fun parsesPostWithProtectedMediaAndViewerState() {
        val post = BlogApi.parsePost(
            JSONObject(
                """
                {
                  "id": 42,
                  "body": "Family update",
                  "created_at": 1700000000000,
                  "updated_at": 1700000001000,
                  "author": {
                    "user_code": "12345678",
                    "username": "alice",
                    "color": "#128c7e",
                    "avatar_url": "/uploads/alice.jpg"
                  },
                  "like_count": 3,
                  "comment_count": 2,
                  "liked_by_me": true,
                  "media": [{
                    "id": 7,
                    "kind": "video",
                    "mime_type": "video/mp4",
                    "original_name": "family.mp4",
                    "file_size": 1024,
                    "width": 1920,
                    "height": 1080,
                    "duration_ms": 2500,
                    "url": "/api/blog/media/7"
                  }]
                }
                """.trimIndent()
            )
        )

        assertEquals(42L, post.id)
        assertEquals("12345678", post.author.userCode)
        assertTrue(post.likedByMe)
        assertEquals(1, post.media.size)
        assertEquals("/api/blog/media/7", post.media.single().url)
        assertEquals(2500L, post.media.single().durationMs)
    }

    @Test
    fun parsesCommentAuthorIdentity() {
        val comment = BlogApi.parseComment(
            JSONObject(
                """
                {
                  "id": 9,
                  "post_id": 42,
                  "body": "Looks good",
                  "created_at": 1700000002000,
                  "updated_at": 1700000002000,
                  "author": {
                    "user_code": "87654321",
                    "username": "bob",
                    "color": "#336699",
                    "avatar_url": ""
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals(42L, comment.postId)
        assertEquals("87654321", comment.author.userCode)
        assertEquals("Looks good", comment.body)
    }
}
