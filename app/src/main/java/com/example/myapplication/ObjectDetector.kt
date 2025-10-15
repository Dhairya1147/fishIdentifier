package com.example.myapplication

// ObjectDetector.kt
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock

import com.google.firebase.crashlytics.buildtools.reloc.org.apache.commons.cli.Options
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector


class ObjectDetector(
    private val context: Context,
    private val modelPath: String,
    private val detectorListener: DetectorListener
) {

    private var objectDetector: ObjectDetector? = null

    init {
        setupObjectDetector()
    }

    private fun setupObjectDetector() {
        val optionsBuilder = ObjectDetector.ObjectDetectorOptions.builder()
            .setScoreThreshold(0.5f) // Confidence threshold
            .setMaxResults(5) // Max objects to detect
        val baseOptionsBuilder = BaseOptions.builder().useNnapi() // Use hardware acceleration
        optionsBuilder.setBaseOptions(baseOptionsBuilder.build())

        try {
            objectDetector = ObjectDetector.createFromFileAndOptions(context, modelPath, optionsBuilder.build())
        } catch (e: Exception) {
            detectorListener.onError("Failed to initialize object detector: ${e.message}")
        }
    }

    fun detect(image: Bitmap, imageRotation: Int) {
        if (objectDetector == null) {
            setupObjectDetector()
        }

        var inferenceTime = SystemClock.uptimeMillis()

        // Preprocess the image for the model
        val imageProcessor = ImageProcessor.Builder().add(Rot90Op(-imageRotation / 90)).build()
        val tensorImage = imageProcessor.process(TensorImage.fromBitmap(image))

        // Run detection
        val results = objectDetector?.detect(tensorImage)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime

        detectorListener.onResults(results, inferenceTime, tensorImage.height, tensorImage.width)
    }

    interface DetectorListener {
        fun onError(error: String)
        fun onResults(
            results: MutableList<Detection>?,
            inferenceTime: Long,
            imageHeight: Int,
            imageWidth: Int
        )
    }
}