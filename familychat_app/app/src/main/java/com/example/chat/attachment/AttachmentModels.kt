package com.example.chat

import android.net.Uri
import java.io.File

data class DecryptedAttachment(
    val name: String,
    val mime: String,
    val size: Long,
    val file: File? = null,
    val uri: Uri? = null,
)
