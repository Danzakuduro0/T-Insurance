package com.tinsurance.app.operator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class OperatorScreen { Queue, Case, Materials, Correction, Request, History }

const val OperatorName = "Елена Волкова"

data class OperatorClaim(
    val number: String,
    val car: String,
    val policy: String,
    val status: String,
    val priority: String,
    val waiting: String,
    val operator: String? = null,
    val client: String = "Анна Смирнова",
    val direction: String = "Ручная проверка",
    val day: Int = 17,
) {
    val completed get() = status == "Обработка завершена"
    val editable get() = operator == OperatorName && !completed
    val colleague get() = operator != null && operator != OperatorName
}

data class QueueFilter(
    val query: String = "",
    val status: String = "",
    val priority: String = "",
    val period: String = "",
    val eventType: String = "",
    val operator: String = "",
    val tab: Int = 0,
)

fun filterClaims(claims: List<OperatorClaim>, filter: QueueFilter): List<OperatorClaim> =
    claims.filter { claim ->
        val query = filter.query.trim()
        (query.isEmpty() || claim.number.contains(query, true) || claim.policy.contains(query, true)) &&
            (filter.status.isEmpty() || claim.status == filter.status) &&
            (filter.priority.isEmpty() || claim.priority == filter.priority) &&
            (filter.period.isEmpty() || when (filter.period) {
                "Сегодня" -> claim.day == 17
                "Последние 7 дней" -> claim.day >= 11
                else -> true
            }) &&
            (filter.eventType.isEmpty() || filter.eventType == "ДТП") &&
            (filter.operator.isEmpty() || (claim.operator ?: "Не назначен") == filter.operator) &&
            when (filter.tab) {
                1 -> claim.operator == OperatorName
                2 -> claim.operator == null
                else -> true
            }
    }

data class PhotoCorrection(
    val result: String,
    val direction: String,
    val priority: String,
    val reason: String,
)

data class MaterialRequest(
    val photo: Boolean,
    val notice: Boolean,
    val message: String,
    val reason: String,
) {
    val valid get() = (photo || notice) && message.isNotBlank() && reason.isNotBlank()
}

data class OperatorEvent(
    val time: String,
    val action: String,
    val before: String,
    val after: String,
    val author: String,
    val comment: String,
    val notification: Boolean = false,
    val correction: PhotoCorrection? = null,
    val originalResult: String = "Царапина · уверенность 64%",
)

/** Локальный сценарий вёрстки. Операторские команды в API пока не отправляются. */
class OperatorState(historyPreview: Boolean = false) {
    private val initialClaims = operatorDemoClaims()
    var claims by mutableStateOf(initialClaims)
        private set
    var corrections by mutableStateOf<Map<String, PhotoCorrection>>(emptyMap())
        private set
    var requests by mutableStateOf<Map<String, MaterialRequest>>(emptyMap())
        private set
    var events by mutableStateOf<Map<String, List<OperatorEvent>>>(emptyMap())
        private set

    init {
        if (historyPreview) {
            val correction = PhotoCorrection("Вмятина", "Ручная проверка", "Повышенный", DefaultCorrectionReason)
            corrections = mapOf(photoKey("ТС-10428", 0) to correction)
            events = mapOf("ТС-10428" to listOf(
                OperatorEvent("12:14", "Результат фото 1", "Царапина", "Вмятина", OperatorName,
                    "Видна деформация металла", correction = correction),
                OperatorEvent("12:10", "Статус", "На рассмотрении", "Нужно уточнение", OperatorName,
                    "Запрошены фото и извещение"),
                OperatorEvent("12:10", "Клиент уведомлён", "—", "Уведомление в приложении", "Система",
                    "Запрос материалов отправлен", notification = true),
            ))
        }
    }

    fun claim(number: String) = claims.first { it.number == number }

    fun correction(number: String, photo: Int) = corrections[photoKey(number, photo)]

    fun history(number: String): List<OperatorEvent> = events[number].orEmpty() + listOfNotNull(
        initialClaims.first { it.number == number }.operator?.let {
            OperatorEvent("10:15", "Исполнитель", "Не назначен", it, "Система", "Назначен ответственный оператор")
        },
        OperatorEvent("09:42", "Результат модели", "—", "Царапина · 64%", "Модель", "Предварительный анализ фото"),
        OperatorEvent("09:41", "Регистрация", "Черновик", "Отправлено", "Клиент", "Обращение создано в приложении"),
    )

