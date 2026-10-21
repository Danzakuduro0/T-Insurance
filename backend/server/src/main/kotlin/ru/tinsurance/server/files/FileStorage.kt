package ru.tinsurance.server.files

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Хранилище содержимого файлов. В БД (claim_files) — только метаданные и ключ.
 * Сейчас — локальный диск (DEC-010); реализация под S3 добавится позже за этим же интерфейсом (DEC-011).
 */
interface FileStorage {
    fun put(key: String, content: ByteArray)
    fun open(key: String): InputStream
    fun delete(key: String)
}

/**
 * Файлы на диске сервера в каталоге [root]. Ключ генерирует только сервер;
 * дополнительно проверяем, что путь не выходит за пределы каталога (защита от `../`).
 */
class LocalFileStorage(root: Path) : FileStorage {
    private val root: Path = root.toAbsolutePath().normalize().also { Files.createDirectories(it) }

    override fun put(key: String, content: ByteArray) {
        val target = resolve(key)
        Files.createDirectories(target.parent)
        // сначала во временный файл рядом, затем атомарное переименование — без «половинчатых» файлов
        val tmp = Files.createTempFile(target.parent, ".upload-", ".tmp")
        try {
            Files.write(tmp, content)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override fun open(key: String): InputStream = Files.newInputStream(resolve(key))

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
    }

    private fun resolve(key: String): Path {
        require(KEY_PATTERN.matches(key)) { "Недопустимый ключ файла" }
        val path = root.resolve(key).normalize()
        require(path.startsWith(root)) { "Ключ файла выходит за пределы хранилища" }
        return path
    }

    private companion object {
        val KEY_PATTERN = Regex("""^[a-z0-9]+(/[a-z0-9-]+)*\.[a-z0-9]{2,4}$""")
    }
}
