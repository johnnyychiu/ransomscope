package ransomscope.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ransomscope.core.*
import java.awt.Desktop
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private val Ink = Color(0xff213c3b)
private val Muted = Color(0xff667776)
private val Teal = Color(0xff167668)
private val Cream = Color(0xfff7f7f0)
private val Line = Color(0xffdce3db)
private val Amber = Color(0xffa45e15)
private val SoftAmber = Color(0xfffff1d9)
private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

internal data class DemoGuidance(val stage: Int, val title: String, val detail: String, val action: String, val button: String)

internal fun demoGuidance(hasLab: Boolean, running: Boolean, hasAlert: Boolean, reviewed: Boolean, restored: Boolean, started: Boolean = false): DemoGuidance = when {
    restored -> DemoGuidance(5, "Restore checked. Your demo is complete.", "The restored copies matched the originals at the last check. Open the result folder or create a new demo.", "OPEN", "Open restored folder")
    !hasLab -> DemoGuidance(1, "Start with a safe, guided demo.", "Create 24 disposable files and backup copies. Your own documents are never used by the demo.", "CREATE", "Create safe demo")
    running -> DemoGuidance(2, "Demo running — watching for changes.", "Only the app’s disposable files are changing. An alert asks our own demo worker to pause.", "WAIT", "Watching changes…")
    hasAlert && !reviewed -> DemoGuidance(3, "An alert is ready to review.", "See what changed and why it triggered a warning before you restore the copies.", "REVIEW", "Review alert")
    started && !reviewed -> DemoGuidance(3, "Review the demo observations.", "The worker has stopped or paused. Check the recorded changes; no rule alert is not proof of safety.", "REVIEW", "Review changes")
    reviewed || hasAlert -> DemoGuidance(4, "Next: restore and check the copies.", "Restore into a new folder, then compare each file with its original. The changed evidence stays intact.", "RESTORE", "Restore & verify")
    else -> DemoGuidance(2, "Your safe demo is ready.", "The backup copies are verified. Start the demo to watch real changes to these disposable files.", "START", "Start demo")
}

private fun onUi(action: () -> Unit) = EventQueue.invokeLater(action)

class DesktopModel {
    var page by mutableStateOf("Overview")
    var health by mutableStateOf("Not monitoring")
    var location by mutableStateOf("No folder selected")
    var source by mutableStateOf("none")
    var busy by mutableStateOf(false)
    private val pageNotes = mutableStateMapOf("Overview" to "New here? Start with the safe demo below. It uses only disposable files created by this app.")
    var error by mutableStateOf<String?>(null)
    var errorPage by mutableStateOf("Overview")
    var operation by mutableStateOf("")
    var operationPage by mutableStateOf("Overview")
    var demoReviewed by mutableStateOf(false)
    var demoRunning by mutableStateOf(false)
    var mode by mutableStateOf("combined")
    var threshold by mutableStateOf(4)
    var lab by mutableStateOf<DemoLab?>(null)
    var workerStatus by mutableStateOf("No worker running")
    var workerWrites by mutableStateOf(0)
    var restoreResult by mutableStateOf<RestoreResult?>(null)
    var readiness by mutableStateOf<List<FileReadiness>>(emptyList())
    var readinessCheckedAtMs by mutableStateOf<Long?>(null)
    var memory by mutableStateOf<MemoryResult?>(null)
    var memoryVariant by mutableStateOf("present")
    var memorySeed by mutableStateOf("7")
    var memoryResultVariant by mutableStateOf("")
    var memoryResultSeed by mutableStateOf(0)
    val events = mutableStateListOf<FileEvent>()
    private var monitor: FolderMonitor? = null
    private var worker: DemoWorker? = null
    private var monitorGeneration = 0
    private var historyTruncated = false
    val canRescan: Boolean get() = monitor != null
    val analysis: Analysis get() = Engine.analyze(events.toList(), DetectionConfig(mode = mode, fileThreshold = threshold))
    val pageNote: String? get() = pageNotes[page]

    private fun note(page: String, text: String) { pageNotes[page] = text }

    private fun background(label: String, context: String = page, action: () -> Unit) {
        busy = true
        error = null
        operation = label
        operationPage = context
        thread(isDaemon = true, name = "ransomscope-operation") {
            try { action() } catch (failure: Exception) {
                onUi {
                    errorPage = context
                    error = "$label did not complete: ${failure.message ?: failure.javaClass.simpleName}"
                    note(context, "Nothing was marked complete. Review the error above, then retry the action.")
                }
            } finally { onUi { busy = false } }
        }
    }

