package com.david.deepdesk.feature.chat.ui

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.david.deepdesk.core.model.Attachment
import com.david.deepdesk.core.ui.R as UiR
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberImageDownload(): (Attachment) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Attachment?>(null) }

    fun save(image: Attachment, destination: Uri? = null) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val source = image.localPath?.let(::File) ?: throw IOException("Image is unavailable")
                    if (destination != null) {
                        resolver.copyImage(source, destination)
                    } else if (Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, image.displayName)
                            put(MediaStore.Images.Media.MIME_TYPE, image.mimeType)
                            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/DeepDesk")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            ?: throw IOException("Unable to create image")
                        try {
                            resolver.copyImage(source, uri)
                            values.clear()
                            values.put(MediaStore.Images.Media.IS_PENDING, 0)
                            if (resolver.update(uri, values, null, null) != 1) throw IOException("Unable to publish image")
                        } catch (e: Exception) {
                            runCatching { resolver.delete(uri, null, null) }
                            throw e
                        }
                    } else {
                        throw IOException("No save destination")
                    }
                }.isSuccess
            }
            Toast.makeText(
                context,
                if (saved) UiR.string.image_download_success else UiR.string.image_download_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val image = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK && image != null) {
            result.data?.data?.let { save(image, it) }
        }
    }

    return { image ->
        if (Build.VERSION.SDK_INT >= 29) {
            save(image)
        } else {
            pending = image
            saveAs.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = image.mimeType
                putExtra(Intent.EXTRA_TITLE, image.displayName)
            })
        }
    }
}

private fun ContentResolver.copyImage(source: File, uri: Uri) {
    source.inputStream().use { input ->
        val output = openOutputStream(uri) ?: throw IOException("Unable to open save destination")
        output.use { input.copyTo(it) }
    }
}
