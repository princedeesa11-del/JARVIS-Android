package com.example.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX Pipeline for JARVIS Vision.
 * Provides ProcessCameraProvider lifecycle binding, PreviewView stream,
 * ImageCapture with rotation correction, front/back lens switching, and torch control.
 */
class CameraXManager(private val context: Context) {

    private val tag = "CameraXManager"
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    var isTorchEnabled: Boolean = false
        private set

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set

    fun initialize(onReady: () -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                onReady()
            } catch (e: Exception) {
                Log.e(tag, "Failed to get ProcessCameraProvider: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onError: (String) -> Unit
    ) {
        val provider = cameraProvider ?: run {
            onError("CameraProvider not ready")
            return
        }

        try {
            provider.unbindAll()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(previewView.display?.rotation ?: android.view.Surface.ROTATION_0)
                .build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )

            // Re-apply torch state if back camera
            if (lensFacing == CameraSelector.LENS_FACING_BACK && isTorchEnabled) {
                camera?.cameraControl?.enableTorch(true)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to bind camera use cases: ${e.message}", e)
            onError("Failed to bind camera: ${e.localizedMessage}")
        }
    }

    fun switchCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onError: (String) -> Unit
    ) {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        isTorchEnabled = false
        bindCamera(lifecycleOwner, previewView, onError)
    }

    fun toggleTorch(onSuccess: (Boolean) -> Unit, onError: (String) -> Unit) {
        val cam = camera ?: run {
            onError("Camera not active")
            return
        }
        if (lensFacing != CameraSelector.LENS_FACING_BACK) {
            onError("Torch is only available on back camera")
            return
        }
        val targetTorch = !isTorchEnabled
        cam.cameraControl.enableTorch(targetTorch).addListener({
            isTorchEnabled = targetTorch
            onSuccess(isTorchEnabled)
        }, ContextCompat.getMainExecutor(context))
    }

    fun captureStillImage(
        onSuccess: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        val capture = imageCapture ?: run {
            onError("ImageCapture not initialized")
            return
        }

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        val bitmap = imageProxyToBitmap(imageProxy, rotationDegrees)
                        ContextCompat.getMainExecutor(context).execute {
                            onSuccess(bitmap)
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to convert image proxy: ${e.message}", e)
                        ContextCompat.getMainExecutor(context).execute {
                            onError("Failed to process captured image: ${e.message}")
                        }
                    } finally {
                        imageProxy.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(tag, "Camera capture error: ${exception.message}", exception)
                    ContextCompat.getMainExecutor(context).execute {
                        onError("Camera capture failed: ${exception.message}")
                    }
                }
            }
        )
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy, rotationDegrees: Int): Bitmap {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Failed to decode ImageProxy buffer into Bitmap")

        return if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        } else {
            rawBitmap
        }
    }

    fun unbind() {
        try {
            cameraProvider?.unbindAll()
            camera = null
            imageCapture = null
            isTorchEnabled = false
        } catch (e: Exception) {
            Log.e(tag, "Error unbinding camera: ${e.message}")
        }
    }

    fun shutdown() {
        unbind()
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }
    }
}
