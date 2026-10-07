package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity

/**
 * Legitimate MediaProjection foreground service for user-consented screen understanding.
 * Requires explicit user consent via MediaProjectionManager intent.
 * Captures screen frame into bitmap for OCR or multimodal AI analysis, then immediately releases.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            captureScreen(resultCode, resultData)
        } else {
            Log.e(TAG, "Screen capture failed: User consent not granted or missing projection data.")
            onCaptureFailed?.invoke("Screen capture permission was not granted by user.")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun captureScreen(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpManager == null) {
            onCaptureFailed?.invoke("MediaProjectionManager not supported on this device.")
            stopSelf()
            return
        }

        try {
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                onCaptureFailed?.invoke("Failed to initialize MediaProjection token.")
                stopSelf()
                return
            }

            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = (metrics.widthPixels).coerceAtLeast(480)
            val height = (metrics.heightPixels).coerceAtLeast(800)
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "JarvisScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )

            // Capture frame after virtual display paints
            handler.postDelayed({
                processImageReaderFrame(width, height)
            }, 600)

        } catch (e: Exception) {
            Log.e(TAG, "Error initiating screen capture: ${e.message}")
            onCaptureFailed?.invoke("Screen capture failed: ${e.localizedMessage}")
            cleanUp()
            stopSelf()
        }
    }

    private fun processImageReaderFrame(width: Int, height: Int) {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                val croppedBitmap = if (rowPadding > 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, width, height)
                } else {
                    bitmap
                }
                image.close()

                Log.i(TAG, "Screen frame captured successfully: ${width}x${height}")
                onScreenCaptured?.invoke(croppedBitmap)
            } else {
                onCaptureFailed?.invoke("No screen frame available from ImageReader.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading screen buffer: ${e.message}")
            onCaptureFailed?.invoke("Failed to process captured screen frame: ${e.localizedMessage}")
        } finally {
            cleanUp()
            stopSelf()
        }
    }

    private fun cleanUp() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Screen Understanding")
            .setContentText("Capturing user-consented screen frame for analysis...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Screen Capture",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notification for active screen analysis session"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val CHANNEL_ID = "jarvis_screen_capture_channel"
        const val NOTIFICATION_ID = 2002
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        var onScreenCaptured: ((Bitmap) -> Unit)? = null
        var onCaptureFailed: ((String) -> Unit)? = null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
