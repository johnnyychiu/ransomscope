@file:Suppress("UnsafeCastFromDynamic")

package ransomscope.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.*
import ransomscope.core.*
import kotlin.math.max
import kotlin.math.min

private val scenarios = DemoScenarios.all()
private var activeScenario = scenarios.first()
private var events: List<FileEvent> = activeScenario.events.sortedBy { it.atMs }
private var title = activeScenario.title
private var description = activeScenario.description
private var imported = false
private var position = 0
private var route = "overview"
private var mode = "combined"
private var windowMs = 2000L
private var fileThreshold = 4
private var entropyThreshold = 1.5
private var interval: Int? = null
private var replaySpeed = 2
private var running = false
private var errorMessage: String? = null
private var notice: String? = null
private var memoryResult: MemoryResult? = null
private var memoryVariant = "present"
private var memorySeed = 7
private var memoryBusy = false
private var selection: FileEvent? = null
private var reviewed = false
private var advancedOpen = false
private var sourceDetailsOpen = false
private var eventDetailsOpen = false
private var memoryDetailsOpen = false
private var incidentControlsOpen = false
private var content: HTMLElement? = null
private var statusLine: HTMLElement? = null

private fun el(tag: String, classes: String = "", text: String? = null): HTMLElement {
    val node = document.createElement(tag) as HTMLElement
    node.className = classes
    if (text != null) node.textContent = text
    return node
}

private fun HTMLElement.add(tag: String, classes: String = "", text: String? = null, init: (HTMLElement.() -> Unit)? = null): HTMLElement {
    val node = el(tag, classes, text)
    appendChild(node)
    init?.invoke(node)
    return node
}

private fun HTMLElement.button(label: String, classes: String = "button", id: String? = null, disabled: Boolean = false, action: () -> Unit): HTMLButtonElement {
    val button = el("button", classes, label) as HTMLButtonElement
    button.type = "button"
    button.disabled = disabled
    if (id != null) button.id = id
    button.onclick = { action(); Unit }
    appendChild(button)
    return button
}

private fun HTMLElement.badge(text: String, kind: String = "neutral") = add("span", "badge $kind", text)

private fun HTMLElement.disclosure(label: String, open: Boolean, changed: (Boolean) -> Unit): HTMLElement {
    val details = add("details", "disclosure")
    if (open) details.setAttribute("open", "")
    details.add("summary", text = label)
    details.addEventListener("toggle", { changed(details.hasAttribute("open")) })
    return details
}

private fun navigate(destination: String) {
    if (route == destination) { renderBody(); window.scrollTo(0.0, 0.0) }
    else window.location.hash = destination
}

private fun reviewResult() {
    reviewed = true
    val firstPath = analyzed().decisions.firstOrNull()?.paths?.firstOrNull()
    selection = events.take(position).firstOrNull { it.path == firstPath } ?: events.take(position).lastOrNull()
    navigate("incident")
}

private fun HTMLElement.select(id: String, label: String, choices: List<Pair<String, String>>, selected: String, onChange: (String) -> Unit): HTMLSelectElement {
    val wrapper = add("label", "field")
    wrapper.add("span", "field-label", label)
    val selector = el("select") as HTMLSelectElement
    selector.id = id
    selector.setAttribute("aria-label", label)
    choices.forEach { (value, caption) ->
        val option = el("option", text = caption) as HTMLOptionElement
        option.value = value
        option.selected = value == selected
        selector.appendChild(option)
    }
    selector.onchange = { onChange(selector.value); Unit }
    wrapper.appendChild(selector)
    return selector
}

private fun analyzed(): Analysis = Engine.analyze(events.take(position), DetectionConfig(mode = mode, windowMs = windowMs, fileThreshold = fileThreshold, entropyThreshold = entropyThreshold))
private fun countLabel(value: Long): String = when {
    value >= 1_048_576 -> "${(value / 104_857).toDouble() / 10} MB"
    value >= 1024 -> "${(value / 102).toDouble() / 10} KB"
    else -> "$value B"
}

private fun timeLabel(ms: Long): String {
    val value = max(0L, ms - (events.firstOrNull()?.atMs ?: 0L))
    val minutes = value / 60_000
    val seconds = (value % 60_000) / 1_000
    val tenths = (value % 1_000) / 100
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}.$tenths"
}

private fun replayState() = when {
    running -> "Replay running"
    position == events.size && events.isNotEmpty() -> "Replay complete"
    position > 0 -> "Replay paused"
    else -> "Ready to explore"
}

private fun stop() {
    interval?.let { window.clearInterval(it) }
    interval = null
    running = false
}

private fun start() {
    if (events.isEmpty()) return
    if (position >= events.size) { position = 0; reviewed = false; selection = null }
    stop()
    running = true
    interval = window.setInterval({
        position = min(events.size, position + max(1, events.size / 35))
        if (position >= events.size) stop()
        renderBody()
    }, 1000 / replaySpeed)
    renderBody()
}

private fun changeScenario(id: String) {
    stop()
    activeScenario = scenarios.first { it.id == id }
    events = activeScenario.events.sortedBy { it.atMs }
    title = activeScenario.title
    description = activeScenario.description
    imported = false
    position = 0
    selection = null
    reviewed = false
    errorMessage = null
    notice = null
    renderBody()
}

