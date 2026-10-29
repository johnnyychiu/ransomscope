package ransomscope.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.ln

/** An observation, not an assertion that the named file was attacked. */
@Serializable
data class FileEvent(
    val atMs: Long,
    val path: String,
    val kind: String = "MODIFY",
    val bytes: Long = 0,
    val entropyBefore: Double? = null,
    val entropyAfter: Double? = null,
    val canary: Boolean = false,
    val source: String = "live",
    val actor: String? = null,
    val attribution: String = "unknown",
)

@Serializable
data class DetectionConfig(
    val mode: String = "combined",
    val windowMs: Long = 2_000,
    val fileThreshold: Int = 4,
    val entropyThreshold: Double = 1.5,
)

@Serializable
data class Decision(
    val atMs: Long,
    val level: String,
    val reasons: List<String>,
    val paths: List<String>,
    val bytes: Long,
    val source: String,
)

@Serializable
data class Analysis(
    val decisions: List<Decision>,
    val changedFiles: Int,
    /** Sum of maximum observed file sizes per path, not measured overwritten byte ranges. */
    val changedBytes: Long,
    val degraded: Boolean,
    val firstAlertAtMs: Long?,
    val summary: String,
)

@Serializable
data class Scenario(
    val id: String,
    val title: String,
    val description: String,
    val events: List<FileEvent>,
    /** Evaluation-only ground truth: never passed to the detector. */
    val label: String,
)

@Serializable
data class MemoryResult(
    val status: String,
    val candidates: Int,
    val verified: Int,
    val scannedBytes: Int,
    val detail: String,
    val keyPreview: String,
)

object Codecs {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    fun encodeEvents(events: List<FileEvent>): String = json.encodeToString(events)
    fun decodeEvents(text: String): List<FileEvent> {
        require(text.length <= 8_000_000) { "Event import exceeds the 8 MB text limit." }
        return json.decodeFromString<List<FileEvent>>(text).also(Engine::validateEvents)
    }
    fun encodeAnalysis(value: Analysis): String = json.encodeToString(value)
    fun encodeConfig(value: DetectionConfig): String = json.encodeToString(value)
    fun decodeConfig(text: String): DetectionConfig = json.decodeFromString(text)
    fun encodeMemory(value: MemoryResult): String = json.encodeToString(value)
}

object ContentFeatures {
    /** Shannon entropy in bits per byte; sample selection and before/after timing matter. */
    fun entropy(bytes: ByteArray): Double {
        if (bytes.isEmpty()) return 0.0
        val counts = IntArray(256)
        bytes.forEach { counts[it.toInt() and 0xff]++ }
        return counts.filter { it > 0 }.sumOf {
            val p = it.toDouble() / bytes.size
            -p * ln(p) / ln(2.0)
        }
    }
}
