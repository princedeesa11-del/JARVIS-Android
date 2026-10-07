package com.example.touchguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Camera2 Silent Front-Camera Photo Capturer for Touch Guard.
 * Captures an intruder/unauthorized touch evidence photo from the front-facing camera
 * without requiring an active visible UI activity or preview surface.
 * Stores captured evidence securely and privately on the device internal storage.
 * Never uploads photos anywhere without explicit owner instruction.
 */
object TouchGuardCameraCapturer {

    private const val TAG = "TouchGuardCamera"

    fun captureFrontPhoto(context: Context, triggerReason: String, onCaptured: (File?) -> Unit) {
        val appContext = context.applicationContext
        val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cameraManager == null) {
            Log.e(TAG, "CameraManager unavailable")
            onCaptured(null)
            return
        }

        val frontCameraId = findFrontCameraId(cameraManager)
        if (frontCameraId == null) {
            Log.e(TAG, "Front camera not found on device")
            onCaptured(null)
            return
        }

        val handlerThread = HandlerThread("TouchGuardCameraThread").apply { start() }
        val backgroundHandler = Handler(handlerThread.looper)

        val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)

        imageReader.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    val savedFile = saveEvidencePhoto(appContext, bytes, triggerReason)
                    Log.i(TAG, "Touch Guard front photo saved: ${savedFile.absolutePath}")
                    onCaptured(savedFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving captured image: ${e.message}", e)
                onCaptured(null)
            } finally {
                image?.close()
                imageReader.close()
                handlerThread.quitSafely()
            }
        }, backgroundHandler)

        try {
            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }

                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                result: TotalCaptureResult
                                            ) {
                                                Log.i(TAG, "Still capture completed successfully")
                                                camera.close()
                                            }

                                            override fun onCaptureFailed(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                failure: CaptureFailure
                                            ) {
                                                Log.e(TAG, "Still capture failed: ${failure.reason}")
                                                camera.close()
                                                onCaptured(null)
                                                handlerThread.quitSafely()
                                            }
                                        }, backgroundHandler)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to submit capture request: ${e.message}", e)
                                        camera.close()
                                        onCaptured(null)
                                        handlerThread.quitSafely()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(TAG, "Capture session configuration failed")
                                    camera.close()
                                    onCaptured(null)
                                    handlerThread.quitSafely()
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed creating capture session: ${e.message}", e)
                        camera.close()
                        onCaptured(null)
                        handlerThread.quitSafely()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    onCaptured(null)
                    handlerThread.quitSafely()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera open error code: $error")
                    camera.close()
                    onCaptured(null)
                    handlerThread.quitSafely()
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission missing for Touch Guard: ${e.message}")
            onCaptured(null)
            handlerThread.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open front camera: ${e.message}", e)
            onCaptured(null)
            handlerThread.quitSafely()
        }
    }

    private fun findFrontCameraId(cameraManager: CameraManager): String? {
        return try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    return id
                }
            }
            cameraManager.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error listing camera IDs: ${e.message}")
            null
        }
    }

    private fun saveEvidencePhoto(context: Context, bytes: ByteArray, triggerReason: String): File {
        val evidenceDir = File(context.filesDir, "touch_guard_evidence").apply {
            if (!exists()) mkdirs()
        }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val cleanReason = triggerReason.lowercase().replace("[^a-z0-9]".toRegex(), "_")
        val photoFile = File(evidenceDir, "evidence_${timeStamp}_$cleanReason.jpg")

        FileOutputStream(photoFile).use { out ->
            out.write(bytes)
            out.flush()
        }
        return photoFile
    }
}
