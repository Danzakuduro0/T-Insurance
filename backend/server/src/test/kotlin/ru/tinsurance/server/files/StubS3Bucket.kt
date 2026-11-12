package ru.tinsurance.server.files

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import ru.tinsurance.server.TestDatabase
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class StubS3Bucket : AutoCloseable {
    val objects = ConcurrentHashMap<String, ByteArray>()
    val reads = AtomicInteger()
    @Volatile var deniedMethod: String? = null

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/test-bucket/", ::handle)
        start()
    }

    fun config() = TestDatabase.config(
        "storage.provider" to "s3",
        "storage.s3.endpoint" to "http://127.0.0.1:${server.address.port}",
        "storage.s3.bucket" to "test-bucket",
        "storage.s3.accessKeyId" to "fake-access-key",
        "storage.s3.secretAccessKey" to SECRET,
    )

    private fun handle(exchange: HttpExchange) = exchange.use { request ->
        val key = request.requestURI.path.removePrefix("/test-bucket/")
        if (!request.requestHeaders.getFirst("Authorization").orEmpty().startsWith("AWS4-HMAC-SHA256 ") ||
            request.requestMethod == deniedMethod
        ) {
            request.error(403, "AccessDenied")
            return@use
        }
        when (request.requestMethod) {
            "PUT" -> {
                objects[key] = request.requestBody.use { it.readBytes() }
                request.sendResponseHeaders(200, -1)
            }
            "GET" -> {
                reads.incrementAndGet()
                val content = objects[key]
                if (content == null) {
                    request.error(404, "NoSuchKey")
                } else {
                    request.sendResponseHeaders(200, content.size.toLong())
                    request.responseBody.write(content)
                }
            }
            "DELETE" -> {
                objects.remove(key)
                request.sendResponseHeaders(204, -1)
            }
            else -> request.sendResponseHeaders(405, -1)
        }
    }

    private fun HttpExchange.error(status: Int, code: String) {
        val body = "<Error><Code>$code</Code><Message>$SECRET</Message></Error>".encodeToByteArray()
        responseHeaders.set("Content-Type", "application/xml")
        sendResponseHeaders(status, body.size.toLong())
        responseBody.write(body)
    }

    override fun close() = server.stop(0)

    companion object {
        const val SECRET = "test-s3-secret-never-in-api-response"
    }
}
