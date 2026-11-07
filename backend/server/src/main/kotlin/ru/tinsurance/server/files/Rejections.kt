package ru.tinsurance.server.files

import ru.tinsurance.api.RejectionReason

class RejectedFileException(val reason: RejectionReason) : Exception(reason.name)

fun RejectionReason.hint(): String = when (this) {
    RejectionReason.UNSUPPORTED_FORMAT -> "Загрузите файл в разрешённом формате: JPEG, PNG или PDF"
    RejectionReason.CORRUPTED -> "Файл повреждён или сохранён не полностью — сохраните его заново и загрузите ещё раз"
    RejectionReason.TOO_MANY_PIXELS -> "Слишком большое изображение — уменьшите разрешение и загрузите снова"
    RejectionReason.ACTIVE_CONTENT -> "PDF содержит скрипты или вложения — сохраните документ как обычный PDF"
    RejectionReason.LOW_QUALITY -> "Сфотографируйте повреждение крупнее и при хорошем освещении"
}
