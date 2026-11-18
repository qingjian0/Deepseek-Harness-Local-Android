package app.dsh.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeElfIntegrityTest {

    private fun elf64(machine: Int): ByteArray = ByteArray(20).apply {
        this[0] = 0x7f.toByte()
        this[1] = 'E'.code.toByte()
        this[2] = 'L'.code.toByte()
        this[3] = 'F'.code.toByte()
        this[4] = 2
        this[5] = 1
        this[18] = (machine and 0xff).toByte()
        this[19] = ((machine ushr 8) and 0xff).toByte()
    }

    @Test fun zeroByteLibraryIsRejected() {
        assertEquals("file too small", RuntimeElfIntegrity.inspectHeader(ByteArray(0), 0, 183))
    }

    @Test fun truncatedLibraryIsRejected() {
        assertEquals("file too small", RuntimeElfIntegrity.inspectHeader(ByteArray(8), 8, 183))
    }

    @Test fun nonElfFileIsRejected() {
        assertEquals("not ELF", RuntimeElfIntegrity.inspectHeader(ByteArray(20), 20, 183))
    }

    @Test fun wrongAbiIsRejected() {
        assertEquals(
            "wrong ABI e_machine=62 expected=183",
            RuntimeElfIntegrity.inspectHeader(elf64(62), 4096, 183),
        )
    }

    @Test fun validArm64ElfIsAccepted() {
        assertNull(RuntimeElfIntegrity.inspectHeader(elf64(183), 4096, 183))
    }
}
