package dev.aichallenge.day20.files.model

data class SaveReportResult(
    val fileName: String,
    val relativePath: String,
    val sizeBytes: Int,
    val sha256: String,
    val status: String = "SAVED",
)
