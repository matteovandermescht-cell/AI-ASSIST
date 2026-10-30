package com.matteo.aiassistant

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Local visual perception only. No language model, cloud inference, or accessibility text. */
class LocalVisionAgent(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun analyze(goal: String, imagePath: String, accessibilityText: String, step: Int, completed: Int): String {
        val bitmap = BitmapFactory.decodeFile(imagePath)
            ?: return decision(false, "Bild konnte nicht gelesen werden.", null)
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val objectTask = detector.process(image)
            val textTask = recognizer.process(image)
            val objects = Tasks.await(objectTask, 3500, TimeUnit.MILLISECONDS)
            val text = Tasks.await(textTask, 3500, TimeUnit.MILLISECONDS)

            val lines = text.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val bounds = line.boundingBox
                    if (bounds == null || line.text.isBlank()) null
                    else line.text.trim()
                }
            }
            val labels = objects.flatMap { item ->
                item.labels.map { it.text.lowercase(Locale.ROOT) }
            }.distinct()

            val summary = buildString {
                append("Bildanalyse abgeschlossen. ")
                append(objects.size).append(" Objekte erkannt; ")
                append(lines.size).append(" Textzeilen gelesen.")
                if (labels.isNotEmpty()) append(" Objekte: ").append(labels.take(8).joinToString(", "))
                if (lines.isNotEmpty()) append(" Sichtbarer Text: ")
                    .append(lines.take(8).joinToString(" | ").take(500))
                if (containsSensitiveText(lines.joinToString(" "))) {
                    append(" Hinweis: möglicherweise sensibler Text sichtbar; Inhalte werden nicht weiter ausgewertet.")
                }
            }
            return decision(false, summary, null)
        } catch (error: Exception) {
            return decision(false, "Bildanalyse fehlgeschlagen: ${error.javaClass.simpleName}.", null)
        } finally {
            bitmap.recycle()
        }
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    private fun containsSensitiveText(value: String): Boolean {
        val normalized = " " + normalize(value) + " "
        val blocked = listOf(
            "password", "passwort", "banking", "payment", "checkout", "kaufen", "purchase",
            "bestellen", "überweisen", "transfer", "login", "anmelden", "security", "sicherheit",
            "delete account", "konto löschen", "send", "senden", "publish", "veröffentlichen",
            "teilen", "share", "subscription", "abonnement"
        )
        return blocked.any { normalized.contains(" ${normalize(it)} ") }
    }

    private fun realScreenSize(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun decision(done: Boolean, summary: String, action: JSONObject?): String =
        JSONObject().put("done", done).put("summary", summary).put("action", action).toString()

    override fun close() { detector.close(); recognizer.close() }
    private data class VisibleLine(val text: String, val bounds: Rect)
}
