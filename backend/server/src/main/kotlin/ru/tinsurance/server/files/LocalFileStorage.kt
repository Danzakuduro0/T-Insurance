package ru.tinsurance.server.files

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
        requireValidStorageKey(key)
        val path = root.resolve(key).normalize()
        require(path.startsWith(root)) { "Ключ файла выходит за пределы хранилища" }
        return path
    }
}
