package com.example.chat

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "blog_posts")
internal data class BlogPostEntity(
    @PrimaryKey val key: String,
    val ownerId: String,
    val postId: Long,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val authorUserCode: String,
    val authorUsername: String,
    val authorColor: String,
    val authorAvatarUrl: String,
    val likeCount: Int,
    val commentCount: Int,
    val likedByMe: Boolean,
    val isAnnouncement: Boolean,
    val mediaJson: String,
)

@Entity(tableName = "blog_comments")
internal data class BlogCommentEntity(
    @PrimaryKey val key: String,
    val ownerId: String,
    val commentId: Long,
    val postId: Long,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val authorUserCode: String,
    val authorUsername: String,
    val authorColor: String,
    val authorAvatarUrl: String,
)

@Dao
internal interface BlogDao {
    @Query("SELECT * FROM blog_posts WHERE ownerId=:ownerId ORDER BY postId DESC")
    fun observePosts(ownerId: String): Flow<List<BlogPostEntity>>

    @Query("SELECT * FROM blog_comments WHERE ownerId=:ownerId AND postId=:postId ORDER BY commentId")
    fun observeComments(ownerId: String, postId: Long): Flow<List<BlogCommentEntity>>

    @Upsert
    suspend fun upsertPosts(items: List<BlogPostEntity>)

    @Upsert
    suspend fun upsertPost(item: BlogPostEntity)

    @Upsert
    suspend fun upsertComments(items: List<BlogCommentEntity>)

    @Upsert
    suspend fun upsertComment(item: BlogCommentEntity)

    @Query("DELETE FROM blog_posts WHERE ownerId=:ownerId AND postId=:postId")
    suspend fun deletePost(ownerId: String, postId: Long)

    @Query("DELETE FROM blog_comments WHERE ownerId=:ownerId AND commentId=:commentId")
    suspend fun deleteComment(ownerId: String, commentId: Long)

    @Query("DELETE FROM blog_comments WHERE ownerId=:ownerId AND postId=:postId")
    suspend fun clearComments(ownerId: String, postId: Long)

    @Query("UPDATE blog_posts SET likedByMe=:liked, likeCount=:count WHERE ownerId=:ownerId AND postId=:postId")
    suspend fun updateLike(ownerId: String, postId: Long, liked: Boolean, count: Int)

    @Query("UPDATE blog_posts SET commentCount=:count WHERE ownerId=:ownerId AND postId=:postId")
    suspend fun updateCommentCount(ownerId: String, postId: Long, count: Int)

    @Query("DELETE FROM blog_posts WHERE ownerId=:ownerId")
    suspend fun clearPosts(ownerId: String)

    /**
     * Remove cached posts the server did not return in a page it claims is
     * complete from [minPostId] upwards. Older posts are left alone, because a
     * page only speaks for its own window.
     */
    @Query(
        "DELETE FROM blog_posts WHERE ownerId=:ownerId AND postId >= :minPostId AND postId NOT IN (:keepIds)"
    )
    suspend fun pruneMissingPosts(ownerId: String, minPostId: Long, keepIds: List<Long>)

    @Query(
        "DELETE FROM blog_comments WHERE ownerId=:ownerId AND postId NOT IN (SELECT postId FROM blog_posts WHERE ownerId=:ownerId)"
    )
    suspend fun pruneOrphanComments(ownerId: String)

    @Query("DELETE FROM blog_comments WHERE ownerId=:ownerId")
    suspend fun clearAllComments(ownerId: String)
}

@Database(entities = [BlogPostEntity::class, BlogCommentEntity::class], version = 2, exportSchema = true)
internal abstract class FamilyChatBlogDatabase : RoomDatabase() {
    abstract fun blogDao(): BlogDao
}

internal class LocalBlogStore(context: Context) {
    // Destructive migration is correct *here* and would not be elsewhere: this
    // database holds nothing but a cache of what the server already has, so
    // dropping it costs one refresh. Without it, adding a column ships an app
    // that crashes on open for everyone who already had the old schema - which
    // is precisely what adding `isAnnouncement` would otherwise have done.
    private val dao = Room.databaseBuilder(
        context.applicationContext,
        FamilyChatBlogDatabase::class.java,
        "family_chat_blog.sqlite",
    ).fallbackToDestructiveMigration(dropAllTables = true).build().blogDao()

    fun observePosts(ownerId: String): Flow<List<BlogPost>> =
        dao.observePosts(ownerId).map { rows -> rows.map { it.toModel() } }

    fun observeComments(ownerId: String, postId: Long): Flow<List<BlogComment>> =
        dao.observeComments(ownerId, postId).map { rows -> rows.map { it.toModel() } }

    suspend fun upsertPosts(ownerId: String, posts: List<BlogPost>) =
        dao.upsertPosts(posts.map { it.toEntity(ownerId) })

