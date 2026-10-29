package ransomscope.desktop

import ransomscope.core.FileEvent
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BackendTest {
    @Test
    fun packagedWorkerUsesItsOwnLauncherWithoutRequiringRuntimeJava() {
        val root = Files.createTempDirectory("ransomscope-launch-test-")
        val launcher = Files.createFile(root.resolve("RansomScope.exe"))
        val command = workerLaunchCommand(root, "test-token", launcher.toString(), null,
            root.resolve("missing-runtime").toString(), "unused", true)
        assertEquals(listOf(launcher.toString(), "--worker", root.toString(), "test-token"), command)
        val fallback = workerLaunchCommand(root, "test-token", null, launcher.toString(),
            root.resolve("missing-runtime").toString(), "unused", true)
        assertEquals(command, fallback)
        val unrelated = Files.createFile(root.resolve("unrelated.exe"))
        assertFails {
            workerLaunchCommand(root, "test-token", null, unrelated.toString(),
                root.resolve("missing-runtime").toString(), "unused", true)
        }
    }

    @Test
    fun sourceWorkerUsesTheJavaClasspathLauncher() {
        val root = Files.createTempDirectory("ransomscope-source-launch-test-")
        val javaHome = Files.createDirectory(root.resolve("jdk"))
        val bin = Files.createDirectory(javaHome.resolve("bin"))
        val java = Files.createFile(bin.resolve("java.exe"))
        assertEquals(listOf(java.toString(), "-cp", "test-classpath", "ransomscope.desktop.MainKt", "--worker", root.toString(), "test-token"),
            workerLaunchCommand(root, "test-token", null, null, javaHome.toString(), "test-classpath", true))
    }

    @Test
    fun snapshotRestorePreservesOriginalEvidenceAndUsesFreshDirectories() {
        val lab = DemoLab.create()
        val changed = lab.data.resolve(lab.entries.first().name)
        Files.writeString(changed, "Changed disposable evidence", StandardOpenOption.TRUNCATE_EXISTING)
        val alteredHash = sha256(changed)
        val first = lab.restore()
        val second = lab.restore()
        assertEquals(24, first.verified)
        assertNotEquals(first.directory, second.directory)
        assertNotEquals(first.directory, lab.data)
        assertEquals(alteredHash, sha256(changed), "Restore must not overwrite the incident evidence")
        assertEquals(lab.entries.first().hash, sha256(first.directory.resolve(lab.entries.first().name)))
    }

    @Test
    fun tamperedSnapshotAndForgedWorkerTokenAreRejected() {
        val lab = DemoLab.create()
        assertFails { DemoLab.reopenForWorker(lab.home, "forged-token") }
        Files.writeString(lab.snapshot.resolve(lab.entries.first().name), "tampered", StandardOpenOption.TRUNCATE_EXISTING)
        assertFails { lab.restore() }
        Files.list(lab.home).use { stream -> assertFalse(stream.anyMatch { it.fileName.toString().startsWith("restored-") }) }
    }

    @Test
    fun passiveWatcherDoesNotInventPidOrCanaryDesignation() {
        val root = Files.createTempDirectory("ransomscope-monitor-test-")
        val file = root.resolve("canary-not-designated.txt")
        Files.writeString(file, "original")
        val events = CopyOnWriteArrayList<FileEvent>()
        FolderMonitor(root, "live", { events += it }, {}).use { monitor ->
            monitor.start()
            assertEquals("original", Files.readString(file), "Starting a passive monitor must not change files")
            Files.writeString(file, "normal edit", StandardOpenOption.TRUNCATE_EXISTING)
            await { events.any { it.path == file.fileName.toString() } }
            val changed = events.filter { it.path == file.fileName.toString() }
            assertTrue(changed.isNotEmpty())
            assertTrue(changed.all { it.actor == null && it.attribution == "unknown" })
            assertTrue(changed.none { it.canary }, "A canary-like filename alone is not a designation")
            assertEquals("normal edit", Files.readString(file))
        }
    }

    @Test
    fun newlyArrivingDirectoryIsRegisteredAndCoverageGapDisclosed() {
        val root = Files.createTempDirectory("ransomscope-tree-test-")
        val events = CopyOnWriteArrayList<FileEvent>()
        FolderMonitor(root, "live", { events += it }, {}).use { monitor ->
            monitor.start()
            val nested = Files.createDirectories(root.resolve("arriving/subtree"))
            await { events.any { it.kind == "OVERFLOW" } }
            val file = nested.resolve("sample.txt")
            Files.writeString(file, "new nested document")
            await { events.any { it.path.endsWith("sample.txt") } }
            assertTrue(monitor.coverageIncomplete)
            assertTrue(events.any { it.path.endsWith("sample.txt") })
        }
    }

    @Test
    fun refusedSpecialPathDoesNotTraverseIt() {
        val root = Files.createTempDirectory("ransomscope-link-test-")
        val other = Files.createTempDirectory("ransomscope-link-target-")
        val link = root.resolve("external")
        // Windows may not grant unprivileged symlink creation. If available, verify the rejection.
        val created = runCatching { Files.createSymbolicLink(link, other); true }.getOrDefault(false)
        if (created) {
            assertFails { requireSafePath(link.resolve("outside.txt")) }
            assertFalse(Files.exists(other.resolve("outside.txt")))
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 4000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue(condition(), "Timed out waiting for a real WatchService event")
    }
}
