package ru.tinsurance.server.files.storage

import ru.tinsurance.server.files.FileStorage
import ru.tinsurance.server.files.requireValidStorageKey
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.IOException
import java.io.InputStream
import java.nio.file.NoSuchFileException

class S3FileStorage private constructor(private val client: S3Client, private val bucket: String) : FileStorage {
    constructor(config: S3Config) : this(S3ClientFactory.create(config), config.bucket)

    override fun put(key: String, content: ByteArray) = execute("PUT", key) {
        val body = RequestBody.fromContentProvider({ content.inputStream() }, content.size.toLong(), "application/octet-stream")
        client.putObject({ it.bucket(bucket).key(key) }, body)
        Unit
    }

    override fun open(key: String): InputStream = execute("GET", key) {
        try {
            client.getObject { it.bucket(bucket).key(key) }
        } catch (_: NoSuchKeyException) {
            throw NoSuchFileException(key)
        }
    }

    override fun delete(key: String) = execute("DELETE", key) {
        client.deleteObject { it.bucket(bucket).key(key) }
        Unit
    }

    override fun close() = client.close()

    private fun <T> execute(operation: String, key: String, action: () -> T): T {
        requireValidStorageKey(key)
        return try {
            action()
        } catch (e: S3Exception) {
            throw IOException("S3 $operation: HTTP ${e.statusCode()}")
        } catch (_: SdkException) {
            throw IOException("S3 $operation: не удалось выполнить запрос")
        }
    }
}
