package ru.tinsurance.server.files

import org.jetbrains.exposed.v1.jdbc.Database
import ru.tinsurance.api.FileStatus
import ru.tinsurance.server.claims.ClaimRepository
import ru.tinsurance.server.common.NotFoundException
import ru.tinsurance.server.common.tx
import ru.tinsurance.server.db.ClaimFiles

/** Отдача содержимого файлов. */
class FileAccessService(private val db: Database, private val storage: FileStorage) {

    /** Клиенту — только своего обращения: принятые и отклонённые анализом (видно, какой снимок переснять). */
    suspend fun openForClient(clientId: Long, claimId: Long, fileId: Long): StoredFile = db.tx {
        if (!ClaimRepository.isOwnedBy(claimId, clientId)) throw NotFoundException("Файл не найден")
        open(claimId, fileId, setOf(FileStatus.ACCEPTED, FileStatus.REJECTED))
    }

    /** Оператору — кроме черновиков; доступны и заменённые версии. */
    suspend fun openForOperator(claimId: Long, fileId: Long): StoredFile = db.tx {
        if (!ClaimRepository.isVisibleToOperator(claimId)) throw NotFoundException("Файл не найден")
        open(claimId, fileId, setOf(FileStatus.ACCEPTED, FileStatus.REPLACED, FileStatus.REJECTED))
    }

    private fun open(claimId: Long, fileId: Long, statuses: Set<FileStatus>): StoredFile {
        val file = FileRepository.findStored(claimId, fileId, statuses) ?: throw NotFoundException("Файл не найден")
        val ext = file[ClaimFiles.storageKey].substringAfterLast('.', "bin")
        return StoredFile(
            mimeType = file[ClaimFiles.mimeType],
            downloadName = "claim-$claimId-file-$fileId.$ext",
            open = { storage.open(file[ClaimFiles.storageKey]) },
        )
    }
}
