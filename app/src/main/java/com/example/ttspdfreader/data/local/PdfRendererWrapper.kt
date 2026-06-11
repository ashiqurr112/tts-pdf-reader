package com.example.ttspdfreader.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class PdfRendererWrapper(
    private val context: Context,
    private val uriString: String
) {
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    
    private val rendererExecutor = Executors.newSingleThreadExecutor()
    private val rendererDispatcher: CoroutineDispatcher = rendererExecutor.asCoroutineDispatcher()

    var pageCount: Int = 0
        private set

    init {
        val uri = Uri.parse(uriString)
        fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
        fileDescriptor?.let {
            renderer = PdfRenderer(it)
            pageCount = renderer?.pageCount ?: 0
        }
    }

    suspend fun renderPage(pageIndex: Int): Bitmap? = withContext(rendererDispatcher) {
        val currentRenderer = renderer ?: return@withContext null
        if (pageIndex < 0 || pageIndex >= pageCount) return@withContext null

        try {
            val page = currentRenderer.openPage(pageIndex)
            val density = context.resources.displayMetrics.density
            // Render at screen density to ensure crispness (approx 1.5x to 2x point scale)
            val targetWidth = (page.width * density).toInt()
            val targetHeight = (page.height * density).toInt()

            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun close() {
        try {
            renderer?.close()
            fileDescriptor?.close()
            rendererExecutor.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