private fun renderShell() {
    val root = document.getElementById("app") as HTMLElement
    root.textContent = ""
    val sidebar = root.add("aside", "sidebar")
    val brand = sidebar.add("a", "brand") as HTMLAnchorElement
    brand.href = "#overview"
    brand.setAttribute("aria-label", "RansomScope overview")
    brand.add("span", "brand-mark", "R")
    brand.add("span", "brand-name", "RansomScope")
    sidebar.add("div", "edition", "RECOVERY WORKBENCH")
    val nav = sidebar.add("nav", "nav")
    nav.setAttribute("aria-label", "Main navigation")
    listOf("overview" to "Overview", "incident" to "Incident replay", "recovery" to "Recovery", "memory" to "Memory lab", "methods" to "Methods & limits").forEachIndexed { index, (key, label) ->
        val item = nav.add("a", "nav-item", "${listOf("◫", "≋", "↶", "◇", "≡")[index]}   $label") as HTMLAnchorElement
        item.href = "#$key"
        item.setAttribute("data-route", key)
    }
    val sideNote = sidebar.add("div", "sidebar-note")
    sideNote.badge("Browser workspace", "teal")
    sideNote.add("p", text = "Explore evidence. Understand what can be recovered.")
    sideNote.add("small", text = "Local processing · no uploads")
    val main = root.add("div", "main-shell")
    val topbar = main.add("header", "topbar")
    topbar.add("span", "breadcrumb", "Workspace / RansomScope")
    val topActions = topbar.add("div", "top-actions")
    topActions.badge("KOTLIN MULTIPLATFORM", "neutral")
    topActions.button("Import events", "button secondary compact", "import-button") { chooseImport() }
    val fileInput = el("input") as HTMLInputElement
    fileInput.id = "event-file"
    fileInput.type = "file"
    fileInput.accept = "application/json,.json"
    fileInput.hidden = true
    fileInput.setAttribute("aria-label", "Choose an event JSON file")
    fileInput.onchange = { importFile(fileInput); Unit }
    main.appendChild(fileInput)
    content = main.add("main", "content")
    content!!.id = "main-content"
    content!!.setAttribute("tabindex", "-1")
    statusLine = main.add("footer", "footer")
    statusLine!!.setAttribute("aria-live", "polite")
    renderBody()
}

private fun renderBody() {
    val body = content ?: return
    val focusedId = document.activeElement?.id
    val tableScroll = (document.getElementById("event-table-scroll") as? HTMLElement)?.scrollTop
    body.textContent = ""
    val navItems = document.querySelectorAll("[data-route]")
    for (index in 0 until navItems.length) {
        val item = navItems.item(index) as HTMLElement
        val selected = item.getAttribute("data-route") == route
        item.className = "nav-item${if (selected) " active" else ""}"
        if (selected) item.setAttribute("aria-current", "page") else item.removeAttribute("aria-current")
    }
    errorMessage?.let { message ->
        val error = body.add("div", "message error feedback-message")
        error.setAttribute("role", "alert")
        error.add("div") { add("strong", text = "We couldn't use that input"); add("p", text = message) }
        error.button("Dismiss", "button ghost compact", "dismiss-error") { errorMessage = null; renderBody() }
    }
    notice?.let { message ->
        val feedback = body.add("div", "message success feedback-message")
        feedback.setAttribute("role", "status")
        feedback.add("span", text = message)
        feedback.button("Dismiss", "button ghost compact", "dismiss-notice") { notice = null; renderBody() }
    }
    when (route) {
        "incident" -> renderIncident(body)
        "recovery" -> renderRecovery(body)
        "memory" -> renderMemory(body)
        "methods" -> renderMethods(body)
        else -> renderOverview(body)
    }
    statusLine?.textContent = "RansomScope · Shared Kotlin engine on JVM + JavaScript · Browser replay does not monitor your operating system"
    if (tableScroll != null) (document.getElementById("event-table-scroll") as? HTMLElement)?.scrollTop = tableScroll
    if (!focusedId.isNullOrBlank()) (document.getElementById(focusedId) as? HTMLElement)?.focus()
}

private fun heading(body: HTMLElement, eyebrow: String, name: String, subtitle: String) {
    body.add("div", "eyebrow", eyebrow)
    body.add("h1", text = name)
    body.add("p", "page-description", subtitle)
}

