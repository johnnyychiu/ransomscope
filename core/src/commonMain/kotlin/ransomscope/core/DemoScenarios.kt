package ransomscope.core

/** Reproducible teaching traces. Labels stay outside detector inputs. */
object DemoScenarios {
    private fun event(
        time: Long, path: String, kind: String = "MODIFY", before: Double? = 2.5,
        after: Double? = 7.8, canary: Boolean = false, actor: String? = null,
    ) = FileEvent(time, path, kind, 4_096, before, after, canary, "synthetic", actor,
        if (actor == null) "unknown" else "provided-by-replay")

    fun all(): List<Scenario> = listOf(
        Scenario("burst", "Rapid file changes", "Synthetic burst of content transformations. Review the evidence before deciding what it means.",
            (0..11).map { event(it * 160L, "documents/report-${it + 1}.txt", canary = it == 3) }, "suspicious-synthetic"),
        Scenario("compression", "Normal archive creation", "A benign archive has high entropy, but new output is not an observed before/after encryption transition.",
            (0..7).map { event(it * 130L, "archives/photos-$it.zip", "CREATE", null, 7.9) }, "benign"),
        Scenario("rename", "Bulk rename", "Renaming many files exercises the rate-only false-positive baseline without content changes.",
            (0..9).map { event(it * 120L, "photos/holiday-$it.jpg", "RENAME", 7.6, 7.6) }, "benign"),
        Scenario("editing", "Everyday editing", "Ordinary low-rate edits with modest content changes.",
            (0..7).map { event(it * 3_000L, "notes/day-$it.txt", before = 3.4, after = 3.6) }, "benign"),
        Scenario("legitimate-encryption", "Authorized encryption", "An authorized encryption job can resemble an attack. The combined rule may alert: user intent is not observable from these events.",
            (0..6).map { event(it * 220L, "private/tax-$it.txt") }, "benign-ambiguous"),
        Scenario("slow", "Slow changes", "Synthetic changes separated beyond the default window demonstrate a rate-window blind spot.",
            (0..7).map { event(it * 4_000L, "documents/slow-$it.txt") }, "suspicious-synthetic"),
        Scenario("partial", "Partial content changes", "Small entropy shifts without a canary demonstrate another limitation; no alert does not establish safety.",
            (0..9).map { event(it * 180L, "documents/large-$it.bin", before = 5.2, after = 5.7) }, "suspicious-synthetic"),
        Scenario("multi-source", "Multiple observed actors", "Synthetic replay groups file evidence across actors. Actor fields come from the trace, not an ordinary folder watcher.",
            (0..9).map { event(it * 150L, "documents/shared-$it.txt", actor = "trace-worker-${it % 3}") }, "suspicious-synthetic"),
        Scenario("overflow", "Missing telemetry", "An overflow breaks the evidence chain. Recovery readiness must show incomplete evidence.",
            listOf(event(0, "documents/first.txt"), FileEvent(100, "", "OVERFLOW", source = "synthetic"),
                event(200, "documents/second.txt")), "incomplete"),
    )
}
