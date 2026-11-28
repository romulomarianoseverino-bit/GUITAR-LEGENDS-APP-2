package com.example.data

import com.example.guitarlegends.data.FIRESTORE_QUOTA_WARNING
import com.example.guitarlegends.data.multiplayerServiceErrorMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class FirestoreErrorHandlerTest {
    @Test
    fun quotaErrorsGetSpecificPlayerMessage() {
        val error = IllegalStateException("RESOURCE_EXHAUSTED: quota exceeded")
        assertEquals(FIRESTORE_QUOTA_WARNING, multiplayerServiceErrorMessage(error, "fallback"))
    }

    @Test
    fun wrappedQuotaErrorsAreRecognized() {
        val error = IllegalStateException("Task failed", IllegalStateException("daily quota exceeded"))
        assertEquals(FIRESTORE_QUOTA_WARNING, multiplayerServiceErrorMessage(error, "fallback"))
    }

    @Test
    fun ordinaryFailuresKeepTheirOwnMessage() {
        val error = IllegalStateException("Sem conexão com a internet")
        assertEquals("Sem conexão com a internet", multiplayerServiceErrorMessage(error, "fallback"))
    }

    @Test
    fun missingMessageUsesFallback() {
        assertEquals("fallback", multiplayerServiceErrorMessage(null, "fallback"))
    }
}
