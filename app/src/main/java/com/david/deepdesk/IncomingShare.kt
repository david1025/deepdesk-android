package com.david.deepdesk

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

internal data class IncomingShare(val files: List<Uri>, val text: String) {
    companion object {
        fun from(intent: Intent, ownFileProvider: String): IncomingShare? {
            val streams: List<*> = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
                )
                Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java,
                ).orEmpty()
                else -> return null
            }
            val clip = intent.clipData
            val files = buildList {
                addAll(streams.filterIsInstance<Uri>())
                if (clip != null) {
                    for (index in 0 until clip.itemCount) {
                        clip.getItemAt(index).uri?.let(::add)
                    }
                }
            }.filter { it.scheme == ContentResolver.SCHEME_CONTENT && it.authority != ownFileProvider }.distinct()
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            return IncomingShare(files, text)
        }
    }
}
