package ransomscope.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    @Test fun burstAlertsButCompressionAndRenameDoNotProduceCombinedAlerts() {
        assertNotNull(analyze("burst").firstAlertAtMs)
        assertNull(analyze("compression").firstAlertAtMs)
        assertNull(analyze("rename").firstAlertAtMs)
        assertNull(analyze("editing").firstAlertAtMs)
    }

    @Test fun baselineFalsePositivesAndBlindSpotsAreVisible() {
        assertNotNull(analyze("rename", "rate").firstAlertAtMs)
        assertNotNull(analyze("legitimate-encryption").firstAlertAtMs)
        assertNull(analyze("slow").firstAlertAtMs)
        assertNull(analyze("partial").firstAlertAtMs)
        assertNotNull(analyze("multi-source").firstAlertAtMs)
    }

    @Test fun duplicateNotificationsDoNotInflateCountsOrSizes() {
        val event = FileEvent(100, "a.txt", bytes = 500, source = "synthetic")
        val result = Engine.analyze(listOf(event, event, event.copy(atMs = 101, bytes = 300)))
        assertEquals(1, result.changedFiles)
        assertEquals(500L, result.changedBytes)
    }

    @Test fun orderingDoesNotChangeResults() {
        val events = DemoScenarios.all().first().events
        assertEquals(Engine.analyze(events), Engine.analyze(events.reversed()))
    }

    @Test fun overflowIsDegradedAndNotAChangedFile() {
        val result = analyze("overflow")
        assertTrue(result.degraded)
        assertEquals(2, result.changedFiles)
        assertEquals("degraded", result.decisions.first().level)
        assertTrue(result.summary.contains("Incomplete"))
    }

    @Test fun unknownWriterIsNotPromotedToProcessAttribution() {
        val result = analyze("burst")
        assertTrue(result.decisions.first().reasons.any { it.contains("identity is unknown") })
    }

    @Test fun noCrossGapEvidenceCombination() {
        val events = DemoScenarios.all().first().events.take(3) +
            FileEvent(400, "", "OVERFLOW", source = "synthetic") +
            FileEvent(500, "last.txt", bytes = 10, entropyBefore = 1.0, entropyAfter = 7.8, source = "synthetic")
        assertNull(Engine.analyze(events).firstAlertAtMs)
    }

    @Test fun strictImportsRejectUnknownFieldsAndInvalidValues() {
        assertFailsWith<Exception> { Codecs.decodeEvents("[{\"atMs\":0,\"path\":\"a\",\"attackLabel\":true}]") }
        assertFailsWith<IllegalArgumentException> { Engine.analyze(listOf(FileEvent(-1, "a"))) }
        assertFailsWith<IllegalArgumentException> { Engine.analyze(listOf(FileEvent(1, "a", entropyAfter = 9.0))) }
        assertFailsWith<IllegalArgumentException> { Engine.analyze(emptyList(), DetectionConfig(mode = "magic")) }
    }

    @Test fun eventsRoundTrip() {
        val events = DemoScenarios.all().first().events
        assertEquals(events, Codecs.decodeEvents(Codecs.encodeEvents(events)))
    }

    @Test fun entropyBoundaries() {
        assertEquals(0.0, ContentFeatures.entropy(ByteArray(100)))
        assertEquals(8.0, ContentFeatures.entropy(ByteArray(256) { it.toByte() }), 0.00000001)
    }

    @Test fun aesMatchesFips197KnownAnswer() {
        val key = hex("000102030405060708090a0b0c0d0e0f")
        val plain = hex("00112233445566778899aabbccddeeff")
        val cipher = Aes128.encryptBlock(plain, key)
        assertContentEquals(hex("69c4e0d86a7b0430d8cdb78070b4c55a"), cipher)
        assertEquals(176, Aes128.expandKey(key).size)
    }

    @Test fun memoryLabFindsRandomlyPlacedSchedulesAndRejectsNegatives() {
        listOf(1, 7, 42, 99).forEach { seed ->
            val result = MemoryLab.run(seed, "present")
            assertEquals("verified", result.status)
            assertEquals(1, result.verified)
        }
        listOf("erased", "raw-only", "corrupt").forEach { variant ->
            val result = MemoryLab.run(42, variant)
            assertEquals("not-found", result.status)
            assertEquals(0, result.verified)
        }
        assertTrue(MemoryLab.run().detail.contains("Synthetic"))
    }

    @Test fun memoryScannerRejectsNoiseAndSmallInputs() {
        assertTrue(MemoryLab.findExpandedKeys(ByteArray(175)).isEmpty())
        assertTrue(MemoryLab.findExpandedKeys(ByteArray(1_024)).isEmpty())
        assertFailsWith<IllegalArgumentException> { MemoryLab.run(variant = "unknown") }
        assertFailsWith<IllegalArgumentException> { MemoryLab.findExpandedKeys(ByteArray(1_048_577)) }
        assertFalse(MemoryLab.run().keyPreview.length >= 32)
    }

    private fun analyze(id: String, mode: String = "combined") = Engine.analyze(
        DemoScenarios.all().first { it.id == id }.events, DetectionConfig(mode = mode),
    )
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