private fun renderJourney(body: HTMLElement, result: Analysis, compact: Boolean = false) {
    val empty = events.isEmpty()
    val complete = !empty && position == events.size
    val current = when {
        empty -> 1
        route == "recovery" && position > 0 -> 4
        reviewed -> 4
        complete -> 3
        else -> 2
    }
    val guide = body.add("section", "journey")
    guide.id = "replay-guide"
    guide.setAttribute("aria-label", "Guided replay")
    val steps = guide.add("nav", "journey-steps")
    steps.setAttribute("aria-label", "Replay steps")
    listOf("Choose evidence", "Run replay", "Review result", "Check recovery").forEachIndexed { index, label ->
        val number = index + 1
        val button = steps.button("$number  $label", "journey-step${if (number == current) " current" else if (number < current) " done" else ""}", "guide-step-$number", (number >= 3 && position == 0) || (number == 2 && empty)) {
            when (number) {
                1 -> { navigate("overview"); window.setTimeout({ (document.getElementById("scenario-select") as? HTMLElement)?.focus() }, 0) }
                2 -> if (!running) start()
                3 -> reviewResult()
                4 -> navigate("recovery")
            }
        }
        if (number == current) button.setAttribute("aria-current", "step")
    }
    if (compact) {
        guide.className += " compact-journey"
        return
    }
    val title: String
    val message: String
    when {
        empty -> { title = "This collection has no events"; message = "Choose a sample below or import another event file to begin." }
        running -> { title = "Watching the replay unfold"; message = "You can pause at any time. The file count and explanations update as events appear." }
        position == 0 -> { title = "Ready: ${if (imported) "your imported collection" else "a safe practice scenario"}"; message = "Press Run replay. You'll see what changed, why the rule reacted, and what to check next." }
        result.degraded -> { title = "Some evidence is missing"; message = "A collection gap was reported. Review what is available; the result cannot describe the whole incident." }
        route == "recovery" -> { title = "Check the evidence before restoring"; message = "This browser has events, not backup files. Use the desktop recovery workflow to verify a snapshot and restore it." }
        reviewed -> { title = "Next: check your recovery options"; message = "You've opened the result. The recovery view shows which supporting evidence is still needed." }
        result.decisions.isNotEmpty() -> { title = if (complete) "Replay finished: activity needs a closer look" else "Activity needs a closer look"; message = "Open Review result to see the reasons. An alert asks for investigation; it does not prove an attack." }
        complete -> { title = "Replay finished: this rule raised no alert"; message = "Review the events or try a different rule. No alert does not guarantee that an incident is harmless." }
        else -> { title = "Replay paused at $position of ${events.size} events"; message = "Continue where you left off, or review the evidence already shown." }
    }
    val status = guide.add("div", "journey-status")
    status.add("div") { add("h2", text = title); add("p", text = message) }
    when {
        empty -> status.button("Import another file", "button primary", "guide-next") { chooseImport() }
        running -> status.button("Pause replay", "button primary", "guide-next") { stop(); renderBody() }
        position == 0 -> status.button("Run replay →", "button primary", "guide-next") { start() }
        route == "recovery" -> status.button("Back to result", "button secondary", "guide-next") { reviewResult() }
        reviewed -> status.button("Check recovery →", "button primary", "guide-next") { navigate("recovery") }
        complete || result.decisions.isNotEmpty() || result.degraded -> status.button("Review result →", "button primary", "guide-next") { reviewResult() }
        else -> status.button("Continue replay →", "button primary", "guide-next") { start() }
    }
}

private fun renderOverview(body: HTMLElement) {
    heading(body, "START HERE", "Understand what changed.", "Try a safe practice replay, inspect the reasons behind an alert, then check the evidence needed for recovery.")
    val intro = body.add("div", "workspace-banner")
    val introText = intro.add("div")
    introText.add("h2", text = if (imported) "Your event collection is loaded" else "A practice scenario is ready")
    introText.add("p", text = "No setup needed. Replay the sample below, or import a desktop event export. Everything is processed here, on your device.")
    intro.badge(if (imported) "Imported events" else "Synthetic practice data", if (imported) "neutral" else "amber")
    val result = analyzed()
    renderJourney(body, result)
    renderControls(body)
    renderStats(body, result)
    val grid = body.add("div", "two-columns")
    val panel = grid.add("section", "panel")
    val sectionHeading = panel.add("div", "section-heading")
    sectionHeading.add("h2", text = "Activity at a glance")
    sectionHeading.badge(replayState(), if (running) "teal" else "neutral")
    panel.add("p", "muted", "Each bar shows how many events appeared in that part of the replay.")
    renderChart(panel)
    val chartLabels = panel.add("div", "chart-labels")
    chartLabels.add("span", text = events.firstOrNull()?.let { timeLabel(it.atMs) } ?: "00:00.0")
    chartLabels.add("span", text = events.lastOrNull()?.let { timeLabel(it.atMs) } ?: "00:00.0")
    val signals = grid.add("section", "panel")
    signals.add("h2", text = "What the engine found")
    if (result.decisions.isEmpty()) {
        signals.add("div", "empty-icon", "◇")
        signals.add("h3", text = if (position == 0) "Your first step: run the replay" else "No alert from this rule so far")
        signals.add("p", "muted", if (position == 0) "No files on your computer will be changed. This browser is replaying event data." else "You can inspect the events or compare another rule using the same collection.")
    } else {
        result.decisions.takeLast(3).reversed().forEach { decision ->
            val card = signals.add("div", "signal")
            card.badge(decision.level, if (decision.level.lowercase() in listOf("high", "alert", "critical")) "red" else "amber")
            card.add("strong", text = "${timeLabel(decision.atMs)} · ${decision.paths.size} paths")
            card.add("p", text = decision.reasons.joinToString(" · "))
        }
    }
    val next = body.add("div", "next-step")
    next.add("div") { add("strong", text = "Look closer when you're ready"); add("p", "muted", "The incident view connects each explanation with the events behind it.") }
    next.button("Review result →", "button secondary", "open-incident", position == 0) { reviewResult() }
}