    private fun reset() {
        monitorGeneration++
        monitor?.close()
        monitor = null
        worker?.close()
        worker = null
        events.clear()
        historyTruncated = false
        workerWrites = 0
        restoreResult = null
        readiness = emptyList()
        readinessCheckedAtMs = null
        demoReviewed = false
        pageNotes.remove("Incidents")
        pageNotes.remove("Recovery")
        note("Overview", "Choose a folder for read-only observation, open a recording, or start a new safe demo.")
        workerStatus = "No worker running"
        demoRunning = false
    }

    private fun observe(path: Path, eventSource: String) {
        val generation = monitorGeneration
        monitor = FolderMonitor(path, eventSource, { event ->
            onUi {
                if (generation != monitorGeneration) return@onUi
                if (events.size >= 4000) {
                    if (historyTruncated) {
                        val ordinary = events.indexOfFirst { it.path != "[history limit]" }
                        if (ordinary >= 0) events.removeAt(ordinary)
                    } else events.removeAt(0)
                    if (!historyTruncated) {
                        events += FileEvent(System.currentTimeMillis(), "[history limit]", "OVERFLOW", source = eventSource)
                        historyTruncated = true
                    }
                    health = "Degraded: history limit reached; export and restart"
                }
                events += event
                if (event.kind == "OVERFLOW") health = "Degraded: rescan required"
                val result = analysis
                if (result.firstAlertAtMs != null && eventSource == "synthetic") worker?.pause()
                workerWrites = worker?.writes?.size ?: 0
            }
        }, { status -> onUi { if (generation == monitorGeneration) health = status } },
            if (eventSource == "synthetic") setOf("canary-00.txt") else emptySet())
        monitor!!.start()
    }

    fun watchFolder(path: Path) {
        reset()
        lab = null
        location = path.toAbsolutePath().toString()
        source = "live"
        health = "Starting passive monitor…"
        background("Start read-only monitoring", "Overview") {
            observe(path.toAbsolutePath().normalize(), "live")
            onUi { if (monitor?.coverageIncomplete != true) health = "Monitoring • passive only"; note("Overview", "This folder is only being observed. RansomScope will not modify its files or stop other programs.") }
        }
    }

    fun createDemo() {
        reset()
        lab = null
        source = "synthetic"
        health = "Preparing isolated lab…"
        background("Create safe demo and verify backup copies", "Overview") {
            val fresh = DemoLab.create()
            val checks = fresh.readiness()
            val checkedAt = System.currentTimeMillis()
            observe(fresh.data, "synthetic")
            onUi {
                lab = fresh
                readiness = checks
                readinessCheckedAtMs = checkedAt
                location = fresh.data.toString()
                if (monitor?.coverageIncomplete != true) health = "Monitoring lab • snapshot verified"
                note("Overview", "24 disposable files and verified backup copies are ready. Next: start the demo to watch their changes.")
                workerStatus = "Ready to start"
            }
        }
    }

    fun runDemoWorker() {
        val selected = lab ?: return
        if (worker?.alive == true) return
        val generation = monitorGeneration
        background("Start demo worker", "Overview") {
            val freshWorker = DemoWorker(selected) { status -> onUi {
                if (generation != monitorGeneration) return@onUi
                workerStatus = status
                if (status.contains("paused") || status.contains("completed") || status.startsWith("Worker error")) demoRunning = false
                workerWrites = worker?.writes?.size ?: 0
                if (status.contains("paused")) {
                    val text = "The demo worker has confirmed it paused. Next: review the alert, then restore the disposable files."
                    note("Overview", text)
                    note("Incidents", text)
                }
            } }
            worker = freshWorker
            freshWorker.start()
            onUi { if (generation == monitorGeneration) demoRunning = true }
        }
    }

    fun pause() { worker?.pause() }

    fun rescan() {
        if (!canRescan) return
        val path = lab?.data ?: Path.of(location)
        val eventSource = source
        worker?.close()
        worker = null
        demoRunning = false
        monitorGeneration++
        monitor?.close()
        monitor = null
        events.clear()
        historyTruncated = false
        health = "Rebuilding content baseline…"
        background("Rescan folder", "Overview") {
            observe(path, eventSource)
            onUi {
                if (monitor?.coverageIncomplete != true) health = "Monitoring • fresh baseline"
                note("Overview", "A fresh observation session started. Changes missed before the rescan cannot be reconstructed.")
            }
        }
    }

