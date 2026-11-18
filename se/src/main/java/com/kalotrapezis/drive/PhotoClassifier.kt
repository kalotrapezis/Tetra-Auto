package com.kalotrapezis.drive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.classifier.ImageClassifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

internal data class DetectedFace(val bounds: Rect, val embedding: FloatArray, val quality: Float, val yaw: Float, val roll: Float)
internal data class ClassifiedPhoto(val documentConfidence: Float, val faces: List<DetectedFace>, val labels: List<String>)

/** Runs entirely on-device. Call from a worker thread; it can be expensive for large galleries. */
internal class PhotoClassifier(private val context: Context, advancedSceneTags: Boolean = false) : AutoCloseable {
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build(),
    )
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val imageLabeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder().setConfidenceThreshold(0.7f).build(),
    )
    // The existing ML Kit tagger remains the fallback if this experimental model cannot load.
    private val sceneClassifier = advancedSceneTags.then {
        ImageClassifier.createFromFileAndOptions(
            context,
            "efficientnet_lite0.tflite",
            ImageClassifier.ImageClassifierOptions.builder().setMaxResults(5).setScoreThreshold(0.15f).build(),
        )
    }
    private val interpreter = Interpreter(context.assets.openFd("mobilefacenet.tflite").use { descriptor ->
        descriptor.createInputStream().channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
    })

    fun classify(uri: Uri, takenMillis: Long, analyzeFaces: Boolean): ClassifiedPhoto {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val scale = minOf(1f, 1280f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        val baseLabels = Tasks.await(imageLabeler.process(image)).sortedByDescending { it.confidence }.take(5).map { it.text }
        val sceneLabels = sceneClassifier?.let { classifier -> runCatching {
            classifier.classify(TensorImage.fromBitmap(bitmap)).flatMap { it.categories }.map { "Scene: ${it.label}" }
        }.getOrDefault(emptyList()) }.orEmpty()
        val text = if (isPaperPhoto(baseLabels)) Tasks.await(textRecognizer.process(image)) else null
        val textChars = text?.text?.count(Char::isLetterOrDigit) ?: 0
        val textBlocks = text?.textBlocks?.size ?: 0
        val documentConfidence = when {
            textChars >= 180 && textBlocks >= 3 -> 0.95f
            textChars >= 80 && textBlocks >= 2 -> 0.70f
            textChars >= 35 -> 0.45f
            else -> 0f
        }
        val faces = if (analyzeFaces && !isPaperPhoto(baseLabels)) Tasks.await(faceDetector.process(image)).mapNotNull { face ->
            face.boundingBox.clamp(bitmap.width, bitmap.height)?.let { bounds ->
                val eyes = face.eyePair() ?: return@mapNotNull null
                val quality = bitmap.faceQuality(bounds)
                val yaw = face.headEulerAngleY
                val roll = face.headEulerAngleZ
                DetectedFace(bounds, embed(bitmap, eyes.first, eyes.second), quality, yaw, roll).takeIf { isReliableFace(quality, yaw, roll) }
            }
        } else emptyList()
        return ClassifiedPhoto(documentConfidence, faces, (baseLabels + sceneLabels + likelyTimeOfDay(takenMillis, bitmap.averageLuminance())).filterNotNull())
    }

    /** MobileFaceNet expects a canonical, upright face rather than an arbitrary box crop. */
    private fun embed(source: Bitmap, leftEye: PointF, rightEye: PointF): FloatArray {
        val face = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        val sourceEyes = floatArrayOf(leftEye.x, leftEye.y, rightEye.x, rightEye.y)
        val targetEyes = floatArrayOf(38f, 44f, 74f, 44f)
        val matrix = Matrix().apply { setPolyToPoly(sourceEyes, 0, targetEyes, 0, 2) }
        Canvas(face).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(112 * 112)
        face.getPixels(pixels, 0, 112, 0, 0, 112, 112)
        val input = ByteBuffer.allocateDirect(112 * 112 * 3 * 4).order(ByteOrder.nativeOrder())
        pixels.forEach { pixel ->
            input.putFloat(((pixel shr 16 and 0xff) - 127.5f) / 127.5f)
            input.putFloat(((pixel shr 8 and 0xff) - 127.5f) / 127.5f)
            input.putFloat(((pixel and 0xff) - 127.5f) / 127.5f)
        }
        val output = Array(1) { FloatArray(192) }
        interpreter.run(input, output)
        return output[0].l2Normalized()
    }

    override fun close() {
        faceDetector.close()
        textRecognizer.close()
        imageLabeler.close()
        sceneClassifier?.close()
        interpreter.close()
    }
}

