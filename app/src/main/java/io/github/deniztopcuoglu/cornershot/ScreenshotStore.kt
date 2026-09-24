package io.github.deniztopcuoglu.cornershot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.github.deniztopcuoglu.cornershot.geometry.IntRect
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

object ScreenshotStore {
    private const val SOURCE_DIR = "source"
    private const val CLIPBOARD_DIR = "clipboard"
    private const val CLIPBOARD_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    private const val SOURCE_RETENTION_MS = 24L * 60L * 60L * 1000L

    fun createSourceFile(context: Context): File {
        val directory = File(context.cacheDir, SOURCE_DIR).apply { mkdirs() }
        cleanupSourceFiles(directory)
        return File.createTempFile("capture_", ".png", directory).also {
            cleanupSourceFiles(directory)
        }
    }

    fun writeSource(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
            output.fd.sync()
        }
    }

    fun sourceUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun deleteSource(context: Context, uri: Uri?) {
        val file = uri?.let { fileForCacheUri(context, it, SOURCE_DIR) } ?: return
        runCatching { file.delete() }
    }

    fun saveCrop(context: Context, source: Bitmap, rect: IntRect): Uri {
        val crop = crop(source, rect)
        var inserted: Uri? = null
        try {
            val filename = "CornerShot_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Screenshots")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val imageUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore could not create image")
            inserted = imageUri
            context.contentResolver.openOutputStream(imageUri, "w")?.use { output ->
                check(crop.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
                output.flush()
            } ?: error("MediaStore output could not be opened")
            val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            check(context.contentResolver.update(imageUri, published, null, null) > 0) { "Image could not be published" }
            return imageUri
        } catch (failure: Throwable) {
            inserted?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            throw failure
        } finally {
            if (crop !== source) crop.recycle()
        }
    }

    fun createClipboardUri(context: Context, source: Bitmap, rect: IntRect): Uri {
        val directory = File(context.cacheDir, CLIPBOARD_DIR).apply { mkdirs() }
        cleanupClipboardFiles(directory)
        val file = File.createTempFile("CornerShot_", ".png", directory)
        val crop = crop(source, rect)
        try {
            FileOutputStream(file).use { output ->
                check(crop.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
                output.fd.sync()
            }
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        } finally {
            if (crop !== source) crop.recycle()
        }
    }

    fun copyToClipboard(context: Context, uri: Uri) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "CornerShot image", uri))
    }

    fun cleanupStaleCache(context: Context) {
        cleanupSourceFiles(File(context.cacheDir, SOURCE_DIR))
        cleanupClipboardFiles(File(context.cacheDir, CLIPBOARD_DIR))
    }

    private fun crop(source: Bitmap, rect: IntRect): Bitmap {
        val left = rect.left.coerceIn(0, source.width - 1)
        val top = rect.top.coerceIn(0, source.height - 1)
        val right = rect.right.coerceIn(left + 1, source.width)
        val bottom = rect.bottom.coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, max(1, right - left), max(1, bottom - top))
    }

    private fun cleanupClipboardFiles(directory: File) {
        if (!directory.isDirectory) return
        val oldestAllowed = System.currentTimeMillis() - CLIPBOARD_RETENTION_MS
        val newestFirst = directory.listFiles()?.filter(File::isFile)?.sortedByDescending(File::lastModified).orEmpty()
        newestFirst.filter { it.lastModified() < oldestAllowed }.forEach { runCatching { it.delete() } }
        var retainedCount = 0
        var retainedBytes = 0L
        newestFirst.filter { it.exists() && it.lastModified() >= oldestAllowed }.forEach { file ->
            val size = file.length()
            val isCurrentNewest = retainedCount == 0
            if (isCurrentNewest || (retainedCount < MAX_CLIPBOARD_FILES && retainedBytes + size <= MAX_CLIPBOARD_BYTES)) {
                retainedCount++
                retainedBytes += size
            } else {
                if (!runCatching { file.delete() }.getOrDefault(false)) {
                    retainedCount++
                    retainedBytes += size
                }
            }
        }
    }

    private fun cleanupSourceFiles(directory: File) {
        if (!directory.isDirectory) return
        val oldestAllowed = System.currentTimeMillis() - SOURCE_RETENTION_MS
        val newestFirst = directory.listFiles()?.filter(File::isFile)?.sortedByDescending(File::lastModified).orEmpty()
        newestFirst.filter { it.lastModified() < oldestAllowed }.forEach { runCatching { it.delete() } }
        var retainedCount = 0
        var retainedBytes = 0L
        newestFirst.filter { it.exists() && it.lastModified() >= oldestAllowed }.forEach { file ->
            val size = file.length()
            if (retainedCount >= 3 || retainedBytes + size > 512L * 1024L * 1024L) {
                runCatching { file.delete() }
                if (file.exists()) {
                    retainedCount++
                    retainedBytes += size
                }
            } else {
                retainedCount++
                retainedBytes += size
            }
        }
    }

    private const val MAX_CLIPBOARD_FILES = 32
    private const val MAX_CLIPBOARD_BYTES = 256L * 1024L * 1024L

    private fun fileForCacheUri(context: Context, uri: Uri, allowedDirectory: String): File? {
        val root = File(context.cacheDir, allowedDirectory).canonicalFile
        val candidate = when {
            uri.scheme == "content" && uri.authority == "${context.packageName}.fileprovider" -> {
                val path = uri.path ?: return null
                val marker = "/$allowedDirectory/"
                val index = path.indexOf(marker)
                if (index < 0) return null
                File(root, path.substring(index + marker.length))
            }
            else -> return null
        }.canonicalFile
        return candidate.takeIf { it.path.startsWith(root.path + File.separator) }
    }
}