private fun renderControls(body: HTMLElement) {
    val panel = body.add("section", "panel replay-controls")
    panel.add("div", "section-heading") { add("h2", text = "Replay controls"); badge(if (imported) "Imported events" else "Synthetic practice data", if (imported) "neutral" else "amber") }
    val selectors = panel.add("div", "control-fields")
    selectors.select("scenario-select", "1. Choose a sample or imported collection", scenarios.map { it.id to it.title } + if (imported) listOf("imported" to title) else emptyList(), if (imported) "imported" else activeScenario.id) { if (it != "imported") changeScenario(it) }
    selectors.select("mode-select", "Rule to compare", listOf("combined" to "Combined signals · recommended", "rate" to "Write rate only", "entropy" to "Entropy only", "canary" to "Canary only"), mode) { mode = it; renderBody() }
    selectors.select("speed-select", "Replay pace", listOf("1" to "Slow", "2" to "Normal", "4" to "Fast"), replaySpeed.toString()) {
        replaySpeed = it.toInt()
        if (running) start() else renderBody()
    }
    panel.add("p", "scenario-description", description)
    val settings = panel.disclosure("Advanced rule settings", advancedOpen) { advancedOpen = it }
    settings.className += " detector-settings"
    val settingsFields = settings.add("div", "control-fields")
    fun numberSetting(id: String, label: String, value: String, lower: String, upper: String, step: String, onValid: (Double) -> Unit) {
        val field = settingsFields.add("label", "field")
        field.add("span", "field-label", label)
        val input = el("input") as HTMLInputElement
        input.id = id
        input.type = "number"
        input.value = value
        input.min = lower
        input.max = upper
        input.step = step
        input.onfocus = { if (running) { stop(); renderBody() }; Unit }
        input.onchange = {
            val changed = input.value.toDoubleOrNull()
            if (changed == null || !changed.isFinite() || changed < lower.toDouble() || changed > upper.toDouble() || (step == "1" && changed != changed.toInt().toDouble())) {
                input.setCustomValidity("Use a valid value between $lower and $upper.")
                input.reportValidity()
            } else {
                input.setCustomValidity("")
                onValid(changed)
                renderBody()
            }
            Unit
        }
        field.appendChild(input)
    }
    numberSetting("window-setting", "Window (milliseconds)", windowMs.toString(), "100", "60000", "1") { windowMs = it.toLong() }
    numberSetting("paths-setting", "Distinct path threshold", fileThreshold.toString(), "2", "1000", "1") { fileThreshold = it.toInt() }
    numberSetting("entropy-setting", "Entropy change threshold", entropyThreshold.toString(), "0", "8", "0.1") { entropyThreshold = it }
    settings.add("small", "muted", "Exploration settings, not calibrated probabilities. Changing a setting re-analyzes the same observed evidence.")
    val actionBar = panel.add("div", "action-bar")
    val buttons = actionBar.add("div", "button-group")
    buttons.button(if (running) "Pause replay" else if (position == events.size && events.isNotEmpty()) "Run again" else "Run replay", "button primary", "run-replay", events.isEmpty()) { if (running) { stop(); renderBody() } else start() }
    buttons.button("Step", "button secondary", "step-replay", running || position >= events.size) { position++; renderBody() }
    buttons.button("Reset replay", "button ghost", "reset-replay", !running && position == 0) { stop(); position = 0; selection = null; reviewed = false; renderBody() }
    val progress = actionBar.add("div", "progress-info")
    progress.add("span", text = "$position / ${events.size} events")
    val meter = progress.add("div", "progress-track")
    meter.setAttribute("role", "progressbar")
    meter.setAttribute("aria-label", "Replay progress")
    meter.setAttribute("aria-valuenow", position.toString())
    meter.setAttribute("aria-valuemin", "0")
    meter.setAttribute("aria-valuemax", max(1, events.size).toString())
    meter.add("div", "progress-fill").style.width = "${if (events.isEmpty()) 0 else position * 100 / events.size}%"
    panel.add("small", "muted", "Local replay only — this browser does not watch or change your files.")
    val sourceDetails = panel.disclosure("About this replay and its evidence", sourceDetailsOpen) { sourceDetailsOpen = it }
    sourceDetails.add("p", "muted", "Replay is time-compressed. Times are elapsed from the first event; original timestamps remain in exports. ${if (imported) "The exporting tool supplied these fields. They have not been independently authenticated." else "All sample events are synthetic teaching data."}")
    sourceDetails.add("p", "muted", "A folder event does not reliably identify the process that wrote the file. An unknown writer remains unknown. Rule output is a reason to investigate, not a verdict.")
}

private fun renderStats(body: HTMLElement, result: Analysis) {
    val stats = body.add("div", "stats")
    listOf(Triple("Changed paths", result.changedFiles.toString(), "Distinct files or paths in this replay"), Triple("Reported file size", countLabel(result.changedBytes), "Size estimate — not bytes lost"), Triple("Alerts to review", result.decisions.size.toString(), "Reasons from the selected rule"), Triple("Collection status", if (result.degraded) "Evidence missing" else "No gap reported", if (result.degraded) "Review with care: some events are missing" else "Based on the supplied events")).forEachIndexed { index, item ->
        val stat = stats.add("div", "stat${if (index == 3 && result.degraded) " warning" else ""}")
        stat.add("span", "stat-label", item.first)
        stat.add("strong", "stat-value", item.second)
        stat.add("small", text = item.third)
    }
}