/** OCR is reserved for the scene labels that actually look like paper. */
internal fun isPaperPhoto(labels: Collection<String>): Boolean = labels.any { it.equals("paper", ignoreCase = true) }

private inline fun <T> Boolean.then(block: () -> T): T? = if (this) runCatching(block).getOrNull() else null

internal fun faceQualityScore(minSide: Int, meanEdgeContrast: Int): Float =
    ((minSide / 112f).coerceIn(0f, 1f) * 0.45f) + ((meanEdgeContrast / 18f).coerceIn(0f, 1f) * 0.55f)

/** Group anchors must be sharp enough and roughly front-facing. */
internal fun isReliableFace(quality: Float, yaw: Float = 0f, roll: Float = 0f): Boolean =
    quality >= 0.68f && abs(yaw) <= 30f && abs(roll) <= 20f

internal fun hasUsableEyeDistance(distance: Float): Boolean = distance >= 32f

private fun Bitmap.faceQuality(bounds: Rect): Float {
    val step = maxOf(1, minOf(bounds.width(), bounds.height()) / 24)
    var contrast = 0L
    var samples = 0
    for (y in bounds.top until bounds.bottom - step step step) for (x in bounds.left until bounds.right - step step step) {
        contrast += abs(pixelLuminance(getPixel(x, y)) - pixelLuminance(getPixel(x + step, y)))
        contrast += abs(pixelLuminance(getPixel(x, y)) - pixelLuminance(getPixel(x, y + step)))
        samples += 2
    }
    return faceQualityScore(minOf(bounds.width(), bounds.height()), (contrast / samples.coerceAtLeast(1)).toInt())
}

private fun pixelLuminance(pixel: Int): Int = ((pixel shr 16 and 0xff) * 299 + (pixel shr 8 and 0xff) * 587 + (pixel and 0xff) * 114) / 1000

internal fun likelyTimeOfDay(takenMillis: Long, luminance: Int): String? {
    val hour = takenMillis.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).hour }
    return when {
        hour != null && (hour >= 20 || hour <= 5) -> "Likely night"
        luminance < 28 -> "Likely night"
        hour != null && hour in 7..18 && luminance >= 60 -> "Likely day"
        else -> null
    }
}

private fun Bitmap.averageLuminance(): Int {
    val step = maxOf(1, minOf(width, height) / 48)
    var total = 0L
    var count = 0
    for (y in 0 until height step step) for (x in 0 until width step step) {
        val pixel = getPixel(x, y)
        total += (pixel shr 16 and 0xff) * 299 + (pixel shr 8 and 0xff) * 587 + (pixel and 0xff) * 114
        count++
    }
    return (total / (count.coerceAtLeast(1) * 1000)).toInt()
}

private fun Rect.clamp(width: Int, height: Int): Rect? = Rect(
    left.coerceIn(0, width), top.coerceIn(0, height), right.coerceIn(0, width), bottom.coerceIn(0, height),
).takeIf { it.width() >= 24 && it.height() >= 24 }

private fun com.google.mlkit.vision.face.Face.eyePair(): Pair<PointF, PointF>? {
    val first = getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
    val second = getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
    val left = if (first.x <= second.x) first else second
    val right = if (first.x <= second.x) second else first
    return (left to right).takeIf { hasUsableEyeDistance(abs(right.x - left.x)) }
}

internal fun FloatArray.l2Normalized(): FloatArray {
    val magnitude = sqrt(sumOf { value -> value * value.toDouble() }).toFloat().coerceAtLeast(0.00001f)
    return FloatArray(size) { this[it] / magnitude }
}

/**
 * Two boxes on one photo that overlap this much are the same face, whichever detector found it — the computer
 * uses the same number (faces.js SAME_FACE_OVERLAP), which is what lets the two devices recognise each other's
 * faces without comparing a single vector.
 */
internal const val SAME_FACE_OVERLAP = 0.4f