    fun stop() {
        reset()
        health = "Stopped"
        source = "none"
        location = "No folder selected"
        lab = null
        note("Overview", "Monitoring stopped. Existing demo files remain on disk; your own documents were not changed.")
    }

    fun restore() {
        val selected = lab ?: return
        background("Restore and verify demo files", "Recovery") {
            worker?.close()
            monitorGeneration++
            monitor?.close()
            monitor = null
            worker = null
            onUi {
                demoRunning = false
                workerStatus = "Demo stopped — checking recovery copies"
                health = "Demo stopped • recovery in progress"
            }
            val result = selected.restore()
            val checks = selected.readiness(result.directory)
            val checkedAt = System.currentTimeMillis()
            onUi {
                restoreResult = result
                readiness = checks
                readinessCheckedAtMs = checkedAt
                demoRunning = false
                workerStatus = "Demo complete — files restored and verified"
                health = "Demo complete • restore verified"
                note("Recovery", "${checks.count { it.state == RecoveryState.RESTORE_VERIFIED }}/${result.total} restored copies matched their recorded originals at the last local check.")
                note("Overview", "Demo complete. Open the restored folder to inspect the verified copies, or create a new safe demo.")
                note("Incidents", "This recording belongs to a completed demo. Its restored copies were verified separately on the Recovery page.")
            }
        }
    }

    fun importEvents(path: Path) {
        reset()
        lab = null
        background("Import event recording", "Incidents") {
            require(Files.size(path) <= 2_000_000) { "Import limit is 2 MB." }
            val imported = Codecs.decodeEvents(Files.readString(path))
            require(imported.size <= 4000) { "Import limit is 4,000 events." }
            onUi {
                // Provenance is displayed as a replay even if the original file says live.
                events.addAll(imported.map { it.copy(source = "replay") })
                source = "replay"
                location = path.fileName.toString()
                health = "Replay • no live monitor"
                note("Incidents", "You are reviewing a recording. These imported events are not live observations of this computer.")
                page = "Incidents"
            }
        }
    }

    fun loadScenario(scenario: Scenario) {
        reset()
        lab = null
        events.addAll(scenario.events)
        source = "synthetic"
        location = scenario.title
        health = "Synthetic replay • no live monitor"
        note("Incidents", scenario.description)
        page = "Incidents"
    }

    fun runMemory() {
        val seed = memorySeed.toIntOrNull()
        if (seed == null) { errorPage = "Memory lab"; error = "Enter a whole number for the repeatable test seed."; return }
        val variant = memoryVariant
        background("Run synthetic memory test", "Memory lab") {
            val result = MemoryLab.run(seed, variant)
            onUi {
                memory = result
                memoryResultVariant = variant
                memoryResultSeed = seed
                note("Memory lab", if (result.verified > 0) "A candidate key passed the sample check. Next: try ‘Schedule erased’ to compare the failure case." else "This test did not recover a verified key. Next: compare with ‘Schedule present’ using the same test seed.")
            }
        }
    }

    fun exportEvents(path: Path) {
        val contents = Codecs.encodeEvents(events.toList())
        val context = page
        background("Save event recording", context) {
            Files.writeString(path, contents)
            onUi { note(context, "Event recording saved. Review file names before sharing it outside your computer.") }
        }
    }

    fun reviewDemo() {
        demoReviewed = true
        page = "Incidents"
        note("Incidents", "Review the alert reasons and changed files below. Next: restore and verify copies on the Recovery page.")
    }

    fun close() { monitorGeneration++; monitor?.close(); worker?.close() }
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--worker") {
        try { require(args.size == 3); runWorker(Path.of(args[1]), args[2]) }
        catch (failure: Exception) { println("ERROR|${failure.message}"); exitProcess(1) }
        return
    }
    if (args.firstOrNull() in listOf("--self-test", "--export-demo")) {
        val output = args.getOrNull(1)?.let { Path.of(it) }
        exitProcess(if (desktopSelfTest(output)) 0 else 1)
    }
    application {
        val model = remember {
            DesktopModel().also { if ("--demo" in args) it.loadScenario(DemoScenarios.all().first()) }
        }
        Window(onCloseRequest = { model.close(); exitApplication() }, title = "RansomScope — Recovery-first workspace",
            state = rememberWindowState(width = 1280.dp, height = 860.dp)) {
            MaterialTheme(colorScheme = lightColorScheme(primary = Teal, onPrimary = Color.White,
                surface = Color.White, background = Cream, onSurface = Ink, secondary = Amber),
                typography = Typography()) {
                RansomScope(model)
            }
        }
    }
}

