package ru.tinsurance.api

import kotlinx.serialization.Serializable

// Значения совпадают с кодами в БД (CHECK-ограничения и справочники).

@Serializable
enum class Role { CLIENT, OPERATOR, ADMIN }

@Serializable
enum class DevicePlatform { ANDROID, IOS }

@Serializable
enum class ActorType { SYSTEM, CLIENT, OPERATOR, ADMIN }

@Serializable
enum class ClaimStatus {
    DRAFT, SUBMITTED, NEEDS_INFO, IN_ANALYSIS, QUEUED, ASSIGNED, IN_REVIEW, COMPLETED, CANCELLED;

    val isFinal get() = this == COMPLETED || this == CANCELLED
}

/** Поля обращения, которые могут быть обязательными; apiName — имя поля в JSON. */
@Serializable
enum class ClaimField(val apiName: String, val title: String) {
    POLICY("policyId", "Полис"),
    EVENT_TYPE("eventType", "Тип события"),
    EVENT_AT("eventAt", "Дата и время события"),
    EVENT_LOCATION("eventLocation", "Место события"),
    DESCRIPTION("description", "Описание события"),
    CONTACT_PHONE("contactPhone", "Контактный телефон");

    companion object {
        const val PARTICIPANTS = "participants"
    }
}

@Serializable
enum class FieldKind { CORE, DETAIL }

@Serializable
enum class FieldValueType { STRING, BOOLEAN, NUMBER, LIST }

@Serializable
enum class FileStatus { UPLOADED, ACCEPTED, REJECTED, DUPLICATE, REPLACED }

@Serializable
enum class RejectionReason { UNSUPPORTED_FORMAT, CORRUPTED, TOO_MANY_PIXELS, ACTIVE_CONTENT, LOW_QUALITY }

@Serializable
enum class AnalysisStatus { NOT_REQUIRED, PENDING, DONE, FAILED }

@Serializable
enum class InfoRequestStatus { OPEN, FULFILLED, CANCELLED }

/** Что не так с материалом в запросе уточнений. */
@Serializable
enum class MaterialIssue { MISSING, INVALID_FORMAT, LOW_QUALITY }

@Serializable
enum class DecisionKind {
    COMPLETENESS, DUPLICATE, DAMAGE, ROUTING, PRIORITY, REVIEW_RESULT;

    /** Решения, которые оператор подтверждает или исправляет. */
    val isReviewable get() = this == DAMAGE || this == ROUTING || this == PRIORITY
}

@Serializable
enum class DecisionSource { SYSTEM, OPERATOR }

@Serializable
enum class DecisionAction { SET, CONFIRM, OVERRIDE }

/** EXACT — тот же файл байт в байт, SIMILAR — тот же снимок, пересжатый или уменьшенный. */
@Serializable
enum class MatchType { EXACT, SIMILAR }

/** Виды повреждений, которые различает модель. */
@Serializable
enum class DamageType { SCRATCH, DENT, BROKEN_GLASS, BUMPER_DAMAGE, HEADLIGHT_DAMAGE }

@Serializable
enum class CommentVisibility { INTERNAL, CLIENT }

@Serializable
enum class OperatorAvailability { ONLINE, BREAK, OFFLINE }