    fun take(number: String): Boolean {
        val claim = claim(number)
        if (claim.colleague || claim.completed) return false
        if (claim.operator == OperatorName) return true
        replace(claim.copy(operator = OperatorName, status = "На рассмотрении"))
        addEvent(number, OperatorEvent("Сейчас", "Исполнитель", "Не назначен", OperatorName,
            OperatorName, "Обращение взято в работу"))
        return true
    }

    fun saveCorrection(number: String, photo: Int, value: PhotoCorrection): Boolean {
        val claim = claim(number)
        if (!claim.editable || photo !in 0..3 || value.result.isBlank() || value.reason.isBlank()) return false
        val before = correction(number, photo)?.result ?: if (photo == 2) "Не определено" else "Царапина"
        val saved = value.copy(result = value.result.trim(), reason = value.reason.trim())
        corrections = corrections + (photoKey(number, photo) to saved)
        replace(claim.copy(direction = saved.direction, priority = saved.priority))
        if (claim.direction != saved.direction) addEvent(number, OperatorEvent("Сейчас", "Направление",
            claim.direction, saved.direction, OperatorName, saved.reason))
        if (claim.priority != saved.priority) addEvent(number, OperatorEvent("Сейчас", "Приоритет",
            claim.priority, saved.priority, OperatorName, saved.reason))
        addEvent(number, OperatorEvent("Сейчас", "Результат фото ${photo + 1}", before, saved.result,
            OperatorName, saved.reason, correction = saved,
            originalResult = if (photo == 2) "Анализ недоступен" else "Царапина · уверенность 64%"))
        return true
    }

    fun requestMaterials(number: String, value: MaterialRequest): Boolean {
        val claim = claim(number)
        if (!claim.editable || !value.valid) return false
        requests = requests + (number to value)
        replace(claim.copy(status = "Нужно уточнение"))
        addEvent(number, OperatorEvent("Сейчас", "Клиент уведомлён", "—", "Уведомление в приложении",
            "Система", "Запрос материалов отправлен", notification = true))
        addEvent(number, OperatorEvent("Сейчас", "Статус", claim.status, "Нужно уточнение", OperatorName, value.reason))
        return true
    }

    fun complete(number: String, comment: String): Boolean {
        val claim = claim(number)
        if (!claim.editable || comment.isBlank()) return false
        replace(claim.copy(status = "Обработка завершена"))
        addEvent(number, OperatorEvent("Сейчас", "Статус", claim.status, "Обработка завершена", OperatorName, comment.trim()))
        return true
    }

    private fun replace(claim: OperatorClaim) {
        claims = claims.map { if (it.number == claim.number) claim else it }
    }

    private fun addEvent(number: String, event: OperatorEvent) {
        events = events + (number to (listOf(event) + events[number].orEmpty()))
    }

    private fun photoKey(number: String, photo: Int) = "$number/$photo"
}

const val DefaultCorrectionReason = "На фото видна деформация металла передней левой двери."

fun operatorDemoClaims(): List<OperatorClaim> {
    val first = listOf(
        OperatorClaim("ТС-10428", "Kia Rio 2019", "TEST-0421", "На рассмотрении", "Повышенный", "2 ч 15 мин", OperatorName),
        OperatorClaim("ТС-10431", "Hyundai Solaris", "TEST-0783", "В очереди", "Обычный", "32 мин"),
        OperatorClaim("ТС-10420", "Skoda Octavia", "TEST-7713", "В работе у коллеги", "Обычный", "1 ч 40 мин", "Иван Петров"),
        OperatorClaim("ТС-10418", "Lada Vesta", "TEST-3378", "Нужно уточнение", "Высокий", "5 ч 10 мин"),
        OperatorClaim("ТС-10416", "Renault Duster", "TEST-9931", "Проверка материалов", "Обычный", "3 ч 5 мин", "Мария Орлова"),
    )
    // 47 учебных записей: работают все десять страниц, а счётчики вычисляются по данным.
    return first + List(42) { index ->
        OperatorClaim(
            number = "ТС-${10370 + index}",
            car = listOf("Toyota Corolla", "Kia Ceed", "Volkswagen Polo")[index % 3],
            policy = "TEST-${5100 + index}",
            status = if (index < 10) "В очереди" else "На рассмотрении",
            priority = if (index < 6) "Повышенный" else "Обычный",
            waiting = "${20 + index * 3} мин",
            operator = when {
                index < 10 -> null
                index < 15 -> OperatorName
                else -> "Мария Орлова"
            },
            day = if (index < 12) 17 else 10,
        )
    }
}
