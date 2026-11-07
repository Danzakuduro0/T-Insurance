package ru.tinsurance.server.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import ru.tinsurance.api.AnalysisStatus
import ru.tinsurance.api.FileStatus
import ru.tinsurance.api.RejectionReason

object ClaimFiles : Table("claim_files") {
    val id = long("id").autoIncrement()
    val claimId = long("claim_id")
    val documentType = text("document_type")
    val storageKey = text("storage_key")
    val originalName = text("original_name")
    val mimeType = text("mime_type")
    val sizeBytes = long("size_bytes")
    val sha256 = text("sha256")
    val phash = long("phash").nullable()
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val status = enumText<FileStatus>("status")
    val analysisStatus = enumText<AnalysisStatus>("analysis_status").default(AnalysisStatus.NOT_REQUIRED)
    val rejectionReason = enumText<RejectionReason>("rejection_reason").nullable()
    val duplicateOfFileId = long("duplicate_of_file_id").nullable()
    val replacedByFileId = long("replaced_by_file_id").nullable()
    val uploadedBy = long("uploaded_by")
    val clientRequestId = uuid("client_request_id").nullable()
    val uploadedAt = timestampWithTimeZone("uploaded_at")
    override val primaryKey = PrimaryKey(id)
}

object ImageAnalyses : Table("image_analyses") {
    val id = long("id").autoIncrement()
    val fileId = long("file_id")
    val modelVersion = text("model_version")
    val qualityOk = bool("quality_ok")
    val qualityScore = float("quality_score").nullable()
    val qualityIssues = textArray("quality_issues")
    val damageType = text("damage_type").nullable()
    val damageConfidence = float("damage_confidence").nullable()
    val damageDetected = bool("damage_detected").nullable()
    val carPart = text("car_part").nullable()
    val damageArea = jsonbElement("damage_area").nullable()
    val rawResult = jsonbElement("raw_result").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}
