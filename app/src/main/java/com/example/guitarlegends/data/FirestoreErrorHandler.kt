package com.example.guitarlegends.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import org.json.JSONObject

enum class OperationType {
    CREATE, UPDATE, DELETE, LIST, GET, WRITE
}

data class FirestoreErrorInfo(
    val error: String,
    val operationType: OperationType,
    val path: String?,
    val uid: String?,
    val email: String?
)

fun handleFirestoreError(
    e: Exception,
    operationType: OperationType,
    path: String?,
    auth: FirebaseAuth?
): Nothing {
    val currentUser = auth?.currentUser
    val errorJson = JSONObject().apply {
        put("error", e.message ?: "Erro desconhecido no Firestore")
        put("operationType", operationType.name.lowercase())
        put("path", path ?: "")
        put("authInfo", JSONObject().apply {
            put("userId", currentUser?.uid ?: "")
            put("email", currentUser?.email ?: "")
            put("emailVerified", currentUser?.isEmailVerified ?: false)
            put("isAnonymous", currentUser?.isAnonymous ?: false)
        })
    }
    Log.e("FirestoreError", "Erro no Firestore [${operationType.name}] em $path: $errorJson", e)
    throw e
}

/**
 * Converts Firebase multiplayer failures into messages players can understand.
 * RESOURCE_EXHAUSTED is deliberately distinct from ordinary network failures.
 */
const val FIRESTORE_QUOTA_WARNING =
    "Multiplayer temporariamente indisponível: a capacidade gratuita do serviço foi atingida. Tente novamente mais tarde."

fun multiplayerServiceErrorMessage(error: Throwable?, fallback: String): String {
    var current = error
    while (current != null) {
        if (current is FirebaseFirestoreException) {
            when (current.code) {
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> return FIRESTORE_QUOTA_WARNING
                FirebaseFirestoreException.Code.UNAVAILABLE ->
                    return "Servidor multiplayer temporariamente indisponível. Verifique sua conexão e tente novamente."
                else -> Unit
            }
        }

        val message = current.message.orEmpty().lowercase()
        if (
            "resource_exhausted" in message ||
            "quota exceeded" in message ||
            "quota_exceeded" in message ||
            "daily quota" in message
        ) {
            return FIRESTORE_QUOTA_WARNING
        }
        current = current.cause
    }
    return error?.message?.takeIf { it.isNotBlank() } ?: fallback
}