private fun renderChart(parent: HTMLElement) {
    val chart = parent.add("div", "activity-chart")
    chart.setAttribute("aria-label", "File event distribution")
    chart.setAttribute("role", "img")
    val slots = 28
    val counts = IntArray(slots)
    val first = events.firstOrNull()?.atMs ?: 0
    val span = max(1L, (events.lastOrNull()?.atMs ?: 0) - first)
    events.take(position).filter { it.kind != "OVERFLOW" }.forEach { event ->
        val bin = min(slots - 1, max(0, (((event.atMs - first).toDouble() / span) * slots).toInt()))
        counts[bin]++
    }
    val highest = max(1, counts.maxOrNull() ?: 1)
    for (count in counts) {
        val bar = chart.add("div", "activity-bar${if (count == 0) " empty" else ""}")
        bar.style.height = "${max(3, count * 100 / highest)}%"
        bar.title = "$count events"
    }
}

private fun renderIncident(body: HTMLElement) {
    heading(body, "REVIEW YOUR RESULT", "See why the rule reacted.", "Open an event to inspect what changed, then connect it with the explanation on the right.")
    val result = analyzed()
    val alerts = result.decisions.filter { it.level == "alert" }
    val summary = body.add("section", "panel incident-summary${if (result.degraded) " evidence-gap" else ""}")
    summary.id = "incident-result-summary"
    val summaryHeader = summary.add("div", "section-heading")
    summaryHeader.add("h2", text = when {
        position == 0 -> "No events reviewed yet"
        result.degraded && alerts.isEmpty() -> "Some evidence is missing"
        alerts.isNotEmpty() -> "${alerts.size} ${if (alerts.size == 1) "alert" else "alerts"} · ${result.changedFiles} changed paths to review"
        else -> "${result.changedFiles} changed paths · no alert from this rule"
    })
    summaryHeader.badge(if (imported) "Imported events" else "Synthetic practice data", if (imported) "neutral" else "amber")
    when {
        position == 0 -> summary.add("p", text = "Start the replay to reveal the result. No files on your computer will be changed.")
        alerts.isNotEmpty() -> {
            summary.add("p", "summary-intro", "The rule reacted because:")
            val reasons = summary.add("ul", "result-reasons")
            alerts.first().reasons.take(2).forEach { reasons.add("li", text = it) }
            if (result.degraded) summary.add("p", "gap-note", "Some events are missing. These reasons describe only the evidence available.")
        }
        result.degraded -> summary.add("p", text = "A collection gap was reported. Inspect the events below, but do not treat this collection as a complete account of the incident.")
        else -> summary.add("p", text = "The selected rule did not flag the events shown. Inspect them below or expand the replay controls to compare another rule; no alert is not a guarantee of safety.")
    }
    val summaryActions = summary.add("div", "summary-actions")
    summaryActions.add("span", "muted", if (position == 0) "$position of ${events.size} events shown" else "$position of ${events.size} events shown · ${replayState().lowercase()}")
    if (position > 0) summaryActions.button("Check recovery →", "button primary compact", "summary-check-recovery") { reviewed = true; navigate("recovery") }
    else summaryActions.button("Run replay →", "button primary compact", "summary-run-replay", events.isEmpty()) { start() }
    renderJourney(body, result, compact = true)
    val controls = body.disclosure("Replay controls and settings", incidentControlsOpen) { incidentControlsOpen = it }
    controls.className += " incident-controls"
    controls.id = "incident-replay-controls"
    renderControls(controls)
    val grid = body.add("div", "incident-layout")
    val timeline = grid.add("section", "panel")
    val header = timeline.add("div", "section-heading")
    header.add("h2", text = "What changed")
    header.button("Export events", "button secondary compact", "export-events", position == 0) { exportEvents() }
    if (position == 0) timeline.add("div", "empty-state") {
        add("h3", text = if (events.isEmpty()) "Choose an event collection first" else "Ready for the first event")
        add("p", text = if (events.isEmpty()) "Import another file or choose a practice sample above." else "Run the replay above, or use Step to reveal one event at a time.")
    }
    else {
        val wrap = timeline.add("div", "table-wrap")
        wrap.id = "event-table-scroll"
        val table = wrap.add("table", "event-table")
        table.add("caption", "sr-only", "Observed file activity, latest events first")
        table.add("thead") { add("tr") { listOf("Time", "Path / action", "Bytes", "Evidence").forEach { add("th", text = it).setAttribute("scope", "col") } } }
        val tbody = table.add("tbody")
        events.take(position).takeLast(100).reversed().forEachIndexed { index, event ->
            val row = tbody.add("tr", if (selection === event) "selected" else "")
            row.add("td", "mono time-cell", timeLabel(event.atMs))
            val path = row.add("td")
            path.button(event.path.ifBlank { "Collection status" }, "path-button", "event-path-${position - 1 - index}", action = { selection = event; reviewed = true; renderBody() })
            path.add("small", "muted", event.kind)
            row.add("td", "mono", countLabel(event.bytes))
            row.add("td") { badge(event.source, if (event.source == "live") "neutral" else "amber") }
        }
    }
    val detail = grid.add("section", "panel")
    detail.add("h2", text = "Your selected event")
    val selected = selection
    if (selected == null) detail.add("p", "muted", "Select a filename on the left. Its details will appear here.")
    else {
        detail.add("p", "detail-path", selected.path)
        detail.add("p", text = "${selected.kind.lowercase().replaceFirstChar { it.uppercase() }} event at ${timeLabel(selected.atMs)} after the collection began.")
        val list = detail.add("dl", "detail-list")
        listOf("Evidence type" to selected.source, "Reported file size" to countLabel(selected.bytes), "Writer" to (selected.actor ?: "Not identified")).forEach { (key, value) -> list.add("dt", text = key); list.add("dd", text = value) }
        val technical = detail.disclosure("Technical event details", eventDetailsOpen) { eventDetailsOpen = it }
        val technicalList = technical.add("dl", "detail-list")
        listOf("Attribution source" to selected.attribution, "Canary" to if (selected.canary) "Reported canary event" else "Not marked", "Entropy before" to (selected.entropyBefore?.toString() ?: "Not collected"), "Entropy after" to (selected.entropyAfter?.toString() ?: "Not collected")).forEach { (key, value) -> technicalList.add("dt", text = key); technicalList.add("dd", text = value) }
        technical.add("small", "muted", "Imported attributes come from the exporting tool and have not been independently authenticated. Folder notifications alone do not identify the writer.")
    }
    detail.add("div", "divider")
    detail.add("h3", text = "Why it was flagged")
    result.decisions.takeLast(4).reversed().forEach { decision -> detail.add("div", "signal") { badge(decision.level, "amber"); add("p", text = decision.reasons.joinToString(" · ")) } }
    if (result.decisions.isEmpty()) detail.add("p", "muted", "This rule has not raised an alert in the events shown. That does not guarantee the activity is harmless.")
    detail.button("Export analysis", "button secondary", "export-analysis", position == 0) { download("ransomscope-analysis.json", Codecs.encodeAnalysis(result)) }
    if (position > 0) {
        val next = body.add("div", "next-step")
        next.add("div") { add("strong", text = "Next: check recovery evidence"); add("p", "muted", "Find out whether a backup or verified recovery method is actually available.") }
        next.button("Check recovery →", "button primary", "incident-next-recovery") { reviewed = true; navigate("recovery") }
    }
}

