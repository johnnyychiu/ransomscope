package ransomscope.desktop

import ransomscope.core.Codecs
import ransomscope.core.DetectionConfig
import ransomscope.core.Engine
import ransomscope.core.FileEvent
import java.io.BufferedWriter
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.ln

/** Directory notifications are observations, never reliable process attribution. */
class FolderMonitor(
    private val root: Path,
    private val source: String,
    private val onEvent: (FileEvent) -> Unit,
    private val onHealth: (String) -> Unit,
    private val canaryPaths: Set<String> = emptySet()
) : AutoCloseable {
    private val service: WatchService = root.fileSystem.newWatchService()
    private val keys = mutableMapOf<WatchKey, Path>()
    private val entropy = mutableMapOf<Path, Double>()
    private val running = AtomicBoolean(false)
    private var task: Thread? = null
    @Volatile var coverageIncomplete = false
        private set

    private fun gap(reason: String) {
        coverageIncomplete = true
        onHealth("Degraded: $reason")
        onEvent(FileEvent(System.currentTimeMillis(), "[$reason]", "OVERFLOW", source = source))
    }

    fun start() {
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Choose a local directory." }
        requireSafePath(root)
        registerTree(root)
        running.set(true)
        task = thread(isDaemon = true, name = "ransomscope-directory-monitor") {
            try {
                while (running.get()) {
                    val key = service.poll(150, TimeUnit.MILLISECONDS) ?: continue
                    val dir = keys[key] ?: continue
                    for (notification in key.pollEvents()) {
                        if (notification.kind() == OVERFLOW) {
                            gap("notification gap; rescan required")
                            continue
                        }
                        @Suppress("UNCHECKED_CAST")
                        val child = dir.resolve((notification as WatchEvent<Path>).context()).normalize()
                        if (!child.startsWith(root) || !safeEntry(child)) {
                            gap("unsafe or special entry excluded from coverage")
                            continue
                        }
                        if (notification.kind() == ENTRY_CREATE && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                            // A populated tree can arrive in one move. Register its descendants, then
                            // disclose that earlier activity inside the arriving tree was not observed.
                            registerTree(child)
                            gap("new directory tree discovered; earlier changes may be missing")
                            continue
                        }
                        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue
                        val before = entropy[child]
                        val after = sampleEntropy(child)
                        if (after != null) {
                            if (entropy.containsKey(child) || entropy.size < 2000) entropy[child] = after
                            else gap("content-baseline limit; new file lacks a baseline")
                        } else if (notification.kind() != ENTRY_DELETE) gap("content sample unavailable after notification")
                        if (notification.kind() == ENTRY_DELETE) entropy.remove(child)
                        val relative = root.relativize(child).toString().replace('\\', '/')
                        val kind = when (notification.kind()) {
                            ENTRY_CREATE -> "CREATE"
                            ENTRY_DELETE -> "DELETE"
                            else -> "MODIFY"
                        }
                        val size = runCatching { Files.size(child) }.getOrDefault(0L)
                        onEvent(FileEvent(System.currentTimeMillis(), relative, kind, size, before, after,
                            relative in canaryPaths, source, null, "unknown"))
                    }
                    if (!key.reset()) {
                        keys.remove(key)
                        gap("a watched directory became unavailable")
                    }
                }
            } catch (_: java.nio.file.ClosedWatchServiceException) {
                // Normal lifecycle shutdown.
            } catch (failure: Exception) {
                gap("monitor stopped: ${failure.message}")
            }
        }
    }

    private fun registerTree(tree: Path) {
        Files.walkFileTree(tree, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (keys.size >= 256 || !safeEntry(dir)) {
                    gap("directory coverage limit or special entry; subtree excluded")
                    return FileVisitResult.SKIP_SUBTREE
                }
                register(dir)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (entropy.size >= 2000) {
                    gap("content-baseline limit; rescan with a smaller folder")
                    return FileVisitResult.TERMINATE
                }
                if (attrs.isRegularFile && safeEntry(file)) {
                    val sampled = sampleEntropy(file)
                    if (sampled != null) entropy[file] = sampled else gap("an initial content sample was unreadable")
                } else gap("special entry excluded from coverage")
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                gap("some entries cannot be read")
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun register(dir: Path) {
        keys[dir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)] = dir
    }

    override fun close() {
        running.set(false)
        service.close()
        task?.join(1000)
    }
}

fun requireSafePath(path: Path) {
    val absolute = path.toAbsolutePath().normalize()
    var cursor: Path? = absolute
    while (cursor != null) {
        require(!Files.isSymbolicLink(cursor)) { "Symbolic links are not supported: $cursor" }
        if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            val attributes = Files.readAttributes(cursor, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(!attributes.isOther) { "Reparse or special entries are not supported: $cursor" }
        }
        cursor = cursor.parent
    }
}

private fun safeEntry(path: Path): Boolean = runCatching { requireSafePath(path); true }.getOrDefault(false)

fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

private fun sampleEntropy(path: Path): Double? = runCatching {
    requireSafePath(path)
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@runCatching null
    val sample = Files.newInputStream(path).use { it.readNBytes(8192) }
    if (sample.isEmpty()) return@runCatching 0.0
    val frequencies = IntArray(256)
    sample.forEach { frequencies[it.toInt() and 255]++ }
    frequencies.filter { it > 0 }.sumOf {
        val probability = it.toDouble() / sample.size
        -probability * ln(probability) / ln(2.0)
    }
}.getOrNull()

typealias SnapshotEntry = ransomscope.core.SnapshotEntry
data class RestoreResult(val directory: Path, val verified: Int, val total: Int)

/** Owns an isolated, bounded lab. It never adopts a directory selected for passive monitoring. */
class DemoLab private constructor(val home: Path, val token: String, val entries: List<SnapshotEntry>) {
    val data: Path = home.resolve("documents")
    val snapshot: Path = home.resolve("snapshot")

    fun validate() {
        requireSafePath(home)
        require(home.fileName.toString().startsWith("ransomscope-lab-")) { "Not a RansomScope lab" }
        require(Files.readString(home.resolve(".ransomscope-lab")) == token) { "Lab identity changed" }
        require(entries.size == 24)
        snapshotManifest().validateSchema()
        for (entry in entries) {
            require(entry.name.matches(Regex("(canary|document)-[0-9]{2}\\.txt"))) { "Invalid lab manifest" }
            val original = snapshot.resolve(entry.name)
            requireSafePath(original)
            require(original.startsWith(snapshot) && Files.size(original) == entry.size && sha256(original) == entry.hash) { "Snapshot failed integrity validation" }
        }
    }

    /** Informational shared manifest; schema validation is not a signature or browser trust grant. */
    fun snapshotManifest(): ransomscope.core.SnapshotManifest {
        val path = home.resolve("manifest.json")
        requireSafePath(path)
        require(Files.size(path) < 16_384) { "Snapshot manifest exceeds the lab limit" }
        return Codecs.json.decodeFromString(ransomscope.core.SnapshotManifest.serializer(), Files.readString(path)).also {
            it.validateSchema()
            require(it.entries == entries) { "Snapshot entries changed" }
        }
    }

    /** These statuses come from in-process file/hash checks, never imported status strings. */
    fun readiness(restoredDirectory: Path? = null): List<ransomscope.core.FileReadiness> = entries.map { entry ->
        val originalMatches = runCatching {
            val original = snapshot.resolve(entry.name)
            requireSafePath(original)
            Files.size(original) == entry.size && sha256(original) == entry.hash
        }.getOrDefault(false)
        val restoredMatches = restoredDirectory?.let { directory -> runCatching {
            val path = directory.resolve(entry.name)
            requireSafePath(path)
            Files.size(path) == entry.size && sha256(path) == entry.hash
        }.getOrDefault(false) } ?: false
        ransomscope.core.RecoveryReadiness.assessLocal(entry.name, ransomscope.core.LocalRecoveryChecks(
            snapshotHashMatches = originalMatches,
            restoredHashMatches = restoredMatches,
            metadataComplete = originalMatches,
        ))
    }

    fun restore(): RestoreResult {
        validate()
        val output = Files.createDirectory(home.resolve("restored-${UUID.randomUUID().toString().take(8)}"))
        var verified = 0
        for (entry in entries) {
            val target = output.resolve(entry.name)
            requireSafePath(target.parent)
            Files.copy(snapshot.resolve(entry.name), target) // No REPLACE_EXISTING: preserve existing evidence.
            if (sha256(target) == entry.hash) verified++
        }
        require(verified == entries.size) { "Restore integrity mismatch" }
        return RestoreResult(output, verified, entries.size)
    }

    companion object {
        fun create(): DemoLab {
            val home = Files.createTempDirectory("ransomscope-lab-").toAbsolutePath().normalize()
            requireSafePath(home)
            val token = UUID.randomUUID().toString()
            Files.writeString(home.resolve(".ransomscope-lab"), token, StandardOpenOption.CREATE_NEW)
            val data = Files.createDirectory(home.resolve("documents"))
            val snapshot = Files.createDirectory(home.resolve("snapshot"))
            val entries = (0 until 24).map { index ->
                val name = if (index == 0) "canary-00.txt" else "document-%02d.txt".format(index)
                val content = "RansomScope disposable document $index\n" + "Invoice item and planning notes. No real personal data.\n".repeat(64)
                val path = data.resolve(name)
                Files.writeString(path, content, StandardOpenOption.CREATE_NEW)
                Files.copy(path, snapshot.resolve(name))
                SnapshotEntry(name, Files.size(path), sha256(path))
            }
            Files.writeString(home.resolve("manifest.tsv"), entries.joinToString("\n") { "${it.name}\t${it.size}\t${it.hash}" }, StandardOpenOption.CREATE_NEW)
            val manifest = ransomscope.core.SnapshotManifest(System.currentTimeMillis(), entries)
            manifest.validateSchema()
            Files.writeString(home.resolve("manifest.json"),
                Codecs.json.encodeToString(ransomscope.core.SnapshotManifest.serializer(), manifest), StandardOpenOption.CREATE_NEW)
            return DemoLab(home, token, entries).also { it.validate() }
        }

        fun reopenForWorker(home: Path, token: String): DemoLab {
            requireSafePath(home)
            require(home.fileName.toString().startsWith("ransomscope-lab-"))
            require(Files.readString(home.resolve(".ransomscope-lab")) == token)
            val manifest = home.resolve("manifest.tsv")
            require(Files.size(manifest) < 16_384)
            val entries = Files.readAllLines(manifest).map { line ->
                val parts = line.split('\t')
                require(parts.size == 3)
                SnapshotEntry(parts[0], parts[1].toLong(), parts[2])
            }
            return DemoLab(home, token, entries).also { it.validate() }
        }
    }
}

class DemoWorker(private val lab: DemoLab, private val onStatus: (String) -> Unit) : AutoCloseable {
    private var process: Process? = null
    private var input: BufferedWriter? = null
    val writes = CopyOnWriteArrayList<Pair<Long, String>>()
    @Volatile var pausedAtMs: Long? = null
    @Volatile var status = "Ready"
    val alive: Boolean get() = process?.isAlive == true

    fun start() {
        lab.validate()
        process = ProcessBuilder(workerLaunchCommand(lab.home, lab.token))
            .redirectErrorStream(true).start()
        input = process!!.outputStream.bufferedWriter()
        status = "Running disposable-file worker"
        onStatus(status)
        thread(isDaemon = true, name = "ransomscope-lab-worker-output") {
            process!!.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("WROTE|")) {
                        val fields = line.split('|')
                        writes.add(fields[1].toLong() to fields[2])
                    } else if (line.startsWith("PAUSED|")) {
                        pausedAtMs = line.substringAfter('|').toLong()
                        status = "Lab worker cooperatively paused"
                        onStatus(status)
                    } else if (line == "COMPLETE") {
                        status = "Lab worker completed"
                        onStatus(status)
                    } else if (line.startsWith("ERROR|")) {
                        status = line.substringAfter('|')
                        onStatus("Worker error: $status")
                    }
                }
            }
        }
    }

    fun pause() {
        if (alive && pausedAtMs == null) runCatching { input?.apply { write("PAUSE\n"); flush() } }
    }

    override fun close() {
        runCatching { input?.apply { write("STOP\n"); flush() } }
        process?.let { if (!it.waitFor(1200, TimeUnit.MILLISECONDS)) it.destroy() }
        runCatching { input?.close() }
    }
}