@Composable
private fun RansomScope(model: DesktopModel) {
    Row(Modifier.fillMaxSize().background(Cream)) {
        Column(Modifier.width(218.dp).fillMaxHeight().background(Ink).padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xffb9dfc4)), contentAlignment = Alignment.Center) {
                    Text("R", color = Ink, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text("RansomScope", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("RECOVERY-FIRST WORKSPACE", color = Color(0xffa4bdb5), fontSize = 9.sp, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(40.dp))
            listOf("Overview", "Incidents", "Recovery", "Memory lab", "Methods").forEachIndexed { index, name ->
                val selected = model.page == name
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) Color(0xff365450) else Color.Transparent)
                    .clickable { model.page = name }.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("0${index + 1}", color = if (selected) Color(0xffb9dfc4) else Color(0xff8ea6a0), fontSize = 10.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(name, color = Color.White, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
                Spacer(Modifier.height(5.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("Know what happened.\nKnow what can recover.", color = Color(0xffd1ded8), fontSize = 17.sp, lineHeight = 24.sp, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(22.dp))
            Text("LOCAL BY DEFAULT\nNo cloud uploads · No arbitrary RAM scan", color = Color(0xff9fb8ae), fontSize = 10.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(14.dp))
            Text("Experimental · v0.1", color = Color(0xff9fb8ae), fontSize = 10.sp)
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 30.dp, vertical = 23.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("WORKSPACE / ${model.page.uppercase()}", color = Muted, fontSize = 11.sp, letterSpacing = 1.4.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val contextBadge = when {
                        model.page == "Memory lab" -> "SIMULATED MEMORY TEST"
                        model.page == "Methods" -> "CAPABILITIES & LIMITS"
                        model.restoreResult != null -> "DEMO COMPLETE"
                        model.lab != null -> "DISPOSABLE-FILE DEMO"
                        model.source == "live" -> "YOUR FOLDER · READ-ONLY"
                        model.source == "replay" -> "IMPORTED RECORDING"
                        model.source == "synthetic" -> "SYNTHETIC RECORDING"
                        else -> "READY TO START"
                    }
                    Badge(contextBadge, if (model.page == "Memory lab") SoftAmber else Color(0xffe5eee6))
                    Spacer(Modifier.width(12.dp))
                    Text("Kotlin Multiplatform", color = Muted, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(when (model.page) {
                        "Overview" -> "A clearer path to recovery."
                        "Incidents" -> "Every signal, with its context."
                        "Recovery" -> "Evidence before reassurance."
                        "Memory lab" -> "Test the recovery window."
                        else -> "Built to show its limits."
                    }, fontSize = 30.sp, lineHeight = 37.sp, color = Ink, fontFamily = FontFamily.Serif)
                    Spacer(Modifier.height(6.dp))
                    Text(when (model.page) {
                        "Overview" -> "Observe changes. Understand the alert. Verify the restore."
                        "Incidents" -> "See the warning, the changed files, and the reason behind it."
                        "Recovery" -> "Restore copies to a new folder, then check they match the originals."
                        "Memory lab" -> "Explore when a key can — and cannot — be recovered from a test fixture."
                        else -> "Research-informed decisions and transparent capability boundaries."
                    }, color = Muted, fontSize = 13.sp)
                }
                if (model.page != "Memory lab" && model.page != "Methods") {
                    OutlinedButton(onClick = { saveFile()?.let { model.exportEvents(it) } }, enabled = !model.busy && model.events.isNotEmpty()) { Text("Save recording") }
                }
            }
            Spacer(Modifier.height(22.dp))
            if (model.busy) {
                Text("${model.operation}…", color = Teal, fontSize = 12.sp)
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Teal)
                Spacer(Modifier.height(8.dp))
            }
            model.error?.takeIf { model.errorPage == model.page }?.let { Notice(it, true); Spacer(Modifier.height(10.dp)) }
            when (model.page) {
                "Overview" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Overview(model) }
                "Incidents" -> Incidents(model)
                "Recovery" -> Recovery(model)
                "Memory lab" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Memory(model) }
                else -> Methods()
            }
            model.pageNote?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 3, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ColumnScope.Overview(model: DesktopModel) {
    val analysis = model.analysis
    DemoGuide(model)
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        Metric("FILES WITH CHANGES", analysis.changedFiles.toString(), "Observed files, not a loss count", Modifier.weight(1f))
        Metric("RECORDED CHANGES", model.events.size.toString(), "Some notifications can be merged", Modifier.weight(1f))
        Metric("CURRENT STATE", if (model.restoreResult != null) "Complete" else if (model.source == "none") "Ready" else if (analysis.degraded) "Incomplete" else if (analysis.firstAlertAtMs != null) "Review" else if (model.source == "replay" || (model.source == "synthetic" && model.lab == null)) "Recording" else "Observing", if (model.restoreResult != null) "Last restore check recorded" else "No guarantee of safety", Modifier.weight(1f))
    }
    Spacer(Modifier.height(18.dp))
    SurfaceCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(if (model.lab != null) "Disposable demo files" else if (model.source == "live") "Your folder — read-only monitoring" else "Choose how to explore", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(model.health, color = if (analysis.degraded) Amber else Teal, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(model.location, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (model.canRescan) TextButton(onClick = { model.rescan() }, enabled = !model.busy) { Text("Rescan") }
            if (model.source != "none") TextButton(onClick = { model.stop() }, enabled = !model.busy) { Text("Stop") }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { chooseFolder()?.let { model.watchFolder(it) } }, enabled = !model.busy) { Text("Observe my folder") }
            if (model.lab != null) TextButton(onClick = { model.createDemo() }, enabled = !model.busy) { Text("New safe demo") }
            TextButton(onClick = { openFile()?.let { model.importEvents(it) } }, enabled = !model.busy) { Text("Open recording") }
        }
        Spacer(Modifier.height(10.dp))
        Text("Selected folders are read-only to this app. Only the separately generated lab can run the demo worker.", fontSize = 11.sp, color = Muted)
    }
    Spacer(Modifier.height(18.dp))
    if (model.lab != null) {
        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (model.restoreResult != null) "Completed demo" else "Demo controls", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(model.workerStatus, color = Teal, fontSize = 12.sp)
                    Text("${model.workerWrites} demo writes recorded · 24 original backup copies", color = Muted, fontSize = 11.sp)
                }
                OutlinedButton(onClick = { model.pause() }, enabled = !model.busy && model.demoRunning) { Text("Pause demo") }
            }
            Spacer(Modifier.height(10.dp))
            AdvancedSection("How the demo pause works") {
                Text("These are real changes to app-created disposable files. Only our own demo worker agrees to pause over a command channel; this does not suspend other programs or block ransomware system-wide.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
    } else {
        Text("TRY A REPLAY", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DemoScenarios.all().take(3).forEach { scenario ->
                Column(Modifier.weight(1f).height(123.dp).clip(RoundedCornerShape(13.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(13.dp))
                    .clickable { model.loadScenario(scenario) }.padding(16.dp)) {
                    Text(scenario.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(scenario.description, fontSize = 11.sp, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Notice("Not a replacement for endpoint protection or independent backups. A directory watcher cannot block writes before they happen.")
}

@Composable
private fun ColumnScope.Incidents(model: DesktopModel) {
    val analysis = model.analysis
    if (model.lab != null) { DemoGuide(model); Spacer(Modifier.height(12.dp)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("combined" to "Combined", "rate" to "Change rate", "entropy" to "Content change", "canary" to "Decoy file").forEach { (mode, label) ->
            FilterChip(selected = model.mode == mode, onClick = { model.mode = mode }, label = { Text(label) })
        }
        Spacer(Modifier.width(12.dp))
        Text("Files / 2 sec: ${model.threshold}", fontSize = 11.sp, color = Muted)
        Slider(model.threshold.toFloat(), { model.threshold = it.toInt() }, valueRange = 2f..12f, steps = 9, modifier = Modifier.width(125.dp))
    }
    Text(analysis.summary, color = if (analysis.firstAlertAtMs != null) Amber else Muted, fontSize = 12.sp)
    Spacer(Modifier.height(12.dp))
    if (model.events.isEmpty()) {
        EmptyState("Nothing observed yet", "Choose a monitored folder or select a synthetic replay below.")
    } else {
        SurfaceCard {
            Text("Recent file-change activity", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val events = model.events.toList()
            Canvas(Modifier.fillMaxWidth().height(52.dp)) {
                val count = minOf(60, events.size)
                val width = size.width / count.coerceAtLeast(1)
                events.takeLast(count).forEachIndexed { index, event ->
                    val h = if (event.canary) size.height else (12 + ((event.entropyAfter ?: 2.0) * 4)).toFloat().coerceAtMost(size.height)
                    drawRoundRect(if (event.canary) Color(0xffc58b3b) else Color(0xff8bb4a6), topLeft = androidx.compose.ui.geometry.Offset(index * width, size.height - h),
                        size = androidx.compose.ui.geometry.Size((width - 3).coerceAtLeast(1f), h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(analysis.decisions.takeLast(8).reversed()) { decision ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SoftAmber).padding(13.dp)) {
                    Badge(decision.level.uppercase(), Color(0xfff3dfb9))
                    Spacer(Modifier.width(12.dp))
                    Column { Text(decision.reasons.joinToString(" · "), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("${decision.paths.size} paths · ${decision.source} evidence", fontSize = 10.sp, color = Muted) }
                }
            }
            items(model.events.takeLast(100).reversed()) { event ->
                Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(9.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(TimeFormat.format(Instant.ofEpochMilli(event.atMs)), fontSize = 10.sp, color = Muted, modifier = Modifier.width(98.dp))
                    Text(event.kind, fontSize = 10.sp, color = if (event.canary) Amber else Teal, modifier = Modifier.width(72.dp))
                    Column(Modifier.weight(1f)) {
                        Text(event.path, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Observed file size: ${event.bytes} bytes · writer: ${event.attribution}", color = Muted, fontSize = 10.sp)
                    }
                    Text(event.source, fontSize = 10.sp, color = Muted)
                }
            }
        }
    }
    Spacer(Modifier.height(9.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DemoScenarios.all().forEach { scenario -> OutlinedButton(onClick = { model.loadScenario(scenario) }, enabled = !model.busy) { Text(scenario.title, fontSize = 10.sp) } }
    }
}

@Composable
private fun ColumnScope.Recovery(model: DesktopModel) {
    val selected = model.lab
    if (selected == null) {
        EmptyState("A readiness map needs evidence", "Create an isolated demo to obtain pre-change copies. Imported events alone cannot prove that a backup exists.")
        Button(onClick = { model.page = "Overview"; model.createDemo() }, enabled = !model.busy) { Text("Create demo with snapshot") }
        Spacer(Modifier.weight(1f))
        Notice("For passively monitored folders, recovery is unsupported until you independently provide and verify pre-change copies.")
        return
    }
    DemoGuide(model)
    Spacer(Modifier.height(13.dp))
    SurfaceCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Original backup copies", fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                Text("${selected.entries.size} disposable files · last local check ${model.readinessCheckedAtMs?.let { TimeFormat.format(Instant.ofEpochMilli(it)) } ?: "not available"}", color = Muted, fontSize = 12.sp)
            }
        }
        model.restoreResult?.let { result ->
            Spacer(Modifier.height(12.dp))
            Text("Last local check: ${model.readiness.count { it.state == RecoveryState.RESTORE_VERIFIED }}/${result.total} restored copies verified", color = Teal, fontWeight = FontWeight.SemiBold)
            Text(result.directory.toString(), color = Muted, fontSize = 11.sp)
        }
    }
    Spacer(Modifier.height(14.dp))
    Text("FILE-LEVEL RECOVERY READINESS", color = Muted, letterSpacing = 1.sp, fontSize = 11.sp)
    Spacer(Modifier.height(9.dp))
    val changed = model.events.map { it.path }.toSet()
    val readinessByPath = model.readiness.associateBy { it.path }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        items(selected.entries) { entry ->
            val checked = readinessByPath[entry.name]
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text("Original fingerprint: ${entry.hash.take(16)}…", fontSize = 10.sp, color = Muted)
                }
                Text(if (entry.name in changed) "Change observed" else "No change observed", color = Muted, fontSize = 10.sp)
                Spacer(Modifier.width(20.dp))
                val label = when (checked?.state) {
                    RecoveryState.RESTORE_VERIFIED -> "RESTORE CHECK PASSED"
                    RecoveryState.PRE_CHANGE_COPY -> "BACKUP CHECK PASSED"
                    RecoveryState.KEY_METHOD_VERIFIED -> "DECRYPTED FILE CHECK PASSED"
                    RecoveryState.UNSUPPORTED -> "NO VERIFIED RECOVERY PATH"
                    else -> "CHECK INCOMPLETE"
                }
                Badge(label, if (checked?.state in setOf(RecoveryState.RESTORE_VERIFIED, RecoveryState.PRE_CHANGE_COPY, RecoveryState.KEY_METHOD_VERIFIED)) Color(0xffe5eee6) else SoftAmber)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Notice("Statuses describe the last local file/hash check, not continuous validity. These demo copies are not tamper-proof or offline backups.")
}

@Composable
private fun ColumnScope.Memory(model: DesktopModel) {
    Notice("SIMULATED TEST · This page creates test data. It does not read your computer’s RAM or recover a key from real ransomware.")
    Spacer(Modifier.height(16.dp))
    SurfaceCard {
        Text("Choose what remains in the test data", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("present" to "Key structure present", "erased" to "Key structure erased", "raw-only" to "Raw key only", "corrupt" to "Damaged structure").forEach { (id, label) ->
                FilterChip(model.memoryVariant == id, onClick = { model.memoryVariant = id }, enabled = !model.busy, label = { Text(label, fontSize = 11.sp) })
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            OutlinedTextField(model.memorySeed, { model.memorySeed = it }, label = { Text("Repeatable test seed") }, singleLine = true, enabled = !model.busy, modifier = Modifier.width(180.dp))
            Button(onClick = { model.runMemory() }, enabled = !model.busy) { Text("Run memory test") }
        }
    }
    Spacer(Modifier.height(18.dp))
    model.memory?.let { result ->
        SurfaceCard {
            Badge(if (result.verified > 0) "SAMPLE CHECK PASSED" else "NO VERIFIED KEY", if (result.verified > 0) Color(0xffe5eee6) else SoftAmber)
            Spacer(Modifier.height(10.dp))
            Text(if (result.verified > 0) "A test key was found and checked." else "This test did not recover a verified key.", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text(if (result.verified > 0) "The candidate reproduced the encrypted sample. This proves this synthetic test case, not live RAM recovery." else "The supported key structure was missing or invalid. Other recovery methods may still exist.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(9.dp))
            Text("Result from: ${model.memoryResultVariant} · seed ${model.memoryResultSeed}", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                model.memoryVariant = if (result.verified > 0) "erased" else "present"
                model.runMemory()
            }, enabled = !model.busy) { Text(if (result.verified > 0) "Compare: erase the key structure" else "Compare: keep the key structure") }
            AdvancedSection("See technical test details") {
                Text(result.detail, fontSize = 11.sp, color = Muted, lineHeight = 18.sp)
                Text("${result.scannedBytes} synthetic bytes · ${result.candidates} candidates · ${result.verified} sample-verified\nKey preview: ${result.keyPreview}", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
            }
        }
    } ?: EmptyState("Start with ‘Key structure present’", "Run the test once, then erase the structure and compare. A key must be present in a supported form and pass a sample check.")
    Spacer(Modifier.height(15.dp))
    AdvancedSection("What this experiment can and cannot show") {
        Text("The shared Kotlin engine looks for a standard AES-128 expanded key schedule and checks candidates against an encrypted sample. Raw random keys have no universal recognizable signature. This does not capture real process memory, prove a crypto provider’s memory layout, or measure recovery against real ransomware.", fontSize = 11.sp, lineHeight = 18.sp, color = Muted)
    }
}

@Composable
private fun ColumnScope.Methods() {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        SurfaceCard {
            Text("One Kotlin engine. Two useful platforms.", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text("The desktop app observes selected directories and runs isolated lab experiments. The browser imports and replays event records through the same Kotlin rules. Neither invents process attribution.", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
        }
        listOf(
            "Signals, not certainty" to "Rate, content entropy change and canary context can explain an alert. Compression and legitimate encryption can look similar; slow or partial changes may evade these rules.",
            "A directory watcher is not a write filter" to "WatchService receives notifications after activity, can merge or lose events, and does not identify the writing process. Degraded evidence is surfaced; no arbitrary process is killed.",
            "A controlled pause has a narrow meaning" to "Only our own disposable-file child worker accepts the pause command over an explicit IPC channel. This is not a Windows-wide ransomware blocker.",
            "Recovery is independently verified" to "Pre-change copies are restored into a fresh directory, then checked using SHA-256. A local same-user snapshot can still be deleted by a real attacker.",
            "Memory recovery has conditions" to "PayBreak, ShieldFS, Redemption and live-forensics research motivate distinct layers. Synthetic expanded AES key schedules illustrate a recoverable structure; keys may be erased, stored differently or unavailable.",
            "Privacy and reproducibility" to "No telemetry or memory is uploaded. Exports can contain relative file names: review them before sharing. Experiments use generated documents and record their provenance.",
            "Research foundations" to "ShieldFS (ACSAC 2016); Redemption (RAID 2017); PayBreak (ASIACCS 2017); Davies et al. (Digital Investigation 2020); ERW-Radar (NDSS 2025). See the project README for sources, threat model and measured test results."
        ).forEach { (title, body) -> SurfaceCard { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Spacer(Modifier.height(7.dp)); Text(body, color = Muted, fontSize = 12.sp, lineHeight = 19.sp) } }
    }
}

@Composable
private fun DemoGuide(model: DesktopModel) {
    val guide = demoGuidance(model.lab != null, model.demoRunning, model.analysis.firstAlertAtMs != null,
        model.demoReviewed, model.restoreResult != null, model.workerStatus != "Ready to start" && model.workerStatus != "No worker running")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xffe8f0e6)).padding(17.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            Column(Modifier.weight(1f)) {
                Text(guide.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(Modifier.height(5.dp))
                Text(guide.detail, fontSize = 11.sp, color = Muted, lineHeight = 17.sp)
                if (model.source == "live" && model.lab == null) {
                    Text("Starting the demo stops this read-only observation session; it does not change your folder.", fontSize = 10.sp, color = Muted, lineHeight = 15.sp)
                }
            }
            Button(onClick = {
                when (guide.action) {
                    "CREATE" -> { model.page = "Overview"; model.createDemo() }
                    "START" -> model.runDemoWorker()
                    "REVIEW" -> model.reviewDemo()
                    "RESTORE" -> { model.page = "Recovery"; model.restore() }
                    "OPEN" -> model.restoreResult?.let { if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(it.directory.toFile()) }
                }
            }, enabled = !model.busy && guide.action != "WAIT") { Text(guide.button, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(13.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Create files", "Run demo", "Review alert", "Restore copies", "Verify result").forEachIndexed { index, label ->
                val number = index + 1
                val done = number < guide.stage || model.restoreResult != null
                val active = number == guide.stage
                Text("${if (done) "✓" else number.toString()}  $label", color = if (done || active) Teal else Muted,
                    fontWeight = if (active || done) FontWeight.SemiBold else FontWeight.Normal, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun AdvancedSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
            Text("$title ${if (expanded) "−" else "+"}", fontSize = 11.sp)
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(15.dp)).padding(20.dp), content = content)
}

@Composable
private fun Metric(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(13.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(13.dp)).padding(17.dp)) {
        Text(label, fontSize = 10.sp, letterSpacing = 1.sp, color = Muted)
        Spacer(Modifier.height(7.dp))
        Text(value, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(caption, fontSize = 10.sp, color = Muted)
    }
}

@Composable
private fun Badge(label: String, background: Color) {
    Text(label, color = Ink, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 9.dp, vertical = 6.dp))
}

@Composable
private fun Notice(message: String, error: Boolean = false) {
    Text(message, color = if (error) Color(0xff943925) else Amber, fontSize = 11.sp, lineHeight = 17.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(if (error) Color(0xffffe9e2) else SoftAmber).padding(13.dp))
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 18.sp, fontFamily = FontFamily.Serif)
        Spacer(Modifier.height(9.dp))
        Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.widthIn(max = 560.dp))
    }
}

private fun chooseFolder(): Path? = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; dialogTitle = "Choose folder — passive monitoring only" }
    .let { if (it.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) it.selectedFile.toPath() else null }
private fun openFile(): Path? = JFileChooser().apply { dialogTitle = "Import RansomScope event JSON" }
    .let { if (it.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) it.selectedFile.toPath() else null }
private fun saveFile(): Path? = JFileChooser().apply { dialogTitle = "Export event JSON — review paths before sharing"; selectedFile = java.io.File("ransomscope-events.json") }
    .let {
        if (it.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) null
        else {
            val path = it.selectedFile.toPath()
            if (Files.exists(path) && JOptionPane.showConfirmDialog(null, "Replace the existing export file?", "Confirm replacement", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) null
            else path
        }
    }
