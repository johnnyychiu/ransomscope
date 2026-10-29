package ransomscope.core

import kotlinx.serialization.Serializable

/** Informational manifest. Its schema and timestamp do not authenticate origin or custody. */
@Serializable
data class SnapshotEntry(val name: String, val size: Long, val hash: String)

@Serializable
data class SnapshotManifest(
    val createdAtMs: Long,
    val entries: List<SnapshotEntry>,
    val schemaVersion: Int = 1,
    val scope: String = "lab-pre-change",
) {
    fun validateSchema() {
        require(schemaVersion == 1) { "Unsupported snapshot manifest version." }
        require(scope == "lab-pre-change") { "Unsupported manifest scope." }
        require(createdAtMs in 0..9_007_199_254_740_991L) { "Invalid manifest timestamp." }
        require(entries.size in 1..10_000) { "Manifest must contain 1 to 10,000 files." }
        require(entries.map { it.name }.distinct().size == entries.size) { "Duplicate manifest path." }
        entries.forEach {
            requireSafeRelativeName(it.name)
            require(it.size in 0..100_000_000_000L) { "Invalid declared file size." }
            require(it.hash.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid SHA-256 declaration." }
        }
    }
}

@Serializable
enum class RecoveryState {
    PRE_CHANGE_COPY,
    RESTORE_VERIFIED,
    KEY_METHOD_VERIFIED,
    UNSUPPORTED,
    EVIDENCE_INCOMPLETE,
}

/** Imported claims remain claims, even if their author sets every verification flag. */
@Serializable
data class RecoveryDeclaration(
    val path: String,
    val snapshotClaimed: Boolean = false,
    val restoreClaimed: Boolean = false,
    val keyRecoveryClaimed: Boolean = false,
    val metadataComplete: Boolean = true,
    val verificationSource: String = "unverified",
)

/**
 * In-process verifier results, deliberately NOT serializable. Only application code that has
 * performed actual local comparisons should construct these. Never map imported claims here.
 * This type is a trust boundary in application design, not a cryptographic attestation.
 */
data class LocalRecoveryChecks(
    val snapshotHashMatches: Boolean = false,
    val restoredHashMatches: Boolean = false,
    val keyRecoveredFileHashMatches: Boolean = false,
    val metadataComplete: Boolean = true,
)

@Serializable
data class FileReadiness(val path: String, val state: RecoveryState, val reasons: List<String>)

object RecoveryReadiness {
    /** No imported field, including a source string, can promote a declaration to verified. */
    fun assessDeclaration(declaration: RecoveryDeclaration): FileReadiness {
        requireSafeRelativeName(declaration.path)
        return FileReadiness(
            declaration.path,
            if (declaration.metadataComplete) RecoveryState.UNSUPPORTED else RecoveryState.EVIDENCE_INCOMPLETE,
            listOf("Imported declarations are not authenticated or independently verified. No backup or decryption status is promoted from these flags."),
        )
    }

    /** Caller supplies results of actual local verification, never user-entered status claims. */
    fun assessLocal(path: String, checks: LocalRecoveryChecks): FileReadiness {
        requireSafeRelativeName(path)
        val state = when {
            checks.restoredHashMatches -> RecoveryState.RESTORE_VERIFIED
            checks.keyRecoveredFileHashMatches -> RecoveryState.KEY_METHOD_VERIFIED
            checks.snapshotHashMatches -> RecoveryState.PRE_CHANGE_COPY
            !checks.metadataComplete -> RecoveryState.EVIDENCE_INCOMPLETE
            else -> RecoveryState.UNSUPPORTED
        }
        val reasons = buildList {
            when (state) {
                RecoveryState.RESTORE_VERIFIED -> add("A locally restored file matched its recorded pre-change hash.")
                RecoveryState.KEY_METHOD_VERIFIED -> add("The recovered file, not merely a sample block, matched its recorded pre-change hash.")
                RecoveryState.PRE_CHANGE_COPY -> add("The local pre-change copy matched the recorded hash at verification time.")
                RecoveryState.EVIDENCE_INCOMPLETE -> add("The local verification evidence is incomplete.")
                RecoveryState.UNSUPPORTED -> add("No supported recovery path has been locally verified for this file.")
            }
            if (!checks.metadataComplete) add("Other incident observations are incomplete; a verified copy does not establish complete incident coverage.")
            add("Hash equality does not establish that the original file was safe, current, or protected against future tampering.")
        }
        return FileReadiness(path, state, reasons)
    }
}

private fun requireSafeRelativeName(value: String) {
    require(value.isNotBlank() && value.length <= 4_096 && !value.contains('\u0000')) { "Invalid relative file path." }
    require(!value.startsWith('/') && !value.startsWith('\\') && !value.contains(':')) { "Absolute paths are not allowed." }
    require(value.replace('\\', '/').split('/').none { it == ".." || it.isBlank() }) { "Unsafe relative file path." }
}