    /**
     * Apply the newest page as the truth for its range, rather than merging it.
     *
     * Merging was the bug: a post deleted on the server stayed on the phone
     * forever, because nothing in a refresh ever removed anything. The realtime
     * events cover the case where the app is watching, but an app that was
     * backgrounded, killed, or offline when a post was deleted never heard, and
     * no later refresh would tell it.
     *
     * An empty first page means the server has no posts at all - it is ordered
     * newest-first with no cursor - so the whole cache goes.
     */
    suspend fun reconcileNewestPage(ownerId: String, posts: List<BlogPost>, hasMore: Boolean) {
        when (val decision = newestPageDecision(posts.map { it.id }, hasMore)) {
            BlogCacheDecision.ClearAll -> {
                dao.clearAllComments(ownerId)
                dao.clearPosts(ownerId)
            }
            is BlogCacheDecision.Prune -> {
                dao.upsertPosts(posts.map { it.toEntity(ownerId) })
                dao.pruneMissingPosts(ownerId, decision.minPostId, decision.keepIds)
                dao.pruneOrphanComments(ownerId)
            }
        }
    }

    suspend fun upsertPost(ownerId: String, post: BlogPost) = dao.upsertPost(post.toEntity(ownerId))

    suspend fun replaceComments(ownerId: String, postId: Long, comments: List<BlogComment>) {
        dao.clearComments(ownerId, postId)
        dao.upsertComments(comments.map { it.toEntity(ownerId) })
    }

    suspend fun upsertComment(ownerId: String, comment: BlogComment) =
        dao.upsertComment(comment.toEntity(ownerId))

    suspend fun deletePost(ownerId: String, postId: Long) = dao.deletePost(ownerId, postId)

    suspend fun deleteComment(ownerId: String, commentId: Long) = dao.deleteComment(ownerId, commentId)

    suspend fun updateLike(ownerId: String, postId: Long, liked: Boolean, count: Int) =
        dao.updateLike(ownerId, postId, liked, count)

    suspend fun updateCommentCount(ownerId: String, postId: Long, count: Int) =
        dao.updateCommentCount(ownerId, postId, count)

    suspend fun clearOwner(ownerId: String) {
        dao.clearAllComments(ownerId)
        dao.clearPosts(ownerId)
    }

    private fun BlogPost.toEntity(ownerId: String) = BlogPostEntity(
        key = "$ownerId:$id",
        ownerId = ownerId,
        postId = id,
        body = body,
        createdAt = createdAt,
        updatedAt = updatedAt,
        authorUserCode = author.userCode,
        authorUsername = author.username,
        authorColor = author.color,
        authorAvatarUrl = author.avatarUrl,
        likeCount = likeCount,
        commentCount = commentCount,
        likedByMe = likedByMe,
        isAnnouncement = isAnnouncement,
        mediaJson = JSONArray().apply {
            media.forEach { item ->
                put(
                    JSONObject()
                        .put("id", item.id)
                        .put("kind", item.kind)
                        .put("mime_type", item.mimeType)
                        .put("original_name", item.originalName)
                        .put("file_size", item.fileSize)
                        .put("width", item.width)
                        .put("height", item.height)
                        .put("duration_ms", item.durationMs)
                        .put("url", item.url)
                )
            }
        }.toString(),
    )

    private fun BlogPostEntity.toModel(): BlogPost {
        val array = runCatching { JSONArray(mediaJson) }.getOrElse { JSONArray() }
        return BlogPost(
            id = postId,
            body = body,
            createdAt = createdAt,
            updatedAt = updatedAt,
            author = BlogAuthor(authorUserCode, authorUsername, authorColor, authorAvatarUrl),
            likeCount = likeCount,
            commentCount = commentCount,
            likedByMe = likedByMe,
            isAnnouncement = isAnnouncement,
            media = List(array.length()) { index ->
                val item = array.getJSONObject(index)
                BlogMedia(
                    id = item.getLong("id"),
                    kind = item.optString("kind"),
                    mimeType = item.optString("mime_type"),
                    originalName = item.optString("original_name"),
                    fileSize = item.optLong("file_size"),
                    width = item.optInt("width"),
                    height = item.optInt("height"),
                    durationMs = item.optLong("duration_ms"),
                    url = item.optString("url"),
                )
            },
        )
    }

    private fun BlogComment.toEntity(ownerId: String) = BlogCommentEntity(
        key = "$ownerId:$id",
        ownerId = ownerId,
        commentId = id,
        postId = postId,
        body = body,
        createdAt = createdAt,
        updatedAt = updatedAt,
        authorUserCode = author.userCode,
        authorUsername = author.username,
        authorColor = author.color,
        authorAvatarUrl = author.avatarUrl,
    )

    private fun BlogCommentEntity.toModel() = BlogComment(
        id = commentId,
        postId = postId,
        body = body,
        createdAt = createdAt,
        updatedAt = updatedAt,
        author = BlogAuthor(authorUserCode, authorUsername, authorColor, authorAvatarUrl),
    )
}
