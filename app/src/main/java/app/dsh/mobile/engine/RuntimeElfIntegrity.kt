package app.dsh.mobile.engine

import java.io.File

/**
 * Shared ELF validation for the runtime installer and self-check.
 * A present-but-empty .so is not a usable dependency and must never count as
 * a complete runtime. expectedMachine <= 0 skips only the ABI comparison.
 */
internal object RuntimeElfIntegrity {

    fun inspect(file: File, expectedMachine: Int): String? {
        if (!file.isFile) return "missing"
        val header = runCatching {
            file.inputStream().use { input ->
                val bytes = ByteArray(64)
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    if (count <= 0) break
                    offset += count
                }
                if (offset == bytes.size) bytes else bytes.copyOf(offset)
            }
        }.getOrElse { return "unreadable: ${it.javaClass.simpleName}" }
        return inspectHeader(header, file.length(), expectedMachine)
    }

    internal fun inspectHeader(header: ByteArray, fileSize: Long, expectedMachine: Int): String? {
        if (fileSize < 64L || header.size < 64) return "file too small"
        if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) return "not ELF"
        if (header[4].toInt() != 2) return "not ELF64"
        if ((header[16].toInt() and 0xff) != 3 || header[17].toInt() != 0) return "not ET_DYN shared object"
        if (header[5].toInt() != 1) return "not little-endian ELF"
        val actualMachine = (header[18].toInt() and 0xff) or
            ((header[19].toInt() and 0xff) shl 8)
        if (expectedMachine > 0 && actualMachine != expectedMachine) {
            return "wrong ABI e_machine=$actualMachine expected=$expectedMachine"
        }
        return null
    }
}
