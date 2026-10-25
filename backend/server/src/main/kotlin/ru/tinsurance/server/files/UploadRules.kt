package ru.tinsurance.server.files

import org.jetbrains.exposed.v1.core.ResultRow
import ru.tinsurance.api.ClaimStatus
import ru.tinsurance.api.FileStatus
import ru.tinsurance.server.claims.ClaimRepository
import ru.tinsurance.server.claims.InfoRequestRepository
import ru.tinsurance.server.common.ConflictException
import ru.tinsurance.server.common.ValidationException
import ru.tinsurance.server.db.ClaimFiles
import ru.tinsurance.server.db.Claims

/** Когда клиент может добавлять и заменять материалы. Вызывается внутри транзакции. */
object UploadRules {

    /** После отправки клиент только добавляет материалы по запросу. */
    fun uploadableClaim(clientId: Long, claimId: Long, forUpdate: Boolean): ResultRow {
        val claim = ClaimRepository.findOwned(clientId, claimId, forUpdate)
        val status = claim[Claims.status]
        if (status != ClaimStatus.DRAFT && status != ClaimStatus.NEEDS_INFO) {
            throw ConflictException("Материалы можно добавлять в черновик или по запросу дополнительных материалов")
        }
        return claim
    }

    /** Заменить можно отклонённый файл; принятый — только в черновике или если его замену запросили. */
    fun checkReplaceable(claimId: Long, fileId: Long, documentType: String) {
        val file = FileRepository.find(claimId, fileId) ?: throw ValidationException("Заменяемый файл не найден")
        if (file[ClaimFiles.documentType] != documentType) throw ValidationException("Замена должна быть того же типа документа")
        when (file[ClaimFiles.status]) {
            FileStatus.REJECTED -> Unit
            FileStatus.ACCEPTED -> {
                val draft = ClaimRepository.status(claimId) == ClaimStatus.DRAFT
                if (!draft && !InfoRequestRepository.isFileRequested(claimId, fileId)) {
                    throw ConflictException("Принятый файл после отправки можно заменить только по запросу")
                }
            }
            else -> throw ConflictException("Этот файл уже заменён")
        }
    }

    /** Имя файла от клиента — только для показа: без пути, управляющих символов, до 200 знаков. */
    fun safeName(name: String?): String =
        name?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.filter { !it.isISOControl() }?.trim()?.take(200)?.takeIf { it.isNotEmpty() } ?: "file"
}
