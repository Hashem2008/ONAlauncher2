/*
 * ONA Controls - floating overlay controls. GPL-3.0.
 */
package com.onalauncher.controls.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.UUID

/**
 * Invisible helper: opens the system photo picker for the overlay editor (a service cannot get
 * activity results), copies the picked picture into app storage and hands back its path.
 */
class ImagePickActivity : ComponentActivity() {

    companion object {
        /** Set by the overlay service before starting this activity; gets the file path or null. */
        @Volatile var callback: ((String?) -> Unit)? = null
    }

    private var delivered = false

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        deliver(uri?.let { copy(it) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun copy(uri: Uri): String? = runCatching {
        val dir = File(filesDir, "ona_overlay/images").apply { mkdirs() }
        val out = File(dir, "img_" + UUID.randomUUID().toString().take(12))
        contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        // keep it only if it really is a picture
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) { out.delete(); null } else out.path
    }.getOrNull()

    private fun deliver(path: String?) {
        if (delivered) return
        delivered = true
        callback?.invoke(path)
        finish()
    }

    override fun onDestroy() {
        // closed without a result (e.g. back pressed while the picker was starting)
        if (isFinishing && !delivered) {
            delivered = true
            callback?.invoke(null)
        }
        super.onDestroy()
    }
}
