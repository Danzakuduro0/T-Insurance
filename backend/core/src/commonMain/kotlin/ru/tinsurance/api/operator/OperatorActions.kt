package ru.tinsurance.api.operator

import kotlinx.serialization.Serializable

@Serializable
data class RequestMaterialsRequest(val message: String, val items: List<RequestedMaterial>)

@Serializable
data class RequestedMaterial(
    val documentType: String? = null,
    val fieldName: String? = null,
    val fileId: Long? = null,
    val minCount: Int = 1,
    val comment: String,
)

@Serializable
data class ReviewResultRequest(val summary: String, val clientComment: String)

@Serializable
data class CancelClaimRequest(val reason: String, val clientComment: String)
