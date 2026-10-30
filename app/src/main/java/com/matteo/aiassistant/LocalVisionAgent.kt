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

/**
 * Visual-only controller: all observations come from the captured bitmap.
 * No language model, prompt completion, network inference, or accessibility text is used.
 */
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
            val objects = Tasks.await(detector.process(image), 6, TimeUnit.SECONDS)
            val text = Tasks.await(recognizer.process(image), 6, TimeUnit.SECONDS)
            val visibleLines = text.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val bounds = line.boundingBox
                    if (bounds == null || line.text.isBlank()) null
                    else VisibleLine(line.text.trim(), Rect(bounds))
                }
            }
            val foundLabels = objects.flatMap { item ->
                item.labels.map { label -> label.text.lowercase(Locale.ROOT) }
            }.distinct()
            val visibleSummary = buildString {
                append("Bildanalyse: ")
                append(objects.size)
                append(" Objekte, ")
                append(visibleLines.size)
                append(" Texterkennungszeilen.")
                if (foundLabels.isNotEmpty()) append(" Objektklassen: ").append(foundLabels.joinToString(", "))
            }

            val allVisibleText = visibleLines.joinToString(" ") { it.text }
            if (containsSensitiveText(allVisibleText)) {
                return decision(false, "Sicherheitsstopp: sensibler Inhalt im sichtbaren Bildschirm erkannt. $visibleSummary", null)
            }

            val target = extractExplicitTapTarget(goal)
                ?: return decision(false, "Visuell analysiert. Für diese erste Version muss ein konkretes sichtbares Textziel genannt werden. $visibleSummary", null)

            val match = visibleLines
                .map { line -> line to textMatchScore(target, line.text) }
                .filter { it.second >= 0.72 }
                .maxByOrNull { it.second }
                ?: return decision(false, "Das Ziel „$target“ wurde im aktuellen Bild nicht eindeutig gelesen. Keine Aktion ausgeführt. $visibleSummary", null)

            val screen = realScreenSize()
            val xScale = screen.widthPixels.toFloat() / bitmap.width.toFloat()
            val yScale = screen.heightPixels.toFloat() / bitmap.height.toFloat()
            val bounds = match.first.bounds
            val x = ((bounds.left + bounds.right) / 2f * xScale).toInt()
            val y = ((bounds.top + bounds.bottom) / 2f * yScale).toInt()

            val action = JSONObject()
                .put("type", "tap")
                .put("x", x)
                .put("y", y)
                .put("confidence", match.second.coerceIn(0.78, 0.98))
            return decision(false, "Sichtbares Ziel „$target“ erkannt: ${match.first.text}. $visibleSummary", action)
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
        val cleaned = raw
            .replace(Regex("""(?i)\b(button|knopf|taste|element|bitte|an)\b"""), " ")
            .replace(Regex("""[.!?,;:]+$"""), "")
            .trim()
        return cleaned.takeIf { it.length in 1..48 && it.any(Char::isLetterOrDigit) }
    }

    private fun textMatchScore(target: String, visible: String): Double {
        val wanted = normalize(target)
        val seen = normalize(visible)
        if (wanted.isBlank() || seen.isBlank()) return 0.0
        if (seen == wanted) return 0.98
        if (seen.contains(wanted)) return 0.94
        if (wanted.contains(seen) && seen.length >= 3) return 0.84
        val targetWords = wanted.split(' ').filter { it.length >= 2 }.toSet()
        if (targetWords.isEmpty()) return 0.0
        val seenWords = seen.split(' ').toSet()
        return targetWords.intersect(seenWords).size.toDouble() / targetWords.size.toDouble()
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
        (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun decision(done: Boolean, summary: String, action: JSONObject?): String =
        JSONObject().put("done", done).put("summary", summary).put("action", action).toString()

    override fun close() {
        detector.close()
        recognizer.close()
    }

    private data class VisibleLine(val text: String, val bounds: Rect)
}
