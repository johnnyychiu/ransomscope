package ransomscope.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.ceil
import kotlin.random.Random

/** CPU-only synthetic regression evaluation. Does not launch malware or change user files. */
object Benchmark {
    private data class Case(val scenario: Scenario, val seed: Int)

    @JvmStatic
    fun main(args: Array<String>) {
        val out = Path.of(args.firstOrNull() ?: "build/evaluation").toAbsolutePath().normalize()
        Files.createDirectories(out.resolve("scenarios"))
        val defaults = DemoScenarios.all()
        defaults.forEach { scenario ->
            Files.writeString(out.resolve("scenarios/${scenario.id}.json"), Codecs.encodeEvents(scenario.events))
        }
        val config = DetectionConfig()
        val developmentSeeds = listOf(1, 2, 3)
        val heldOutSeeds = (101..112).toList()
        val cases = heldOutSeeds.flatMap { seed -> defaults.map { Case(vary(it, seed), seed) } }
        // Warm the same pure function without reusing held-out seeds or adjusting thresholds.
        developmentSeeds.forEach { seed -> defaults.forEach { Engine.analyze(vary(it, seed).events, config) } }
        val modeReports = listOf("rate", "entropy", "canary", "combined").map { mode ->
            val timings = mutableListOf<Double>()
            var eventsAnalyzed = 0
            var benignRuns = 0
            var falseAlertRuns = 0
            var suspiciousRuns = 0
            var missedSuspiciousRuns = 0
            var incompleteRuns = 0
            val runs = cases.map { case ->
                val input = case.scenario
                val start = System.nanoTime()
                val analysis = Engine.analyze(input.events, config.copy(mode = mode))
                val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
                timings += elapsedMs
                eventsAnalyzed += input.events.size
                val alerted = analysis.firstAlertAtMs != null
                if (input.label.startsWith("benign")) {
                    benignRuns++
                    if (alerted) falseAlertRuns++
                } else if (input.label == "suspicious-synthetic") {
                    suspiciousRuns++
                    if (!alerted) missedSuspiciousRuns++
                } else incompleteRuns++
                val firstChange = input.events.filter { it.kind != "OVERFLOW" }.minOfOrNull { it.atMs } ?: 0L
                val beforeAlert = analysis.firstAlertAtMs?.let { at -> input.events.filter { it.atMs <= at } }
                buildJsonObject {
                    put("scenario", input.id)
                    put("seed", case.seed)
                    put("evaluationLabel", input.label)
                    put("alerted", alerted)
                    put("degraded", analysis.degraded)
                    put("analysisMs", elapsedMs)
                    put("timeToFirstAlertMs", analysis.firstAlertAtMs?.let { JsonPrimitive(it - firstChange) } ?: kotlinx.serialization.json.JsonNull)
                    put("changedPathsAtOrBeforeAlert", beforeAlert?.let { JsonPrimitive(Engine.analyze(it, config.copy(mode = mode)).changedFiles) } ?: kotlinx.serialization.json.JsonNull)
                    put("observedSizeAtOrBeforeAlert", beforeAlert?.let { JsonPrimitive(Engine.analyze(it, config.copy(mode = mode)).changedBytes) } ?: kotlinx.serialization.json.JsonNull)
                }
            }
            buildJsonObject {
                put("mode", mode)
                put("runs", cases.size)
                put("benignRuns", benignRuns)
                put("falseAlertRuns", falseAlertRuns)
                put("falseAlertFractionPerBenignRun", falseAlertRuns.toDouble() / benignRuns)
                put("suspiciousSyntheticRuns", suspiciousRuns)
                put("missedSuspiciousSyntheticRuns", missedSuspiciousRuns)
                put("incompleteRunsExcludedFromDetectionRates", incompleteRuns)
                put("analysisMsP50", percentile(timings, 0.5))
                put("analysisMsP95", percentile(timings, 0.95))
                put("measuredEngineEventsPerSecond", eventsAnalyzed * 1_000.0 / timings.sum().coerceAtLeast(0.000001))
                put("details", JsonArray(runs))
            }
        }
        val memory = buildJsonArray {
            for (variant in listOf("present", "erased", "raw-only", "corrupt")) {
                for (seed in heldOutSeeds) {
                    val result = MemoryLab.run(seed, variant)
                    add(buildJsonObject {
                        put("variant", variant)
                        put("seed", seed)
                        put("result", Codecs.json.parseToJsonElement(Codecs.encodeMemory(result)))
                    })
                }
            }
        }
        val report = buildJsonObject {
            put("product", "RansomScope")
            put("generatedAtUtc", Instant.now().toString())
            put("evaluationType", "Synthetic deterministic regression workloads, not real-malware accuracy")
            put("datasetLimits", "Held-out seeds change paths, event sizes and timing jitter on teaching scenario templates; templates themselves are shared with development. This does not measure unseen ransomware-family generalization.")
            put("falseAlertTimeLimit", "Only per-run false-alert fractions are reported. Trace durations are short and are not real wall-clock monitoring hours; no false-alert-per-hour extrapolation is made.")
            put("sizeMetricLimit", "Observed sizes sum maximum file size per distinct path, not exact byte ranges overwritten or permanent loss.")
            put("containmentLimit", "No containment measurements here: this is pure event replay. Desktop lab separately reports cooperative worker pause and snapshot restoration.")
            put("config", Codecs.json.parseToJsonElement(Codecs.encodeConfig(config)))
            put("developmentSeeds", ints(developmentSeeds))
            put("heldOutSeeds", ints(heldOutSeeds))
            put("modes", JsonArray(modeReports))
            put("memoryFixtures", memory)
            putJsonObject("environment") {
                put("javaVersion", System.getProperty("java.version"))
                put("osName", System.getProperty("os.name"))
                put("osArch", System.getProperty("os.arch"))
                put("availableProcessors", Runtime.getRuntime().availableProcessors())
                put("maxJvmHeapBytes", Runtime.getRuntime().maxMemory())
                put("hardwareLimit", "JVM-visible values only; no GPU, full physical RAM, or system CPU utilization measured.")
            }
        }
        write(out.resolve("evaluation.json"), report)
        val contractEvents = defaults.first { it.id == "burst" }.events
        val contract = buildJsonObject {
            put("schemaVersion", 1)
            put("purpose", "JVM-to-browser shared-core parity fixture")
            put("events", Codecs.json.parseToJsonElement(Codecs.encodeEvents(contractEvents)))
            put("config", Codecs.json.parseToJsonElement(Codecs.encodeConfig(config)))
            put("expectedAnalysis", Codecs.json.parseToJsonElement(Codecs.encodeAnalysis(Engine.analyze(contractEvents, config))))
        }
        write(out.resolve("crossplatform-contract.json"), contract)
        println("Wrote ${out.resolve("evaluation.json")}")
        modeReports.forEach { println(it.toString()) }
    }

    private fun vary(scenario: Scenario, seed: Int): Scenario {
        val random = Random(seed * 31 + scenario.id.hashCode())
        val base = 10_000L * seed
        return scenario.copy(events = scenario.events.map { event ->
            event.copy(
                atMs = base + event.atMs + random.nextLong(0, 31),
                path = if (event.path.isBlank()) "" else "held-out-$seed/${event.path}",
                bytes = if (event.bytes == 0L) 0 else event.bytes + random.nextLong(0, 512),
            )
        }.shuffled(random))
    }

    private fun percentile(values: List<Double>, quantile: Double): Double =
        values.sorted()[(ceil(values.size * quantile).toInt() - 1).coerceIn(0, values.lastIndex)]

    private fun ints(values: List<Int>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun write(path: Path, value: JsonElement) = Files.writeString(path, Codecs.json.encodeToString(JsonElement.serializer(), value))
}
