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
            // Start both local pipelines before waiting so OCR and object detection overlap.
            val objectTask = detector.process(image)
            val textTask = recognizer.process(image)
            val objects = Tasks.await(objectTask, 3500, TimeUnit.MILLISECONDS)
            val text = Tasks.await(textTask, 3500, TimeUnit.MILLISECONDS)
            val visibleLines = text.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val bounds = line.boundingBox
                    if (bounds == null || line.text.isBlank()) null
                    else VisibleLine(line.text.trim(), Rect(bounds))
                }
            }
            val labels = objects.flatMap { item -> item.labels.map { it.text.lowercase(Locale.ROOT) } }.distinct()
            val visibleSummary = buildString {
                append("Bild geprüft: ").append(objects.size).append(" Objekte, ")
                    .append(visibleLines.size).append(" Textbereiche.")
                if (labels.isNotEmpty()) append(" Klassen: ").append(labels.joinToString(", "))
            }

            if (containsSensitiveText(visibleLines.joinToString(" ") { it.text })) {
                return decision(false, "Sicherheitsstopp: sensibler Bildschirmtext erkannt. $visibleSummary", null)
            }

            val target = extractExplicitTapTarget(goal)
                ?: return decision(false, "Kein eindeutiges Textziel im Auftrag. $visibleSummary", null)
            val match = visibleLines.asSequence()
                .map { line -> line to textMatchScore(target, line.text) }
                .filter { it.second >= 0.78 }
                .maxByOrNull { it.second }
                ?: return decision(false, "„$target“ im aktuellen Bild nicht eindeutig erkannt; keine Aktion. $visibleSummary", null)

            val screen = realScreenSize()
            val x = ((match.first.bounds.left + match.first.bounds.right) / 2f *
                screen.widthPixels.toFloat() / bitmap.width.toFloat()).toInt()
            val y = ((match.first.bounds.top + match.first.bounds.bottom) / 2f *
                screen.heightPixels.toFloat() / bitmap.height.toFloat()).toInt()
            val action = JSONObject().put("type", "tap").put("x", x).put("y", y)
                .put("confidence", match.second.coerceIn(0.78, 0.98))
            return decision(false, "Sichtbares Ziel „$target“ erkannt. $visibleSummary", action)
        } catch (error: Exception) {
            return decision(false, "Bildanalyse fehlgeschlagen: ${error.javaClass.simpleName}.", null)
        } finally {
            bitmap.recycle()
        }
    }

    private fun extractExplicitTapTarget(goal: String): String? {
        val patterns = listOf(
            Regex("""(?i)(?:tap|click|press)\s+(?:on\s+)?(?:the\s+)?(.+)$"""),
            Regex("""(?i)(?:drück(?:e)?|klick(?:e)?|tippe(?:\s+auf)?|wähle)\s+(?:den\s+|die\s+|das\s+)?(.+)$""")
        )
        val raw = patterns.firstNotNullOfOrNull { it.find(goal)?.groupValues?.getOrNull(1) } ?: return null
        val cleaned = raw.replace(Regex("""(?i)\b(button|knopf|taste|element|bitte|an)\b"""), " ")
            .replace(Regex("""[.!?,;:]+$"""), "").trim()
        return cleaned.takeIf { it.length in 1..48 && it.any(Char::isLetterOrDigit) }
    }

    private fun textMatchScore(target: String, visible: String): Double {
        val wanted = normalize(target)
        val seen = normalize(visible)
        if (wanted.isBlank() || seen.isBlank()) return 0.0
        if (seen == wanted) return 0.99
        if (seen.contains(wanted)) return 0.95
        if (wanted.contains(seen) && seen.length >= 3) return 0.86
        val words = wanted.split(' ').filter { it.length >= 2 }.toSet()
        if (words.isEmpty()) return 0.0
        return words.intersect(seen.split(' ').toSet()).size.toDouble() / words.size
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