/** A jlink runtime may omit bin/java. Re-enter this app's own launcher for the owned worker. */
internal fun workerLaunchCommand(
    home: Path,
    token: String,
    packagedAppPath: String? = System.getProperty("jpackage.app-path"),
    currentCommand: String? = runCatching { ProcessHandle.current().info().command().orElse(null) }.getOrNull(),
    javaHome: String = System.getProperty("java.home"),
    classPath: String = System.getProperty("java.class.path"),
    windows: Boolean = System.getProperty("os.name").startsWith("Windows"),
): List<String> {
    fun executablePath(value: String?): Path? = value?.takeIf { it.isNotBlank() }?.let {
        runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull()
    }?.takeIf { Files.isRegularFile(it) }

    val packaged = executablePath(packagedAppPath)
    val ownProcess = executablePath(currentCommand)?.takeIf {
        it.fileName.toString().equals(if (windows) "RansomScope.exe" else "RansomScope", ignoreCase = windows)
    }
    val launcher = packaged ?: ownProcess
    if (launcher != null) return listOf(launcher.toString(), "--worker", home.toString(), token)

    val java = Path.of(javaHome, "bin", if (windows) "java.exe" else "java")
    require(Files.isRegularFile(java)) {
        "Cannot start the lab worker: this runtime has no Java launcher and the packaged RansomScope launcher could not be identified."
    }
    return listOf(java.toString(), "-cp", classPath, "ransomscope.desktop.MainKt", "--worker", home.toString(), token)
}