private fun renderRecovery(body: HTMLElement) {
    heading(body, "CHECK RECOVERY", "Find the next recovery step.", "First confirm that a usable copy or supported recovery method exists. Event records alone cannot establish that.")
    renderJourney(body, analyzed())
    val notice = body.add("div", "workspace-banner")
    notice.add("div") { add("h2", text = "Continue recovery in the desktop app"); add("p", text = "Open the desktop app's Recovery page to check its pre-change snapshot, restore into a new folder, and verify the file hashes. The browser can review events, but cannot restore your files.") }
    notice.badge("Desktop step", "teal")
    val eventsByPath = events.take(position).filter { it.kind != "OVERFLOW" && it.path.isNotBlank() }.groupBy { it.path }
    val stats = body.add("div", "stats recovery-stats")
    listOf("Paths to review" to eventsByPath.size.toString(), "Verified backups here" to "0", "Verified decryptions here" to "0").forEach { (key, value) -> stats.add("div", "stat") { add("span", "stat-label", key); add("strong", "stat-value", value) } }
    val panel = body.add("section", "panel")
    panel.add("h2", text = "Per-file readiness map")
    panel.add("p", "muted", "Statuses below describe the imported or synthetic event evidence only. They are not estimates of recovery probability.")
    if (eventsByPath.isEmpty()) panel.add("div", "empty-state") { add("p", text = "No changed paths to review yet."); button("Run a practice replay", "button primary", "recovery-start-replay") { navigate("overview") } }
    else {
        val wrap = panel.add("div", "table-wrap")
        val table = wrap.add("table", "readiness-table")
        table.add("thead") { add("tr") { listOf("Path", "Snapshot evidence", "Key recovery", "Next step").forEach { add("th", text = it).setAttribute("scope", "col") } } }
        val rows = table.add("tbody")
        eventsByPath.keys.take(150).forEach { path -> rows.add("tr") { add("td", "detail-path", path); add("td") { badge("No backup evidence", "neutral") }; add("td") { badge("Not verified", "neutral") }; add("td", text = "Inspect desktop snapshot or a supported recovery artifact") } }
    }
    val cards = body.add("div", "two-columns")
    cards.add("section", "panel") { add("h3", text = "1. Look for a pre-change copy"); add("p", text = "In the desktop lab, create the snapshot before starting the worker. Restore to a new folder and check the actual verification result."); add("small", "muted", "Same-user local copies are not tamper-proof or offline backups.") }
    cards.add("section", "panel") { add("h3", text = "2. Explore the optional memory experiment"); add("p", text = "Try a generated example to see when the supported scanner can, or cannot, identify a key structure. This does not recover your files."); button("Try memory lab →", "button secondary", "recovery-memory") { navigate("memory") } }
}

