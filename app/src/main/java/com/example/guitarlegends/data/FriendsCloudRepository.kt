package com.example.guitarlegends.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class FriendRequestCloud(
    @DocumentId val id: String = "",
    val senderUid: String = "",
    val recipientUid: String = "",
    val senderNickname: String = "",
    val recipientNickname: String = "",
    val status: String = "PENDING",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L
)

data class RoomInviteCloud(
    @DocumentId val id: String = "",
    val senderUid: String = "",
    val recipientUid: String = "",
    val senderNickname: String = "",
    val roomCode: String = "",
    val status: String = "PENDING",
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L
)

data class FriendPresenceCloud(
    @DocumentId val userId: String = "",
    val online: Boolean = false,
    val lastSeenAtMs: Long = 0L
) {
    fun isOnline(nowMs: Long = System.currentTimeMillis()): Boolean =
        online && lastSeenAtMs > 0L && nowMs - lastSeenAtMs <= 45_000L
}

/**
 * Relacionamentos, presença e convites diretos. Usa o Firestore já existente;
 * não adiciona Firebase Storage, hospedagem ou serviço pago.
 */
class FriendsCloudRepository(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private fun uidOrNull(): String? = auth.currentUser?.uid

    private fun pairId(a: String, b: String): String =
        listOf(a, b).sorted().joinToString("__")

    suspend fun sendFriendRequest(nickname: String): Result<Unit> {
        val uid = uidOrNull() ?: return Result.failure(IllegalStateException("Entre na sua conta para adicionar amigos."))
        val normalized = nickname.trim().lowercase()
        if (normalized.length !in 3..24 || !normalized.matches(Regex("[a-z0-9_-]+"))) {
            return Result.failure(IllegalArgumentException("Digite um nickname válido, com 3 a 24 caracteres."))
        }
        return try {
            val nickSnap = firestore.collection("nicknames").document(normalized).get().await()
            val targetUid = nickSnap.getString("userId").orEmpty()
            if (targetUid.isBlank()) throw IllegalArgumentException("Nenhum jogador encontrado com esse nickname.")
            if (targetUid == uid) throw IllegalArgumentException("Você não pode adicionar sua própria conta.")
            val myAccount = firestore.collection("users").document(uid).get().await()
            val targetAccount = firestore.collection("users").document(targetUid).get().await()
            val myNickname = myAccount.getString("nickname").orEmpty().ifBlank { "Jogador" }
            val targetNickname = targetAccount.getString("nickname").orEmpty().ifBlank { nickname.trim() }
            val requestRef = firestore.collection("friend_requests").document(pairId(uid, targetUid))
            val myBlock = firestore.collection("users").document(uid).collection("blocked").document(targetUid)
            val theirBlock = firestore.collection("users").document(targetUid).collection("blocked").document(uid)
            firestore.runTransaction { tx ->
                val existing = tx.get(requestRef)
                val blockedByMe = tx.get(myBlock).exists()
                val blockedByThem = tx.get(theirBlock).exists()
                if (blockedByMe || blockedByThem) throw IllegalStateException("Não é possível enviar solicitação para este jogador.")
                if (existing.exists() && existing.getString("status") == "ACCEPTED") {
                    throw IllegalStateException("Este jogador já está na sua lista de amigos.")
                }
                if (existing.exists() && existing.getString("status") == "PENDING") {
                    throw IllegalStateException("Já existe uma solicitação de amizade pendente entre vocês.")
                }
                tx.set(requestRef, mapOf(
                    "senderUid" to uid,
                    "recipientUid" to targetUid,
                    "senderNickname" to myNickname.take(24),
                    "recipientNickname" to targetNickname.take(24),
                    "status" to "PENDING",
                    "createdAtMs" to System.currentTimeMillis(),
                    "updatedAtMs" to System.currentTimeMillis()
                ))
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeRequests(): Flow<List<FriendRequestCloud>> = callbackFlow {
        val uid = uidOrNull()
        if (uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        var incoming = emptyList<FriendRequestCloud>()
        var outgoing = emptyList<FriendRequestCloud>()
        fun publish() = trySend((incoming + outgoing).distinctBy { it.id }.sortedByDescending { it.updatedAtMs })
        val collection = firestore.collection("friend_requests")
        val incomingListener = collection.whereEqualTo("recipientUid", uid).addSnapshotListener { snap, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            incoming = snap?.documents?.mapNotNull { it.toObject(FriendRequestCloud::class.java)?.copy(id = it.id) }.orEmpty()
            publish()
        }
        val outgoingListener = collection.whereEqualTo("senderUid", uid).addSnapshotListener { snap, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            outgoing = snap?.documents?.mapNotNull { it.toObject(FriendRequestCloud::class.java)?.copy(id = it.id) }.orEmpty()
            publish()
        }
        awaitClose {
            incomingListener.remove()
            outgoingListener.remove()
        }
    }

    suspend fun respondToRequest(requestId: String, accept: Boolean): Result<Unit> {
        val uid = uidOrNull() ?: return Result.failure(IllegalStateException("Sessão encerrada."))
        if (requestId.isBlank() || requestId.length > 128) return Result.failure(IllegalArgumentException("Solicitação inválida."))
        return try {
            val ref = firestore.collection("friend_requests").document(requestId)
            val snap = ref.get().await()
            if (!snap.exists() || snap.getString("recipientUid") != uid || snap.getString("status") != "PENDING") {
                throw IllegalStateException("Esta solicitação não está mais disponível.")
            }
            ref.update(
                mapOf(
                    "status" to if (accept) "ACCEPTED" else "DECLINED",
                    "updatedAtMs" to System.currentTimeMillis()
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeFriend(friendUid: String): Result<Unit> {
        val uid = uidOrNull() ?: return Result.failure(IllegalStateException("Sessão encerrada."))
        if (friendUid.isBlank() || friendUid == uid) return Result.failure(IllegalArgumentException("Amigo inválido."))
        return try {
            val ref = firestore.collection("friend_requests").document(pairId(uid, friendUid))
            val snap = ref.get().await()
            if (snap.getString("status") != "ACCEPTED") throw IllegalStateException("Este jogador não está na sua lista de amigos.")
            ref.delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun blockUser(blockedUid: String): Result<Unit> {
        val uid = uidOrNull() ?: return Result.failure(IllegalStateException("Sessão encerrada."))
        if (blockedUid.isBlank() || blockedUid == uid) return Result.failure(IllegalArgumentException("Jogador inválido."))
        return try {
            firestore.collection("users").document(uid).collection("blocked").document(blockedUid)
                .set(mapOf("userId" to blockedUid, "createdAtMs" to System.currentTimeMillis())).await()
            firestore.collection("friend_requests").document(pairId(uid, blockedUid)).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setPresence(online: Boolean): Result<Unit> {
        val uid = uidOrNull() ?: return Result.failure(IllegalStateException("Sessão encerrada."))
        return try {
            firestore.collection("user_presence").document(uid).set(
                mapOf("userId" to uid, "online" to online, "lastSeenAtMs" to System.currentTimeMillis())
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observePresence(userIds: List<String>): Flow<Map<String, Boolean>> = callbackFlow {
        val ids = userIds.distinct().filter { it.isNotBlank() && it != uidOrNull() }.take(10)
        if (ids.isEmpty()) {
            trySend(emptyMap())
            close()
            return@callbackFlow
        }
        val states = mutableMapOf<String, Boolean>()
        val registrations = ids.map { friendUid ->
            firestore.collection("user_presence").document(friendUid).addSnapshotListener { snap, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val presence = snap?.toObject(FriendPresenceCloud::class.java)?.copy(userId = friendUid)
                states[friendUid] = presence?.isOnline() == true
                trySend(states.toMap())
            }
        }
        awaitClose { registrations.forEach { it.remove() } }
    }

    suspend fun sendRoomInvite(friendUid: String, roomCode: String, senderNickname: String): Result<String> {
        val uid = uidOrNull() ?: return Result.failure(IllegalStateException("Sessão encerrada."))
        if (friendUid.isBlank() || friendUid == uid || roomCode.isBlank()) {
            return Result.failure(IllegalArgumentException("Convite inválido."))
        }
        return try {
            val friendship = firestore.collection("friend_requests").document(pairId(uid, friendUid)).get().await()
            if (friendship.getString("status") != "ACCEPTED") throw IllegalStateException("Este jogador não está na sua lista de amigos.")
            val room = firestore.collection("multiplayer_rooms").document(roomCode).get().await()
            val members = room.get("memberUids") as? List<*> ?: emptyList<Any>()
            if (!room.exists() || uid !in members || room.getString("status") !in listOf("WAITING", "PLAYING")) {
                throw IllegalStateException("A sala não está disponível para convite.")
            }
            val ref = firestore.collection("room_invites").document()
            ref.set(mapOf(
                "senderUid" to uid,
                "recipientUid" to friendUid,
                "senderNickname" to senderNickname.take(24),
                "roomCode" to roomCode.take(24),
                "status" to "PENDING",
                "createdAtMs" to System.currentTimeMillis(),
                "updatedAtMs" to System.currentTimeMillis()
            )).await()
            Result.success(ref.id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun observeRoomInvites(): Flow<List<RoomInviteCloud>> = callbackFlow {
        val uid = uidOrNull()
        if (uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val listener = firestore.collection("room_invites").whereEqualTo("recipientUid", uid)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val invites = snap?.documents?.mapNotNull {
                    it.toObject(RoomInviteCloud::class.java)?.copy(id = it.id)
                }.orEmpty().filter { it.status == "PENDING" }
                trySend(invites.sortedByDescending { it.createdAtMs })
            }
        awaitClose { listener.remove() }
    }

    suspend fun respondToRoomInvite(inviteId: String, accept: Boolean): Result<String?> {
        val uid = uidOrNull() ?: return Result.failure(IllegalStateException("Sessão encerrada."))
        return try {
            val ref = firestore.collection("room_invites").document(inviteId)
            val snap = ref.get().await()
            if (!snap.exists() || snap.getString("recipientUid") != uid || snap.getString("status") != "PENDING") {
                throw IllegalStateException("Este convite não está mais disponível.")
            }
            val roomCode = snap.getString("roomCode").orEmpty()
            ref.update(
                mapOf("status" to if (accept) "ACCEPTED" else "DECLINED", "updatedAtMs" to System.currentTimeMillis())
            ).await()
            Result.success(if (accept) roomCode else null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
