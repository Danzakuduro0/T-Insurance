package com.tinsurance.app.admin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tinsurance.app.operator.OperatorClaim
import com.tinsurance.app.operator.operatorDemoClaims

enum class AdminScreen { Overview, Queue, Team, Rules, Journal }

const val AdminName = "Старший оператор"
const val KeepPriority = "Сохранить текущий приоритет"

data class TeamOperator(
    val name: String,
    val status: String,
    val specialization: String,
    val active: Int,
    val queued: Int,
    val capacity: Int = 8,
) {
    val available get() = (capacity - active).coerceAtLeast(0)
    val onDuty get() = status == "На линии"
    val label get() = "$name · свободно $available мест"
}

val RequiredMaterials = listOf("Описание события", "Фото общего вида", "Фото повреждения крупно", "Извещение о ДТП")
val ReviewRoutes = listOf("Ручная проверка", "Очередь оператора")
val IncompleteRoutes = listOf("Запрос уточнения", "Проверка комплектности")
val ReplyRoutes = listOf("Проверка комплектности", "Ручная проверка")

data class ProcessingRules(
    val materials: List<String> = RequiredMaterials,
    val confidence: Int = 75,
    val belowThreshold: String = "Ручная проверка",
    val incomplete: String = "Запрос уточнения",
    val afterReply: String = "Проверка комплектности",
    val queueHours: Int = 2,
    val reviewHours: Int = 24,
)

fun rulesError(rules: ProcessingRules): String? = when {
    rules.materials.isEmpty() || rules.materials.any { it !in RequiredMaterials } -> "Выберите обязательные материалы"
    rules.confidence !in 0..100 -> "Порог уверенности должен быть от 0 до 100%"
    rules.queueHours <= 0 || rules.reviewHours <= 0 -> "Сроки должны быть больше нуля"
    rules.queueHours > rules.reviewHours -> "Срок рассмотрения не может быть меньше срока очереди"
    rules.belowThreshold !in ReviewRoutes || rules.incomplete !in IncompleteRoutes || rules.afterReply !in ReplyRoutes ->
        "Выберите маршрут из списка"
    else -> null
}

fun parseHours(value: String): Int? =
    Regex("([0-9]+)\\s*(?:час(?:а|ов)?)?").matchEntire(value.trim())?.groupValues?.get(1)?.toIntOrNull()

data class AdminEvent(
    val action: String,
    val subject: String,
    val before: String,
    val after: String,
    val reason: String,
    val author: String = AdminName,
)

/** Локальные данные макета. Изменения не отправляются в административный API. */
class AdminState {
    var operators by mutableStateOf(adminDemoOperators())
        private set
    var claims by mutableStateOf(adminDemoClaims())
        private set
    var rules by mutableStateOf(ProcessingRules())
        private set
    var events by mutableStateOf<List<AdminEvent>>(emptyList())
        private set

    val unassigned get() = claims.filter { it.operator == null && !it.completed }

    /** null — назначение выполнено; иначе возвращается понятная причина отказа. */
    fun reassign(numbers: List<String>, name: String, priority: String, reason: String): String? {
        val selected = numbers.distinct().mapNotNull { number -> claims.find { it.number == number } }
        val target = operators.find { it.name == name }
        val incoming = selected.count { it.operator != name }
        val error = when {
            selected.isEmpty() -> "Выберите хотя бы одно обращение"
            selected.size != numbers.distinct().size -> "Одно из обращений больше недоступно"
            selected.any { it.completed } -> "Завершённое обращение нельзя переназначить"
            target == null -> "Выберите нового исполнителя"
            !target.onDuty -> "Этот оператор сейчас недоступен"
            incoming > target.available -> "У оператора недостаточно свободных мест"
            priority !in listOf(KeepPriority, "Обычный", "Повышенный", "Высокий") -> "Выберите приоритет из списка"
            reason.isBlank() -> "Укажите причину переназначения"
            else -> null
        }
        if (error != null) return error

        val changed = selected.filter { it.operator != name }
        operators = operators.map { operator ->
            val outgoing = changed.count { it.operator == operator.name }
            val added = if (operator.name == name) changed.size else 0
            operator.copy(
                active = (operator.active - outgoing + added).coerceAtLeast(0),
                queued = (operator.queued - outgoing + added).coerceAtLeast(0),
            )
        }
        val selectedNumbers = selected.map { it.number }
        claims = claims.map { claim ->
            if (claim.number !in selectedNumbers) claim
            else claim.copy(
                operator = name,
                status = "На рассмотрении",
                priority = if (priority == KeepPriority) claim.priority else priority,
            )
        }
        events = selected.map { claim ->
            AdminEvent("Переназначение", claim.number, "${claim.operator ?: "Не назначен"} · ${claim.priority}",
                "$name · ${if (priority == KeepPriority) claim.priority else priority}", reason.trim())
        } + events
        return null
    }

    fun saveRules(value: ProcessingRules): String? {
        rulesError(value)?.let { return it }
        if (value == rules) return null
        events = listOf(AdminEvent(
            "Изменение правил", "ДТП", rulesSummary(rules), rulesSummary(value),
            "Настройки сохранены для новых обращений",
        )) + events
        rules = value
        return null
    }
}

private fun rulesSummary(rules: ProcessingRules) =
    "Порог ${rules.confidence}% · ${rules.belowThreshold} · ${rules.incomplete} · ${rules.afterReply}\n" +
        "Очередь ${rules.queueHours} ч · рассмотрение ${rules.reviewHours} ч\n" + rules.materials.joinToString(", ")

fun adminDemoOperators() = listOf(
    TeamOperator("Елена Волкова", "На линии", "ДТП, КАСКО", 6, 11),
    TeamOperator("Илья Морозов", "Занят", "ДТП, ОСАГО", 8, 14),
    TeamOperator("Мария Орлова", "На линии", "ДТП, КАСКО", 3, 6),
    TeamOperator("Алексей Соколов", "Перерыв", "ДТП", 0, 0),
    TeamOperator("Виктория Лебедева", "На линии", "ДТП", 5, 9),
)

fun adminDemoClaims(): List<OperatorClaim> {
    val names = adminDemoOperators().filter { it.onDuty }.map { it.name }
    return operatorDemoClaims().mapIndexed { index, claim ->
        when (claim.number) {
            "ТС-10428" -> claim.copy(operator = null, status = "В очереди")
            "ТС-10431" -> claim.copy(operator = null, status = "В очереди", client = "Сергей Крылов")
            "ТС-10403" -> claim.copy(client = "Игорь Петров", waiting = "26 ч 18 мин")
            "ТС-10411" -> claim.copy(client = "Ольга Кузнецова", waiting = "22 ч 39 мин")
            "ТС-10418" -> claim.copy(client = "Дмитрий Николаев", waiting = "8 ч 14 мин", operator = "Елена Волкова")
            else -> claim.copy(operator = claim.operator ?: names[index % names.size],
                status = if (index in 4..6) "Проверка материалов" else claim.status)
        }
    }
}
