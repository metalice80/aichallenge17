package dev.aichallenge.weather.agent.pipeline

import com.openai.errors.NotFoundException
import com.openai.errors.UnauthorizedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class PipelineExecutionServiceTest {
    @Test
    fun `404 identifies unavailable configured model`() {
        val failure = classifyAgentFailure(mock(NotFoundException::class.java), "gpt-invalid")

        assertEquals("OPENAI_MODEL_NOT_FOUND", failure.code)
        assertEquals(
            "OpenAI model 'gpt-invalid' is unavailable; set OPENAI_MODEL to a model enabled for this API project",
            failure.message,
        )
    }

    @Test
    fun `401 identifies invalid credentials for configured endpoint`() {
        val failure = classifyAgentFailure(mock(UnauthorizedException::class.java), "model")

        assertEquals("OPENAI_AUTHENTICATION_FAILED", failure.code)
        assertEquals(
            "OpenAI-compatible API rejected authentication; set OPENAI_API_KEY to a valid key for OPENAI_BASE_URL",
            failure.message,
        )
    }

    @Test
    fun `timeout remains distinguishable from other OpenAI failures`() {
        assertEquals("OPENAI_TIMEOUT", classifyAgentFailure(RuntimeException("request timeout"), "model").code)
        assertEquals("OPENAI_UNAVAILABLE", classifyAgentFailure(RuntimeException("connection refused"), "model").code)
    }
}