internal fun faceOverlap(
    left: Int, top: Int, right: Int, bottom: Int,
    otherLeft: Int, otherTop: Int, otherRight: Int, otherBottom: Int,
): Float {
    val width = (minOf(right, otherRight) - maxOf(left, otherLeft)).coerceAtLeast(0)
    val height = (minOf(bottom, otherBottom) - maxOf(top, otherTop)).coerceAtLeast(0)
    val intersection = width.toLong() * height
    val union = (right - left).toLong() * (bottom - top) + (otherRight - otherLeft).toLong() * (otherBottom - otherTop) - intersection
    return if (union > 0) intersection.toFloat() / union else 0f
}

internal fun faceOverlap(first: android.graphics.Rect, second: android.graphics.Rect): Float =
    faceOverlap(first.left, first.top, first.right, first.bottom, second.left, second.top, second.right, second.bottom)

/**
 * Where a face counts as someone this library already knows.
 *
 * Measured twice, and the second measurement is the one to trust. The first (2026-09-23, §6o) compared **random
 * pairs of faces**, which is not what the app does; this one measures the comparison it actually makes — a new
 * face against each known person, taking that person's closest face — on this library's own named people, 304
 * faces across 27–28 people on each device:
 *
 *   line   joins of the same person (phone / computer)   different people wrongly joined
 *   0.60            90.1% / 85.2%                              1.61% / 1.58%
 *   0.68            80.6% / 76.6%                              0.31% / 0.33%
 *   0.72            74.0% / 70.1%                              0.15% / 0.10%
 *   0.75            68.4% / 63.2%                              0.02% / 0.00%
 *
 * 0.60 was joining wrongly on one comparison in sixty, which is exactly what it looked like in the library. The
 * line is now 0.75, where different people essentially never meet, and the band below it goes to review instead
 * of being joined silently. That is the same rule as everywhere else here: what cannot be undone is strict, and
 * what is uncertain becomes a question, which can be.
 *
 * Note what is being paid for. Raising the line from 0.68 to 0.75 gives up about a sixth of the joins to remove
 * nine tenths of the wrong ones — worth it because the two mistakes are not equal. A join that should not have
 * happened has to be found and picked apart by hand; one that did not happen is one Combine, or one answer to a
 * card that is now being asked.
 *
 * The labels are the user's own grouping, so a "different people" pair that scores high may be two groups that
 * are really one person. That makes the wrong-join column, if anything, pessimistic. Retune from the same
 * measurement if the model or the crop ever changes.
 */
internal const val SAME_PERSON = 0.75f

/**
 * The day a photo was taken, as evidence about who is in it.
 *
 * Two photos taken seconds apart hold the same people, and the model does not know that: it looks at one face
 * at a time and sometimes fails to recognise someone between two frames of the same moment. The calendar is
 * free evidence that the pixels do not carry.
 *
 * Measured on this library's own named people (2026-09-24, 290 faces across 46 people, 7020 comparisons of the
 * kind the app actually makes):
 *
 *   a comparison against a person who appears on the same day  → the same person **39.3%** of the time
 *   a comparison against a person who appears on another day   → the same person **1.6%** of the time
 *
 * Twenty-five times the prior, so it is worth something — but it is evidence, not proof, so it is worth a
 * nudge rather than a licence. What each size buys, at the 0.75 line:
 *
 *   bonus   same-person joins   wrong joins (out of 7020)
 *   +0.00        58.1%                 0
 *   +0.05        63.0%                 1
 *   +0.10        64.4%                 3
 *   +0.15        66.7%                14
 *
 * +0.05 takes five points of the joins the line was missing for a single wrong one, and the knee is well before
 * +0.15. Ten-minute windows were measured too and bought nothing a day did not: they cover fewer comparisons
 * (4.7% against 5.9%) for the same gain, so the day is the better unit as well as the simpler one.
 */
internal const val SAME_DAY_BONUS = 0.05f

/** The calendar day a moment falls on, where this device is. Two photos on it are one day's worth of people. */
internal fun dayOf(takenMillis: Long): Long =
    if (takenMillis <= 0) Long.MIN_VALUE
    else Math.floorDiv(takenMillis + java.util.TimeZone.getDefault().getOffset(takenMillis), 86_400_000L)
internal const val REVIEW_FROM = 0.45f

internal fun cosineSimilarity(first: FloatArray, second: FloatArray): Float =
    first.indices.sumOf { index -> (first[index] * second[index]).toDouble() }.toFloat()
