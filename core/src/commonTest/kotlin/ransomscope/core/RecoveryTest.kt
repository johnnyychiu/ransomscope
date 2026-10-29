package ransomscope.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecoveryTest {
    @Test fun snapshotManifestRoundTripsWithoutClaimingAuthenticity() {
        val manifest = SnapshotManifest(100, listOf(SnapshotEntry("a.txt", 4, "a".repeat(64))))
        manifest.validateSchema()
        val serialized = Codecs.json.encodeToString(SnapshotManifest.serializer(), manifest)
        val decoded = Codecs.json.decodeFromString(SnapshotManifest.serializer(), serialized)
        assertEquals(manifest, decoded)
        decoded.validateSchema()
    }

    @Test fun manifestRejectsTraversalDuplicatesAndUnknownVersion() {
        val entry = SnapshotEntry("a.txt", 1, "f".repeat(64))
        assertFailsWith<IllegalArgumentException> { SnapshotManifest(1, listOf(entry.copy(name = "../outside"))).validateSchema() }
        assertFailsWith<IllegalArgumentException> { SnapshotManifest(1, listOf(entry, entry)).validateSchema() }
        assertFailsWith<IllegalArgumentException> { SnapshotManifest(1, listOf(entry), schemaVersion = 2).validateSchema() }
        assertFailsWith<IllegalArgumentException> { SnapshotManifest(1, listOf(entry.copy(hash = "not-a-hash"))).validateSchema() }
    }

    @Test fun importedVerificationFlagsAndSourceStringDoNotPromoteClaims() {
        val json = """{"path":"a.txt","snapshotClaimed":true,"restoreClaimed":true,"keyRecoveryClaimed":true,"metadataComplete":true,"verificationSource":"local-verification"}"""
        val imported = Codecs.json.decodeFromString(RecoveryDeclaration.serializer(), json)
        assertEquals(RecoveryState.UNSUPPORTED, RecoveryReadiness.assessDeclaration(imported).state)
        assertEquals(RecoveryState.EVIDENCE_INCOMPLETE, RecoveryReadiness.assessDeclaration(imported.copy(metadataComplete = false)).state)
    }

    @Test fun localChecksProduceEvidenceStatesAndKeepCoverageLimits() {
        assertEquals(RecoveryState.UNSUPPORTED, RecoveryReadiness.assessLocal("a.txt", LocalRecoveryChecks()).state)
        assertEquals(RecoveryState.PRE_CHANGE_COPY, RecoveryReadiness.assessLocal("a.txt", LocalRecoveryChecks(snapshotHashMatches = true)).state)
        assertEquals(RecoveryState.KEY_METHOD_VERIFIED, RecoveryReadiness.assessLocal("a.txt", LocalRecoveryChecks(keyRecoveredFileHashMatches = true)).state)
        val restored = RecoveryReadiness.assessLocal("a.txt", LocalRecoveryChecks(restoredHashMatches = true, metadataComplete = false))
        assertEquals(RecoveryState.RESTORE_VERIFIED, restored.state)
        assertTrue(restored.reasons.any { it.contains("incomplete") })
    }
}