private fun renderMemory(body: HTMLElement) {
    heading(body, "OPTIONAL EXPERIMENT", "See when a key can be found.", "Choose a generated example and run the scanner. Then try an erased example to see an honest negative result.")
    val caution = body.add("div", "notice-strip")
    caution.badge("Synthetic memory only", "amber")
    caution.add("span", text = "  This experiment does not read your RAM or restore your files.")
    val grid = body.add("div", "two-columns")
    val inputs = grid.add("section", "panel")
    inputs.add("h2", text = "1. Choose an example")
    inputs.select("memory-variant", "Generated example", listOf("present" to "Key structure present · start here", "erased" to "Key structure erased", "raw-only" to "Raw key only · unsupported", "corrupt" to "Key structure damaged"), memoryVariant) { memoryVariant = it; memoryResult = null; errorMessage = null; renderBody() }
    val seedLabel = inputs.add("label", "field")
    seedLabel.add("span", "field-label", "Reproducible seed")
    val seed = el("input") as HTMLInputElement
    seed.type = "number"
    seed.id = "memory-seed"
    seed.min = "1"
    seed.max = "1000000"
    seed.value = memorySeed.toString()
    seed.onfocus = { if (running) { stop(); renderBody() }; Unit }
    seed.onchange = {
        val value = seed.value.toIntOrNull()
        if (value == null || value !in 1..1_000_000) { errorMessage = "Choose a whole-number seed between 1 and 1,000,000."; renderBody() }
        else { memorySeed = value; errorMessage = null }
        Unit
    }
    seedLabel.appendChild(seed)
    inputs.button(if (memoryBusy) "Scanning example…" else "2. Run scan", "button primary", "run-memory", memoryBusy) {
        memoryBusy = true
        errorMessage = null
        renderBody()
        window.setTimeout({
            try { memoryResult = MemoryLab.run(seed = memorySeed, variant = memoryVariant) }
            catch (error: Throwable) { errorMessage = "Memory fixture failed: ${error.message ?: "unknown error"}" }
            memoryBusy = false
            renderBody()
        }, 20)
    }
    inputs.add("p", "muted", "Keep the same seed when comparing examples. It makes the generated test repeatable.")
    val results = grid.add("section", "panel")
    results.add("h2", text = "3. Read the result")
    val result = memoryResult
    if (result == null) results.add("div", "empty-state") { add("h3", text = "Your result will appear here"); add("p", text = "Start with Key structure present, then run the erased example to compare.") }
    else {
        results.add("h3", text = if (result.verified > 0) "A supported key structure was verified" else "No supported key structure was verified")
        results.badge(result.status, if (result.verified > 0) "teal" else "amber")
        val stats = results.add("div", "memory-stats")
        listOf("Scanned" to countLabel(result.scannedBytes.toLong()), "Candidates" to result.candidates.toString(), "Verified" to result.verified.toString()).forEach { (key, value) -> stats.add("div") { add("strong", text = value); add("span", text = key) } }
        results.add("p", text = if (result.verified > 0) "The scanner found the expected structure and checked it against this example's encrypted sample. Next, try the erased example." else "The required structure was absent or did not validate. This is a supported-method limit, not proof that every recovery option is impossible.")
        val details = results.disclosure("Technical scanner result", memoryDetailsOpen) { memoryDetailsOpen = it }
        details.add("p", text = result.detail)
        if (result.keyPreview.isNotBlank()) details.add("p", "mono key-preview", "Key preview: ${result.keyPreview}")
        details.add("small", "muted", "Verification is limited to the generated fixture and sample ciphertext. It does not establish live RAM acquisition, a real-family decryptor, or recovery of user files.")
    }
    val steps = body.add("section", "panel")
    steps.add("h2", text = "What this experiment teaches")
    val list = steps.add("ol", "method-steps")
    list.add("li", text = "Look for an AES-128 expanded-key schedule, not merely random-looking bytes.")
    list.add("li", text = "Reconstruct a candidate and validate it against the fixture's encrypted sample.")
    list.add("li", text = "Report no supported candidate when the structure is absent, erased, or unsupported.")
    steps.add("p", "muted", "A raw random key has no universal signature. Scanning a real process, acquiring memory at the right time, and handling different ciphers are separate engineering and forensic tasks.")
}

