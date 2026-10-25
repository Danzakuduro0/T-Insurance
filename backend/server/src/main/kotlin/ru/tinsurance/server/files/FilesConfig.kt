package ru.tinsurance.server.files

import io.ktor.server.config.*
import ru.tinsurance.server.common.int
import ru.tinsurance.server.common.long
import ru.tinsurance.server.common.stringOrNull
import java.nio.file.Path

data class FilesConfig(
    val storageDir: Path,
    val maxFilesPerClaim: Int,
    val maxTotalBytesPerClaim: Long,
    /** Защита от «бомб»: огромное число пикселей при маленьком файле. */
    val maxImagePixels: Long,
    /** Сколько загрузок одновременно проверяются в памяти; остальные ждут. */
    val maxConcurrentProcessing: Int,
    /** До скольких различающихся бит перцептивного хэша фото считаются одним снимком. */
    val phashMaxDistance: Int,
) {
    companion object {
        fun from(config: ApplicationConfig) = FilesConfig(
            storageDir = config.stringOrNull("storage.localDir")?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".tstrah", "files"),
            maxFilesPerClaim = config.int("files.maxFilesPerClaim", 15),
            maxTotalBytesPerClaim = config.long("files.maxTotalBytesPerClaim", 100L * 1024 * 1024),
            maxImagePixels = config.long("files.maxImagePixels", 50_000_000L),
            maxConcurrentProcessing = config.int("files.maxConcurrentProcessing", 4),
            phashMaxDistance = config.int("files.phashMaxDistance", 5),
        )
    }
}
