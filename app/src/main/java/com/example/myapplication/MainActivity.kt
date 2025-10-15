package com.example.myapplication

import android.Manifest
import android.graphics.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val MODEL_NAME = "model.tflite"
    private val INPUT_SIZE = 128 // change according to your model
    private lateinit var interpreter: Interpreter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ Correct ByteBuffer initialization
        val tfliteModel = assets.open(MODEL_NAME).use { input ->
            val bytes = input.readBytes()
            ByteBuffer.allocateDirect(bytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(bytes)
                rewind()
            }
        }
        interpreter = Interpreter(tfliteModel)

        setContent {
            TwoButtonCameraApp()
        }
    }

    data class DetectionBox(val rect: RectF, val confidence: Float, val label: String)

    @Composable
    fun TwoButtonCameraApp() {
        val context = LocalContext.current
        var detections by remember { mutableStateOf(listOf<DetectionBox>()) }
        var bitmapForDetection by remember { mutableStateOf<Bitmap?>(null) }
        var mode by remember { mutableStateOf("") }

        val cameraPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) mode = "camera"
        }

        LaunchedEffect(Unit) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Camera")
                }
                Button(onClick = { mode = "gallery" }) {
                    Text("Gallery")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (mode == "camera") {
                CameraPreview(
                    onFrame = { bmp -> bitmapForDetection = bmp },
                    detections = detections
                )
            } else if (mode == "gallery") {
                Text("Gallery selection not implemented yet")
            }
        }

        bitmapForDetection?.let { bmp ->
            LaunchedEffect(bmp) {
                detections = runTFLiteInference(bmp)
            }
        }
    }

    @Composable
    fun CameraPreview(
        onFrame: (Bitmap) -> Unit,
        detections: List<DetectionBox>
    ) {
        val context = LocalContext.current
        val lifecycleOwner = LocalContext.current as ComponentActivity
        val executor = Executors.newSingleThreadExecutor()

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(factory = { ctx ->
                val previewView = androidx.camera.view.PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(executor) { imageProxy ->
                                val bmp = imageProxy.toBitmap()
                                bmp?.let { onFrame(it) }
                                imageProxy.close()
                            }
                        }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalyzer
                    )
                }, executor)
                previewView
            }, modifier = Modifier.fillMaxSize())

            Canvas(modifier = Modifier.fillMaxSize()) {
                detections.forEach { det ->
                    drawRect(
                        color = Color.Red,
                        topLeft = androidx.compose.ui.geometry.Offset(det.rect.left, det.rect.top),
                        size = androidx.compose.ui.geometry.Size(det.rect.width(), det.rect.height()),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }
    }

    private fun runTFLiteInference(bitmap: Bitmap): List<DetectionBox> {
        val scaledBmp = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val input = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        input.order(ByteOrder.nativeOrder())

        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val px = scaledBmp.getPixel(x, y)
                input.putFloat((px shr 16 and 0xFF) / 255f)
                input.putFloat((px shr 8 and 0xFF) / 255f)
                input.putFloat((px and 0xFF) / 255f)
            }
        }
        input.rewind()

        val output = Array(1) { Array(10) { FloatArray(6) } } // [batch, max_detections, [x,y,w,h,conf,class]]
        interpreter.run(input, output)

        val detections = mutableListOf<DetectionBox>()
        for (det in output[0]) {
            val conf = det[4]
            if (conf > 0.5f) {
                val x = det[0]
                val y = det[1]
                val w = det[2]
                val h = det[3]
                detections.add(
                    DetectionBox(
                        RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2),
                        conf,
                        "Obj"
                    )
                )
            }
        }
        return detections
    }
}

fun ImageProxy.toBitmap(): Bitmap? {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = java.io.ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
    val imageBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}