private fun renderMethods(body: HTMLElement) {
    heading(body, "METHODS & LIMITS", "Useful evidence. Honest boundaries.", "RansomScope is an experimental analysis and recovery-readiness tool, not a universal ransomware vaccine or a replacement for endpoint protection.")
    val grid = body.add("div", "two-columns")
    grid.add("section", "panel") {
        add("h2", text = "What is shared")
        add("p", text = "The JVM desktop and this browser compile the same commonMain Kotlin event model, detection engine, explanations, scenario fixtures, and AES memory-lab scanner.")
        add("h3", text = "What is platform-specific")
        add("p", text = "Only the desktop application watches a selected folder, creates pre-test snapshots and restores files. The browser imports event JSON and runs analysis locally.")
    }
    grid.add("section", "panel") {
        add("h2", text = "Collection & response")
        add("p", text = "Directory notifications can be incomplete and do not reliably identify the writer. Unknown attribution must remain unknown. The desktop lab can cooperatively pause only its own test worker.")
        add("p", text = "An overflow or reported collection gap changes coverage to incomplete. Missing events never establish that no attack occurred.")
    }
    grid.add("section", "panel") {
        add("h2", text = "Interpreting alerts")
        add("p", text = "Compression, builds, bulk rename and legitimate encryption can resemble suspicious activity. Compare baselines using the same evidence. A combined rule is a heuristic, not a calibrated probability.")
        add("p", text = "Scenario labels are for evaluation, never detection inputs. Synthetic demonstrations are not real-malware benchmarks.")
    }
    grid.add("section", "panel") {
        add("h2", text = "Local by design")
        add("p", text = "Imports are processed in browser memory. There is no upload service, account, analytics or third-party font request. Exports are local downloads.")
        add("p", text = "Event paths can still reveal private filenames. Review and redact desktop exports before sharing them with anyone.")
    }
    val evidence = body.add("section", "panel")
    evidence.add("h2", text = "Research foundations")
    val links = listOf(
        Triple("ShieldFS · ACSAC 2016", "File-system activity and copy-on-write recovery; not equivalent to a directory watcher.", "https://trustial.org/publications/continella_shieldfs_2016/"),
        Triple("Redemption · RAID 2017", "Preserving original files through mediated writes and transparent buffering.", "https://theseclab.org/publications/raid2017redemption.pdf"),
        Triple("PayBreak · ASIACCS 2017", "Proactive cryptographic key capture is distinct from post-incident memory scanning.", "https://discovery.ucl.ac.uk/id/eprint/1541227/"),
        Triple("ERW-Radar · NDSS 2025", "Context and content analysis address limitations of simplistic rate and entropy rules.", "https://www.ndss-symposium.org/wp-content/uploads/2025-349-paper.pdf")
    )
    links.forEach { (name, text, url) ->
        val row = evidence.add("div", "research-row")
        val link = row.add("a", text = name) as HTMLAnchorElement
        link.href = url
        link.target = "_blank"
        link.rel = "noopener noreferrer"
        row.add("p", "muted", text)
    }
    body.add("p", "fine-print", "Competition prototype · No real malware is included. Reported capabilities must be assessed against the source, tests and limitations shipped with this build.")
}

private fun chooseImport() { (document.getElementById("event-file") as HTMLInputElement).click() }

private fun importFile(input: HTMLInputElement) {
    val file = input.files?.item(0) ?: return
    if (file.size.toDouble() > 5 * 1024 * 1024) {
        errorMessage = "This file exceeds the 5 MB limit. Export a smaller, redacted event collection."
        renderBody()
        input.value = ""
        return
    }
    val reader: dynamic = js("new FileReader()")
    reader.onload = {
        try {
            val loaded = Codecs.decodeEvents(reader.result as String)
            require(loaded.size <= 20_000) { "Use at most 20,000 events." }
            loaded.forEach { event ->
                require(event.atMs >= 0 && event.atMs <= 9_007_199_254_740_991L) { "Event timestamps must be non-negative safe integers." }
                require(event.path.length <= 1024 && !event.path.any { it.code < 32 }) { "Paths must be at most 1,024 characters without control characters." }
                require(event.kind in setOf("CREATE", "ADD", "MODIFY", "WRITE", "DELETE", "REMOVE", "RENAME", "OVERFLOW", "RESCAN", "GAP", "ERROR")) { "Unsupported event kind: ${event.kind.take(40)}" }
                require(event.bytes >= 0 && event.bytes <= 9_007_199_254_740_991L) { "Byte counts must be non-negative safe integers." }
                listOf(event.entropyBefore, event.entropyAfter).forEach { value -> require(value == null || (value.isFinite() && value in 0.0..8.0)) { "Entropy must be finite and between 0 and 8." } }
                require(event.source.length <= 32 && (event.actor?.length ?: 0) <= 128 && event.attribution.length <= 64) { "Event metadata is too long." }
            }
            stop()
            events = loaded.sortedBy { it.atMs }
            title = "Imported collection"
            description = "${events.size} events from ${file.name.take(100)}. Start the replay to inspect the collection."
            imported = true
            position = 0
            selection = null
            reviewed = false
            route = "incident"
            errorMessage = null
            notice = "Your event collection is ready. Press Run replay to begin; nothing was uploaded."
            window.location.hash = "incident"
            renderBody()
        } catch (error: Throwable) {
            errorMessage = "${error.message?.take(200) ?: "The file is not a supported event collection."} Your previous collection has been kept. Choose another JSON export and try again."
            renderBody()
        }
        input.value = ""
    }
    reader.onerror = { errorMessage = "The selected file could not be read."; renderBody() }
    reader.readAsText(file)
}

private fun exportEvents() = download("ransomscope-events.json", Codecs.encodeEvents(events.take(position)))

private fun download(name: String, text: String) {
    val blob: dynamic = js("new Blob([text], {type: 'application/json;charset=utf-8'})")
    val url = window.asDynamic().URL.createObjectURL(blob) as String
    val anchor = document.createElement("a") as HTMLAnchorElement
    anchor.href = url
    anchor.download = name
    document.body!!.appendChild(anchor)
    anchor.click()
    anchor.remove()
    window.setTimeout({ window.asDynamic().URL.revokeObjectURL(url) }, 1000)
}

fun main() {
    fun currentRoute(): String = window.location.hash.removePrefix("#").takeIf { it in setOf("overview", "incident", "recovery", "memory", "methods") } ?: "overview"
    route = currentRoute()
    renderShell()
    window.onhashchange = { route = currentRoute(); renderBody(); window.scrollTo(0.0, 0.0); Unit }
    if (window.location.search.contains("demo=1")) window.setTimeout({ start() }, 650)
}