fun runWorker(home: Path, token: String) {
    val lab = DemoLab.reopenForWorker(home, token)
    val paused = AtomicBoolean(false)
    val stopped = AtomicBoolean(false)
    thread(isDaemon = true, name = "lab-command-channel") {
        System.`in`.bufferedReader().useLines { lines ->
            lines.forEach { command ->
                if (command == "PAUSE") paused.set(true)
                if (command == "STOP") stopped.set(true)
            }
        }
        stopped.set(true)
    }
    val random = java.util.Random(92741L)
    for (entry in lab.entries) {
        if (stopped.get()) break
        if (paused.get()) {
            println("PAUSED|${System.currentTimeMillis()}")
            System.out.flush()
            while (paused.get() && !stopped.get()) Thread.sleep(25)
        }
        if (stopped.get()) break
        val path = lab.data.resolve(entry.name).normalize()
        require(path.startsWith(lab.data))
        requireSafePath(path)
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= 8192)
        val bytes = ByteArray(entry.size.toInt()).also { random.nextBytes(it) }
        Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)
        println("WROTE|${System.currentTimeMillis()}|${entry.name}")
        System.out.flush()
        Thread.sleep(180)
    }
    println("COMPLETE")
    System.out.flush()
}

data class DesktopCheck(val name: String, val passed: Boolean, val detail: String)

