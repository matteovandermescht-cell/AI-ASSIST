package com.matteo.aiassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream

/**
 * Captures frames on a dedicated HandlerThread. The UI thread never waits for capture
 * or performs bitmap conversion/JPEG compression.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val channelId = "ai_assistent_screen_capture"
    @Volatile private var lastSavedAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        captureThread = HandlerThread("AI-ScreenCapture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("AI ASSISTENT")
            .setContentText("Bildschirmfreigabe aktiv – über Android stoppen.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(22, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(22, notification)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null || resultCode == 0) { stopSelf(); return START_NOT_STICKY }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, captureHandler)

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtMost(1280)
            val height = (metrics.heightPixels * (width.toFloat() / metrics.widthPixels)).toInt()
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader?.setOnImageAvailableListener({ source ->
                val now = System.currentTimeMillis()
                if (now - lastSavedAt < FRAME_INTERVAL_MS) {
                    source.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                lastSavedAt = now
                try { saveImage(image) } catch (_: Exception) { } finally { image.close() }
            }, captureHandler)

            virtualDisplay = projection?.createVirtualDisplay(
                "AI-ASSISTENT-Capture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, captureHandler
            )
        } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    private fun saveImage(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()

        val file = File(cacheDir, "screen_${System.nanoTime()}.jpg")
        FileOutputStream(file).use { cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        cropped.recycle()

        synchronized(frameLock) {
            latestFramePath = file.absolutePath
            frameVersion++
            frameTimestamp = System.currentTimeMillis()
            frameLock.notifyAll()
        }
        cacheDir.listFiles()?.filter {
            it.name.startsWith("screen_") && System.currentTimeMillis() - it.lastModified() > 30000
        }?.forEach { it.delete() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Bildschirmfreigabe", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        reader?.close()
        projection?.stop()
        projection = null
        synchronized(frameLock) {
            latestFramePath = null
            frameTimestamp = 0L
            frameLock.notifyAll()
        }
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        private const val FRAME_INTERVAL_MS = 120L
        private const val JPEG_QUALITY = 78
        private val frameLock = Object()
        @Volatile private var frameVersion = 0L
        @Volatile private var frameTimestamp = 0L
        @Volatile private var latestFramePath: String? = null

        @JvmStatic fun latestFramePath(): String? = synchronized(frameLock) {
            val path = latestFramePath
            if (path != null && System.currentTimeMillis() - frameTimestamp <= 2000L) path else null
        }

        @JvmStatic fun currentFrameVersion(): Long = frameVersion

        @JvmStatic fun awaitNextFramePath(afterVersion: Long, timeoutMs: Long): String? {
            val deadline = System.currentTimeMillis() + timeoutMs
            synchronized(frameLock) {
                while (frameVersion <= afterVersion && latestFramePath != null || frameVersion <= afterVersion && frameTimestamp == 0L) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) return null
                    try { frameLock.wait(remaining) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
                val path = latestFramePath
                return if (path != null && System.currentTimeMillis() - frameTimestamp <= 2000L) path else null
            }
        }
    }
}
