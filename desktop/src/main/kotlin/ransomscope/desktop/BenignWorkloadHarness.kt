package ransomscope.desktop

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ransomscope.core.Codecs
import ransomscope.core.DetectionConfig
import ransomscope.core.Engine
import ransomscope.core.FileEvent

/** Real benign I/O on application-created temporary files; no user-selected paths are modified. */
object BenignWorkloadHarness {
    @JvmStatic
    fun main(args: Array<String>) {
        val output = Path.of(args.firstOrNull() ?: "build/benign-workloads").toAbsolutePath().normalize()
        Files.createDirectories(output)
        val reports = listOf("compression", "bulk-rename", "java-build", "editing").map { kind -> runCase(kind, output) }
        val report = buildJsonObject {
            put("generatedAtUtc", Instant.now().toString())
            put("type", "Actual benign filesystem workloads on application-created disposable documents")
            put("limits", "Four short, single-run controls. Reported exposure is not production prevalence or a population false-alert rate. Resource values describe this harness JVM plus its monitor and workload, not a separate idle GUI.")
            put("resources", "JVM heap is not resident process RAM. CPU time includes the benign operation and observer. No GPU use or system-wide CPU usage is measured.")
            put("disk", "End-of-run logical file sizes, not physical allocated blocks or storage I/O counters. No files are deleted by this harness.")
            put("runs", JsonArray(reports))
        }
        Files.writeString(output.resolve("benign-workloads.json"), Codecs.json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), report))
        println("Wrote ${output.resolve("benign-workloads.json")}; four bounded benign controls completed.")
    }

    private fun runCase(kind: String, output: Path): kotlinx.serialization.json.JsonObject {
        val root = Files.createTempDirectory("ransomscope-benign-").toAbsolutePath().normalize()
        requireSafePath(root)
        val inputs = Files.createDirectory(root.resolve("inputs"))
        val destination = Files.createDirectory(root.resolve("outputs"))
        val source = Files.createDirectory(root.resolve("src"))
        val files = (0 until 24).map { index ->
            inputs.resolve("note-%02d.txt".format(index)).also {
                Files.writeString(it, "Disposable benign workload file $index.\n" + "Planning notes and invoice line items.\n".repeat(90), StandardOpenOption.CREATE_NEW)
            }
        }
        val javaSources = (0 until 12).map { index ->
            source.resolve("BenignCase$index.java").also {
                Files.writeString(it, "final class BenignCase$index { public int value() { return $index; } }", StandardOpenOption.CREATE_NEW)
            }
        }
        val observations = CopyOnWriteArrayList<FileEvent>()
        val health = CopyOnWriteArrayList<String>()
        val setupStart = System.nanoTime()
        val monitor = FolderMonitor(root, "live", { observations += it }, { health += it })
        monitor.start()
        val setupMs = (System.nanoTime() - setupStart) / 1_000_000.0
        val memory = ManagementFactory.getMemoryMXBean()
        val peakHeap = AtomicLong(memory.heapMemoryUsage.used)
        val sampling = AtomicBoolean(true)
        val sampler = Thread {
            while (sampling.get()) {
                val used = memory.heapMemoryUsage.used
                peakHeap.updateAndGet { previous -> maxOf(previous, used) }
                Thread.sleep(25)
            }
        }.apply { isDaemon = true; name = "ransomscope-benign-resource-sampler"; start() }
        val bean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        val cpuBefore = bean?.processCpuTime?.takeIf { it >= 0 }
        val heapBefore = memory.heapMemoryUsage.used
        val start = System.nanoTime()
        var failure: String? = null
        try {
            when (kind) {
                "compression" -> ZipOutputStream(Files.newOutputStream(destination.resolve("notes.zip"), StandardOpenOption.CREATE_NEW)).use { zip ->
                    files.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.fileName.toString()))
                        Files.copy(file, zip)
                        zip.closeEntry()
                    }
                }
                "bulk-rename" -> files.forEach { file ->
                    Files.move(file, file.resolveSibling("renamed-${file.fileName}"), StandardCopyOption.ATOMIC_MOVE)
                }
                "java-build" -> {
                    val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "This control requires a JDK with the Java compiler." }
                    val compilerArgs = listOf("-d", destination.toString()) + javaSources.map { it.toString() }
                    require(compiler.run(null, null, null, *compilerArgs.toTypedArray()) == 0) { "Benign Java compilation failed." }
                }
                "editing" -> repeat(20) { index ->
                    Files.writeString(files[index % 4], "Additional ordinary edit $index.\n", StandardOpenOption.APPEND)
                    Thread.sleep(100)
                }
            }
            // Five seconds of actual monitor exposure per control, including its I/O operation.
            val elapsed = (System.nanoTime() - start) / 1_000_000
            if (elapsed < 5_000) Thread.sleep(5_000 - elapsed)
        } catch (error: Exception) {
            failure = error.toString()
        } finally {
            monitor.close()
            sampling.set(false)
            sampler.join(1_000)
        }
        val durationNanos = System.nanoTime() - start
        val cpuAfter = bean?.processCpuTime?.takeIf { it >= 0 }
        val cpuNanos = if (cpuBefore != null && cpuAfter != null) cpuAfter - cpuBefore else null
        val eventList = observations.toList()
        Files.writeString(output.resolve("$kind-events.json"), Codecs.encodeEvents(eventList))
        val bytes = Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
        val modes = listOf("rate", "entropy", "canary", "combined").map { mode ->
            val analysis = Engine.analyze(eventList, DetectionConfig(mode = mode))
            buildJsonObject {
                put("mode", mode)
                put("alertRecords", analysis.decisions.count { it.level == "alert" })
                put("falseAlertInThisKnownBenignRun", analysis.firstAlertAtMs != null)
                put("degraded", analysis.degraded)
                put("observedPaths", analysis.changedFiles)
            }
        }
        return buildJsonObject {
            put("workload", kind)
            put("successfulOperation", failure == null)
            put("error", failure?.let(::JsonPrimitive) ?: JsonNull)
            put("monitorSetupMs", setupMs)
            put("actualObservedDurationSeconds", durationNanos / 1_000_000_000.0)
            put("eventCount", eventList.size)
            put("degraded", monitor.coverageIncomplete)
            put("healthMessages", JsonArray(health.distinct().map(::JsonPrimitive)))
            put("processCpuTimeMs", cpuNanos?.let { JsonPrimitive(it / 1_000_000.0) } ?: JsonNull)
            put("processCpuPercentOneCoreEquivalent", cpuNanos?.let { JsonPrimitive(100.0 * it / durationNanos) } ?: JsonNull)
            put("jvmHeapBeforeBytes", heapBefore)
            put("jvmHeapSampledPeakBytes", peakHeap.get())
            put("logicalWorkspaceBytesAfter", bytes)
            put("inputFileCount", files.size)
            put("modes", JsonArray(modes))
            put("workspaceRetention", "Retained application-created temp workspace; no user documents or original project files modified")
        }
    }
}
