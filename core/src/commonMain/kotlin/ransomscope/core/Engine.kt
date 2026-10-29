package ransomscope.core

/** Transparent rule baselines. This engine does not identify processes or stop writes. */
object Engine {
    private val changeKinds = setOf("MODIFY", "WRITE", "CREATE", "ADD", "DELETE", "REMOVE", "RENAME")
    private val lossKinds = setOf("OVERFLOW", "GAP", "ERROR")
    private val modes = setOf("rate", "entropy", "canary", "combined")

    fun validateEvents(events: List<FileEvent>) {
        require(events.size <= 100_000) { "At most 100,000 events can be analyzed." }
        events.forEach { event ->
            require(event.atMs in 0..9_007_199_254_740_991L) { "Event time must be a non-negative, JavaScript-safe integer." }
            require(event.path.length <= 4_096) { "Event path exceeds 4,096 characters." }
            require(event.bytes in 0..1_000_000_000_000L) { "Observed file size is outside the supported range." }
            require(event.source in setOf("live", "replay", "synthetic")) { "Unknown observation source." }
            listOfNotNull(event.entropyBefore, event.entropyAfter).forEach {
                require(it.isFinite() && it in 0.0..8.0) { "Entropy must be between 0 and 8 bits per byte." }
            }
        }
    }

    fun analyze(events: List<FileEvent>, config: DetectionConfig = DetectionConfig()): Analysis {
        validateEvents(events)
        require(config.mode in modes) { "Unknown detector mode: ${config.mode}" }
        require(config.windowMs in 100..60_000) { "Window must be between 100 and 60,000 ms." }
        require(config.fileThreshold in 2..1_000) { "File threshold must be between 2 and 1,000." }
        require(config.entropyThreshold.isFinite() && config.entropyThreshold in 0.0..8.0) {
            "Entropy-change threshold must be between 0 and 8."
        }

        val ordered = events.distinct().sortedWith(compareBy({ it.atMs }, { it.path }, { it.kind }))
        val changes = ordered.filter { it.kind.uppercase() in changeKinds && it.path.isNotBlank() }
        val decisions = mutableListOf<Decision>()
        val window = ArrayDeque<FileEvent>()
        val pathCounts = mutableMapOf<String, Int>()
        val entropyCounts = mutableMapOf<String, Int>()
        val canaryCounts = mutableMapOf<String, Int>()
        var degraded = false
        var lastAlertAt = Long.MIN_VALUE / 2
        var lastAlertPathCount = 0
        var alertCount = 0
        var summarized = false

        fun adjust(counts: MutableMap<String, Int>, path: String, delta: Int) {
            val next = (counts[path] ?: 0) + delta
            if (next <= 0) counts.remove(path) else counts[path] = next
        }
        fun entropyChanged(value: FileEvent): Boolean = value.kind.uppercase() in setOf("MODIFY", "WRITE") &&
            value.entropyBefore != null && value.entropyAfter != null &&
            value.entropyAfter - value.entropyBefore >= config.entropyThreshold
        fun account(value: FileEvent, delta: Int) {
            adjust(pathCounts, value.path, delta)
            if (entropyChanged(value)) adjust(entropyCounts, value.path, delta)
            if (value.canary) adjust(canaryCounts, value.path, delta)
        }

        for (event in ordered) {
            if (event.kind.uppercase() in lossKinds) {
                degraded = true
                window.clear() // Do not connect observations across missing evidence.
                pathCounts.clear()
                entropyCounts.clear()
                canaryCounts.clear()
                lastAlertAt = Long.MIN_VALUE / 2
                lastAlertPathCount = 0
                decisions += Decision(
                    event.atMs, "degraded",
                    listOf("Telemetry is incomplete (${event.kind.lowercase()}); rescan is needed. Absence of later alerts does not establish safety."),
                    emptyList(), 0, event.source,
                )
                continue
            }
            if (event.kind.uppercase() !in changeKinds || event.path.isBlank()) continue
            while (window.isNotEmpty() && window.first().atMs < event.atMs - config.windowMs) {
                account(window.removeFirst(), -1)
            }
            window.addLast(event)
            account(event, 1)
            val pathCount = pathCounts.size
            val rate = pathCount >= config.fileThreshold
            val entropy = entropyCounts.isNotEmpty()
            val canary = canaryCounts.isNotEmpty()
            val alert = when (config.mode) {
                "rate" -> rate
                "entropy" -> entropy
                "canary" -> canary
                else -> (rate && entropyCounts.size >= 2) || (canary && pathCount >= 2)
            }
            if (!alert) continue
            // Bound reporting cost without throwing away input evidence or the first alert.
            if (event.atMs - lastAlertAt < 250 && pathCount < lastAlertPathCount + config.fileThreshold) continue
            if (alertCount >= 128) {
                summarized = true
                continue
            }
            val current = window.toList()
            val paths = pathCounts.keys.sorted().take(256)
            lastAlertAt = event.atMs
            lastAlertPathCount = pathCount
            alertCount++
            val reasons = buildList {
                if (rate) add("$pathCount distinct changed paths within ${config.windowMs} ms (threshold ${config.fileThreshold}).")
                if (entropy) add("${entropyCounts.size} modified paths increased sampled entropy by at least ${config.entropyThreshold} bits/byte; legitimate encryption can also do this.")
                if (canary) add("${canaryCounts.size} designated canary paths changed; this is a warning signal, not proof of malware.")
                if (pathCount > paths.size) add("This decision lists the first ${paths.size} of $pathCount paths; aggregate counts include all observed paths.")
                if (!current.all { it.attribution != "unknown" && it.actor != null }) {
                    add("Writer identity is unknown for some observations. No process containment is authorized by this result.")
                }
                if (degraded) add("Earlier telemetry loss reduces confidence in completeness.")
            }
            decisions += Decision(event.atMs, "alert", reasons, paths, observedBytes(current),
                if (current.map { it.source }.distinct().size == 1) current.first().source else "mixed")
        }

        val first = decisions.firstOrNull { it.level == "alert" }?.atMs
        val summary = when {
            first != null && degraded -> "Suspicious changes observed; telemetry is incomplete. Review evidence before taking action."
            first != null -> "Suspicious changes observed. This is a rule-based alert, not a ransomware verdict."
            degraded -> "Incomplete observations. Rescan before interpreting the result; this is not a safe verdict."
            else -> "No rule threshold crossed in these observations. Slow or unsupported behavior may be missed."
        }
        return Analysis(decisions, changes.map { it.path }.distinct().size, observedBytes(changes), degraded, first,
            summary + if (summarized) " Alert updates are summarized after 128 records; aggregate file counts still include all observations." else "")
    }

    private fun observedBytes(events: List<FileEvent>): Long = events.groupBy { it.path }.values.sumOf { group ->
        group.maxOfOrNull { it.bytes } ?: 0
    }
}
