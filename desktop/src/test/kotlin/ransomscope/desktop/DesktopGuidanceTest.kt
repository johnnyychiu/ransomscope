package ransomscope.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopGuidanceTest {
    @Test
    fun newUserHasOneCreateThenStartAction() {
        assertEquals("CREATE", demoGuidance(false, false, false, false, false).action)
        assertEquals("START", demoGuidance(true, false, false, false, false).action)
        assertEquals("WAIT", demoGuidance(true, true, false, false, false, true).action)
    }

    @Test
    fun anAlertMustLeadToReviewThenRestore() {
        assertEquals("REVIEW", demoGuidance(true, false, true, false, false, true).action)
        assertEquals("RESTORE", demoGuidance(true, false, true, true, false, true).action)
    }

    @Test
    fun stoppedWorkerWithoutAlertDoesNotOfferAnImpossibleRestart() {
        assertEquals("REVIEW", demoGuidance(true, false, false, false, false, true).action)
        assertEquals("RESTORE", demoGuidance(true, false, false, true, false, true).action)
    }

    @Test
    fun verifiedRestoreIsTerminalEvenIfAnOldRunningFlagRemains() {
        val result = demoGuidance(true, true, true, true, true, true)
        assertEquals(5, result.stage)
        assertEquals("OPEN", result.action)
        assertEquals("Open restored folder", result.button)
    }

    @Test
    fun switchingAwayFromALabRequiresANewSafeDemo() {
        assertEquals("CREATE", demoGuidance(false, false, true, true, false, true).action)
    }
}
