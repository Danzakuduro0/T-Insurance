package com.tinsurance.app.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

enum class ClientScreen(val figmaNode: String) {
    Claims("8:205"),
    Contract("8:206"),
    Event("8:207"),
    Materials("8:208"),
    PhotoQuality("8:209"),
    Review("8:210"),
    Sent("8:211"),
    Progress("8:212"),
    Request("8:213"),
    Reply("8:214"),
    Completed("8:215"),
    Notifications("8:216"),
}

class ClaimDraft {
    var policy by mutableStateOf("TEST-0421")
    var date by mutableStateOf("16.10.2026")
    var time by mutableStateOf("18:35")
    var location by mutableStateOf("Томск, проспект Ленина")
    var description by
        mutableStateOf(
            "Другой автомобиль задел левую сторону. Повреждена передняя левая дверь. Пострадавших нет."
        )
    var email by mutableStateOf("anna@example.test")
    var participants by mutableStateOf("2")
    var injured by mutableStateOf(false)
    var notifications by mutableStateOf(true)
    var confirmed by mutableStateOf(true)
    var reply by mutableStateOf("Добавила фото двери и извещение.")

    val car: String
        get() =
            when (policy) {
                "TEST-0421" -> "Kia Rio"
                "TEST-0783" -> "Hyundai Solaris"
                else -> "Автомобиль по договору"
            }

    val year: String
        get() = if (policy == "TEST-0783") "2020" else "2019"

    val eventDate: String
        get() {
            if (!isValidDate(date)) return date
            val months =
                listOf(
                    "января",
                    "февраля",
                    "марта",
                    "апреля",
                    "мая",
                    "июня",
                    "июля",
                    "августа",
                    "сентября",
                    "октября",
                    "ноября",
                    "декабря",
                )
            val parts = date.split('.')
            return "${parts[0].toInt()} ${months[parts[1].toInt() - 1]}"
        }

    companion object {
        val Saver =
            listSaver<ClaimDraft, String>(
                save = {
                    listOf(
                        it.policy,
                        it.date,
                        it.time,
                        it.location,
                        it.description,
                        it.email,
                        it.participants,
                        it.injured.toString(),
                        it.notifications.toString(),
                        it.confirmed.toString(),
                        it.reply,
                    )
                },
                restore = { values ->
                    ClaimDraft().apply {
                        policy = values[0]
                        date = values[1]
                        time = values[2]
                        location = values[3]
                        description = values[4]
                        email = values[5]
                        participants = values[6]
                        injured = values[7].toBoolean()
                        notifications = values[8].toBoolean()
                        confirmed = values[9].toBoolean()
                        reply = values[10]
                    }
                },
            )
    }
}

fun validateEvent(draft: ClaimDraft): Map<String, String> = buildMap {
    if (!isValidDate(draft.date)) put("date", "Укажите дату в формате ДД.ММ.ГГГГ")
    if (!Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(draft.time))
        put("time", "Укажите время ЧЧ:ММ")
    if (draft.location.isBlank()) put("location", "Укажите место события")
    if (draft.description.isBlank()) put("description", "Опишите, что произошло")
    if (!Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(draft.email.trim()))
        put("email", "Проверьте адрес почты")
}

fun isValidDate(value: String): Boolean {
    if (!Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}").matches(value)) return false
    val (day, month, year) = value.split('.').map { it.toInt() }
    if (month !in 1..12 || year !in 1900..2100) return false
    val leap = year % 400 == 0 || year % 4 == 0 && year % 100 != 0
    val days = listOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    return day in 1..days[month - 1]
}

const val MaxFileSize = 20L * 1024 * 1024
const val MaxTotalSize = 100L * 1024 * 1024
const val MaxFileCount = 10

fun fileError(name: String, size: Long, count: Int, totalSize: Long): String? =
    when {
        name.substringAfterLast('.', "").lowercase() !in listOf("jpg", "jpeg", "png", "pdf") ->
            "Формат не поддерживается"
        size <= 0 -> "Файл пустой"
        size > MaxFileSize -> "Файл больше 20 МБ"
        count >= MaxFileCount -> "Можно добавить не больше 10 файлов"
        totalSize + size > MaxTotalSize -> "Общий размер файлов больше 100 МБ"
        else -> null
    }
