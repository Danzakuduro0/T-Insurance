package ru.tinsurance.server.analysis

import ru.tinsurance.server.db.ImageAnalyses
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Подсказки клиенту по проблемам качества фото (вопрос 22: «переснять с подсказкой»). */
object QualityHints {
    const val DEFAULT = "Сфотографируйте повреждение крупнее и при хорошем освещении"

    private val byIssue = mapOf(
        "BLURRY" to "Снимок размыт — держите телефон неподвижно и дождитесь фокусировки",
        "LOW_LIGHT" to "Слишком темно — снимите при хорошем освещении",
        "OVEREXPOSED" to "Засвечено — уберите блики, не снимайте против солнца",
        "LOW_RESOLUTION" to "Мало деталей — снимите в полном разрешении камеры",
        "NO_DAMAGE_VISIBLE" to "Повреждение не видно — сфотографируйте его крупнее",
    )

    fun forIssues(issues: List<String>): String =
        issues.mapNotNull { byIssue[it] }.distinct().joinToString(". ").ifEmpty { DEFAULT }

    /** Проблемы качества из последнего анализа каждого файла. Внутри транзакции. */
    fun issuesByFile(fileIds: Collection<Long>): Map<Long, List<String>> =
        if (fileIds.isEmpty()) emptyMap() else ImageAnalyses.selectAll()
            .where { ImageAnalyses.fileId inList fileIds }
            .orderBy(ImageAnalyses.id to SortOrder.ASC)
            .associate { it[ImageAnalyses.fileId] to it[ImageAnalyses.qualityIssues] }  // последний анализ перезаписывает
}
