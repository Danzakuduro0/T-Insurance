package ru.tinsurance.server.operator.actions

import org.jetbrains.exposed.v1.core.ResultRow
import ru.tinsurance.api.FileStatus
import ru.tinsurance.api.operator.RequestMaterialsRequest
import ru.tinsurance.server.claims.ClaimRules
import ru.tinsurance.server.common.ValidationException
import ru.tinsurance.server.db.ClaimFiles
import ru.tinsurance.server.db.Claims
import ru.tinsurance.server.files.FileRepository

object MaterialsRequestValidator {
    fun text(value: String, field: String): String = value.trim().also {
        if (it.isEmpty() || it.length > 4000) throw ValidationException("$field: от 1 до 4000 символов")
    }

    fun validate(claim: ResultRow, request: RequestMaterialsRequest, maxFiles: Int) {
        text(request.message, "message")
        if (request.items.isEmpty() || request.items.size > 30) throw ValidationException("items: от 1 до 30 пунктов")
        val fields = claim[Claims.eventType]?.let { ClaimRules.fields(it).map { f -> f.code }.toSet() }.orEmpty()
        val keys = mutableSetOf<String>()
        request.items.forEach { item ->
            text(item.comment, "comment")
            if ((item.documentType == null) == (item.fieldName == null)) {
                throw ValidationException("Укажите documentType или fieldName")
            }
            if (item.minCount !in 1..maxFiles) throw ValidationException("Некорректное minCount")
            val key = if (item.fieldName != null) {
                if (item.fieldName !in fields || item.fileId != null || item.minCount != 1) {
                    throw ValidationException("Некорректный запрос поля")
                }
                "field:${item.fieldName}"
            } else {
                if (FileRepository.documentType(item.documentType!!) == null) throw ValidationException("Неизвестный тип документа")
                if (item.fileId != null) {
                    val file = FileRepository.find(claim[Claims.id], item.fileId!!)
                        ?: throw ValidationException("Файл не принадлежит обращению")
                    if (file[ClaimFiles.status] !in setOf(FileStatus.ACCEPTED, FileStatus.REJECTED) ||
                        file[ClaimFiles.documentType] != item.documentType || item.minCount != 1) {
                        throw ValidationException("Некорректный запрос замены файла")
                    }
                    "file:${item.fileId}"
                } else "document:${item.documentType}"
            }
            if (!keys.add(key)) throw ValidationException("Повторяющийся пункт запроса")
        }
        val files = FileRepository.rows(claim[Claims.id])
        val additions = request.items.sumOf { item ->
            when {
                item.fieldName != null -> 0
                item.fileId == null -> item.minCount
                files[item.fileId]?.get(ClaimFiles.status) == FileStatus.REJECTED -> 1
                else -> 0
            }
        }
        if (files.values.count { it[ClaimFiles.status] == FileStatus.ACCEPTED } + additions > maxFiles) {
            throw ValidationException("Запрос превышает лимит файлов обращения")
        }
    }
}
