package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineTriagePortTest {
    @Test fun diagnosisReportsTheAllocatedPortInsteadOfAssuming3080() {
        val diagnosis = EngineTriage.diagnose(
            EngineTriage.Facts(
                enginePort = 3180,
                portInUse = true,
                portOwners = emptyMap(),
            ),
        )
        assertEquals(EngineTriage.Cause.PORT_UNKNOWN_OWNER, diagnosis.cause)
        assertEquals(true, diagnosis.evidence.any { it == "port 3180 is listening" })
        assertEquals(true, diagnosis.summary.contains("3180"))
    }
}
