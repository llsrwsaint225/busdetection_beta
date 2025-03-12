package com.llsrwsaintt.busdetection_beta

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.llsrwsaintt.busdetection_beta.Constants.LABELS_PATH
import com.llsrwsaintt.busdetection_beta.Constants.MODEL_PATH
import com.llsrwsaintt.busdetection_beta.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detector: Detector

    private lateinit var textToSpeech: TextToSpeech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        textToSpeech = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.ENGLISH
            }
        }

        detector = Detector(this, MODEL_PATH, LABELS_PATH, this)
        detector.setup()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detector.clear()
        textToSpeech.stop()
        textToSpeech.shutdown()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                        val bitmap = BitmapUtils.getBitmap(imageProxy)
                        val rotateBitmap = rotateBitmap(bitmap, 90f)
                        detector.detect(rotateBitmap)
                        imageProxy.close()
                    })
                }

            val cameraSelector = if (isFrontCamera)
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                finish()
            }
        }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.textView.text = "No bus detected"
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            val text = StringBuilder()
            boundingBoxes.forEach { box ->
                text.append("${box.clsName}: ${box.cnf * 100}%\n")
            }
            binding.textView.text = "Inference time: ${inferenceTime}ms\n${text.toString()}"
        }

        boundingBoxes.forEach { box ->
            when (box.clsName) {
                Constants.BMTA_BUS,
                Constants.BUS_LINE_NUMBER,
                Constants.BUS_SIDE_NUMBER,
                Constants.DESTINATION_SIGN,
                Constants.TSB_BUS -> {
                    announceDetection(box.clsName)
                }
            }
        }
    }

    private fun announceDetection(clsName: String) {
        val message = when (clsName) {
            Constants.BMTA_BUS -> "BMTA bus detected"
            Constants.BUS_LINE_NUMBER -> "Bus line number detected"
            Constants.BUS_SIDE_NUMBER -> "Bus side number detected"
            Constants.DESTINATION_SIGN -> "Destination sign detected"
            Constants.TSB_BUS -> "TSB bus detected"
            else -> "No Bus detected"
        }

        textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    companion object {
        private const val TAG = "MainActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
