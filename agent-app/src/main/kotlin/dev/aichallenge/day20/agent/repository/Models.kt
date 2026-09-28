package dev.aichallenge.day20.agent.repository

import java.time.Instant

enum class RunStatus { CREATED, RUNNING, COMPLETED, FAILED, CANCELLED }
enum class InvocationStatus { REQUESTED, RUNNING, SUCCEEDED, REJECTED, FAILED }

data class OrchestrationRun(
    val id: String,
    val requestText: String,
    val status: RunStatus,
    val modelId: String,
    val createdAt: Instant,
    val startedAt: Instant?,
    val updatedAt: Instant,
    val finishedAt: Instant?,
    val finalAnswer: String?,
    val reportPath: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val version: Int,
)

data class ToolInvocation(
    val id: String,
    val runId: String,
    val sequenceNumber: Int,
    val serverName: String,
    val toolName: String,
    val toolCallId: String?,
    val status: InvocationStatus,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val durationMs: Long?,
    val inputHash: String,
    val outputHash: String?,
    val inputRefsJson: String,
    val outputRefsJson: String?,
    val errorCode: String?,
    val errorMessage: String?,
)

data class OrchestrationEvent(
    val id: String,
    val runId: String,
    val sequenceNumber: Int,
    val eventType: String,
    val serverName: String?,
    val toolName: String?,
    val occurredAt: Instant,
    val payloadJson: String,
)