fun desktopSelfTest(output: Path?): Boolean {
    val checks = mutableListOf<DesktopCheck>()
    fun check(name: String, task: () -> String) {
        runCatching(task).onSuccess { checks += DesktopCheck(name, true, it) }
            .onFailure { checks += DesktopCheck(name, false, it.toString()) }
    }
    var lab: DemoLab? = null
    val observations = CopyOnWriteArrayList<FileEvent>()
    var monitor: FolderMonitor? = null
    var worker: DemoWorker? = null
    try {
        check("pre-change snapshot") {
            lab = DemoLab.create()
            "24 disposable files; all snapshot SHA-256 values validated"
        }
        check("live directory observations and cooperative pause") {
            val selectedLab = requireNotNull(lab)
            monitor = FolderMonitor(selectedLab.data, "synthetic", { event ->
                observations += event
                val analysis = Engine.analyze(observations.toList())
                if (analysis.firstAlertAtMs != null) worker?.pause()
            }, {}, setOf("canary-00.txt"))
            monitor!!.start()
            worker = DemoWorker(selectedLab) {}
            worker!!.start()
            val deadline = System.currentTimeMillis() + 7000
            while (System.currentTimeMillis() < deadline && worker!!.pausedAtMs == null && worker!!.alive) Thread.sleep(50)
            require(observations.any { it.kind != "OVERFLOW" }) { "No real WatchService events observed" }
            require(observations.all { it.actor == null && it.attribution == "unknown" }) { "Watcher invented process attribution" }
            require(worker!!.pausedAtMs != null) { "Worker did not acknowledge cooperative pause" }
            "${observations.size} real notifications; ${worker!!.writes.size} worker writes before acknowledged pause"
        }
        worker?.close()
        monitor?.close()
        monitor = null
        check("restore into a fresh directory") {
            val restored = requireNotNull(lab).restore()
            require(restored.directory != lab!!.data)
            "${restored.verified}/${restored.total} files SHA-256 verified"
        }
        check("reject a tampered snapshot") {
            val selectedLab = requireNotNull(lab)
            Files.writeString(selectedLab.snapshot.resolve(selectedLab.entries.first().name), "tampered", StandardOpenOption.TRUNCATE_EXISTING)
            require(runCatching { selectedLab.restore() }.isFailure)
            "Tampering rejected before restore"
        }
        check("reject a forged lab identity") {
            require(runCatching { DemoLab.reopenForWorker(requireNotNull(lab).home, "wrong-token") }.isFailure)
            "Worker refuses mismatched token"
        }
    } finally {
        worker?.close()
        monitor?.close()
    }
    val report = buildString {
        appendLine("RansomScope desktop integration checks")
        appendLine("Recorded: ${Instant.now()}")
        appendLine("Platform: ${System.getProperty("os.name")} / Java ${System.getProperty("java.version")}")
        appendLine("Synthetic disposable-file experiment. Not real malware validation.")
        checks.forEach { appendLine("${if (it.passed) "PASS" else "FAIL"} | ${it.name} | ${it.detail}") }
        val firstWrite = worker?.writes?.firstOrNull()?.first
        val firstAlert = Engine.analyze(observations.toList()).firstAlertAtMs
        val pauseAck = worker?.pausedAtMs
        appendLine("Single-run timings (not distribution statistics):")
        appendLine("First worker write epoch ms: $firstWrite")
        appendLine("First observed alert epoch ms: $firstAlert")
        appendLine("Cooperative pause acknowledgement epoch ms: $pauseAck")
        if (firstWrite != null && firstAlert != null) appendLine("First write to alert ms: ${firstAlert - firstWrite}")
        if (firstWrite != null && pauseAck != null) appendLine("First write to pause acknowledgement ms: ${pauseAck - firstWrite}")
        if (firstAlert != null) appendLine("Evaluator worker writes timestamped before/equal alert: ${worker?.writes?.count { it.first <= firstAlert }}")
        if (pauseAck != null) appendLine("Evaluator worker writes timestamped before/equal pause acknowledgement: ${worker?.writes?.count { it.first <= pauseAck }}")
        appendLine("Notification bytes are observed whole-file sizes, not exact overwritten-byte counts.")
        appendLine("Lab retained at ${lab?.home}; no user documents were modified.")
    }
    println(report)
    if (output != null) {
        Files.createDirectories(output)
        Files.writeString(output.resolve("desktop-self-test.txt"), report)
        Files.writeString(output.resolve("desktop-observations.json"), Codecs.encodeEvents(observations.toList()))
    }
    return checks.size >= 5 && checks.all { it.passed }
}
