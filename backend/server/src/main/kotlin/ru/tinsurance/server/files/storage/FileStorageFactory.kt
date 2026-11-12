package ru.tinsurance.server.files.storage

import io.ktor.server.config.ApplicationConfig
import ru.tinsurance.server.common.string
import ru.tinsurance.server.files.FileStorage
import ru.tinsurance.server.files.FilesConfig
import ru.tinsurance.server.files.LocalFileStorage

object FileStorageFactory {
    fun create(config: ApplicationConfig, files: FilesConfig): FileStorage =
        when (config.string("storage.provider", "local")) {
            "local" -> LocalFileStorage(files.storageDir)
            "s3" -> S3FileStorage(S3Config.from(config))
            else -> error("STORAGE_PROVIDER: допустимо local или s3")
        }
}
