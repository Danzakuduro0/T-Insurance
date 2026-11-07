package ru.tinsurance.api.operator

import kotlinx.serialization.Serializable

@Serializable
data class DashboardDto(
    val generatedAt: String,
    val queues: List<QueueLoadDto>,
    val operators: List<OperatorDto>,
    val metrics: ProcessingMetricsDto,
    val overloadWarning: Boolean,
    val recommendations: List<DashboardRecommendation>,
    val queuedThreshold: Int,
    val overdueThreshold: Int,
)

@Serializable
data class QueueLoadDto(val directionCode: String, val queued: Long, val queuedUrgent: Long,
    val overdue: Long, val oldestQueuedAt: String?, val overloadWarning: Boolean = false)

@Serializable
data class ProcessingMetricsDto(
    val submittedClaims: Long,
    val assignedSamples: Long,
    val averageFirstAssignmentSeconds: Double?,
    val completedSamples: Long,
    val averageCompletionSeconds: Double?,
    val needsInfoClaims: Long,
    val needsInfoShare: Double?,
)

@Serializable
data class DashboardRecommendation(val code: String, val directionCode: String, val message: String)
