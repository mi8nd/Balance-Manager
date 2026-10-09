package com.personal.balance.data

import com.google.firebase.firestore.FirebaseFirestore
import com.personal.balance.model.UserConfig
import com.personal.balance.model.Transaction
import com.personal.balance.model.TransactionType
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The result of persisting a brand-new transaction. On success the caller
 * receives the [Transaction] whose [Transaction.id] was set by Firestore — the
 * caller needs this id to later update the AI-assigned category without touching
 * the rest of the document.
 */
sealed class AddTransactionResult {
    data class Saved(val transaction: Transaction) : AddTransactionResult()
    data class Error(val message: String) : AddTransactionResult()
}

sealed class UserConfigResult {
    data class Success(val config: UserConfig) : UserConfigResult()
    data class Error(val message: String) : UserConfigResult()
}

sealed class TransactionQueryResult {
    data class Success(val transactions: List<Transaction>) : TransactionQueryResult()
    data class Error(val diagnostic: String = "transactions.read_failed") : TransactionQueryResult()
}

class FirestoreUserRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    suspend fun createUserConfig(userId: String): UserConfigResult =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val userConfig = UserConfig(
                    userId = userId,
                    onboardingCompleted = false,
                    createdAt = System.currentTimeMillis()
                )
                firestore.collection("users").document(userId).set(userConfig).await()
                UserConfigResult.Success(userConfig)
            } catch (e: Exception) {
                UserConfigResult.Error(e.message ?: "Failed to create user config")
            }
        }

    suspend fun getUserConfig(userId: String): UserConfigResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val snapshot = firestore.collection("users").document(userId).get().await()
            val config = snapshot.toObject(UserConfig::class.java)
            if (config != null) {
                UserConfigResult.Success(config)
            } else {
                UserConfigResult.Error("User config not found")
            }
        } catch (e: Exception) {
            UserConfigResult.Error(e.message ?: "Failed to fetch user config")
        }
    }

    suspend fun completeOnboarding(
        userId: String,
        initialBalance: Long
    ): UserConfigResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val updates = mapOf(
                "onboardingCompleted" to true,
                "currentBalance" to initialBalance
            )
            firestore.collection("users").document(userId).update(updates).await()
            getUserConfig(userId)
        } catch (e: Exception) {
            UserConfigResult.Error(e.message ?: "Failed to complete onboarding")
        }
    }

    suspend fun setTarget(
        userId: String,
        targetName: String,
        targetAmount: Long,
        targetEnabled: Boolean = true
    ): UserConfigResult =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val updates = mapOf(
                    "targetName" to targetName,
                    "targetAmount" to targetAmount,
                    "targetEnabled" to targetEnabled
                )
                firestore.collection("users").document(userId).update(updates).await()
                getUserConfig(userId)
            } catch (e: Exception) {
                UserConfigResult.Error(e.message ?: "Failed to set target")
            }
        }

    suspend fun acknowledgeTarget(userId: String): UserConfigResult =
        withContext(Dispatchers.IO) {
            return@withContext try {
                firestore.collection("users").document(userId).update("targetAcknowledged", true).await()
                getUserConfig(userId)
            } catch (e: Exception) {
                UserConfigResult.Error(e.message ?: "Failed to acknowledge target")
            }
        }

    suspend fun updateBalance(userId: String, newBalance: Long): UserConfigResult =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val updates = mapOf("currentBalance" to newBalance)
                firestore.collection("users").document(userId).update(updates).await()
                getUserConfig(userId)
            } catch (e: Exception) {
                UserConfigResult.Error(e.message ?: "Failed to update balance")
            }
        }

    suspend fun addTransaction(userId: String, transaction: Transaction): AddTransactionResult =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val userDocRef = firestore.collection("users").document(userId)
                val transDocRef = userDocRef.collection("transactions").document()
                val transactionId = transDocRef.id
                firestore.runTransaction { transactionContext ->
                    val snapshot = transactionContext.get(userDocRef)
                    val currentBalance = snapshot.getLong("currentBalance") ?: 0L

                    val newBalance = if (transaction.type == TransactionType.INCOME) {
                        currentBalance + transaction.amount
                    } else {
                        currentBalance - transaction.amount
                    }

                    val targetEnabled = snapshot.getBoolean("targetEnabled") ?: false
                    val targetAmount = snapshot.getLong("targetAmount") ?: 0L
                    val targetCompleted = snapshot.getBoolean("targetCompleted") ?: false

                    if (targetEnabled && newBalance >= targetAmount && targetAmount > 0) {
                        if (!targetCompleted) {
                            transactionContext.update(userDocRef, "targetCompleted", true)
                        }
                    } else {
                        if (targetCompleted) {
                            transactionContext.update(userDocRef, "targetCompleted", false)
                            transactionContext.update(userDocRef, "targetAcknowledged", false)
                        }
                    }

                    transactionContext.update(userDocRef, "currentBalance", newBalance)
                    transactionContext.set(transDocRef, transaction.copy(id = transactionId))

                    null
                }.await()
                AddTransactionResult.Saved(transaction.copy(id = transactionId))
            } catch (e: Exception) {
                AddTransactionResult.Error(e.message ?: "Failed to add transaction")
            }
        }

    /**
     * Updates only the `category` field of an existing transaction. Used by the
     * async automatic categorization step so an AI result can be persisted
     * without re-computing balance/target arithmetic.
     */
    suspend fun updateTransactionCategory(userId: String, transactionId: String, category: String): UserConfigResult =
        withContext(Dispatchers.IO) {
            return@withContext try {
                firestore.collection("users").document(userId)
                    .collection("transactions")
                    .document(transactionId)
                    .update(mapOf("category" to category))
                    .await()
                UserConfigResult.Success(UserConfig())
            } catch (e: Exception) {
                UserConfigResult.Error(e.message ?: "Failed to update transaction category")
            }
        }

    /**
     * All transactions for the user, newest first (no recency limit).
     */
    suspend fun getAllTransactions(userId: String): Result<List<Transaction>> = withContext(Dispatchers.IO) {
        return@withContext runCatching {
            val snapshot = firestore.collection("users").document(userId)
                .collection("transactions")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .get()
                .await()
            snapshot.toObjects(Transaction::class.java)
        }
    }

    suspend fun getRecentTransactions(userId: String): List<Transaction> = withContext(Dispatchers.IO) {
        return@withContext try {
            val snapshot = firestore.collection("users").document(userId)
                .collection("transactions")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(5)
                .get()
                .await()
            snapshot.toObjects(Transaction::class.java)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getTransactionsBetween(
        userId: String,
        startEpochMillis: Long,
        endEpochMillis: Long
    ): TransactionQueryResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val snapshot = firestore.collection("users").document(userId)
                .collection("transactions")
                .whereGreaterThanOrEqualTo("timestamp", startEpochMillis)
                .whereLessThan("timestamp", endEpochMillis)
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(500)
                .get()
                .await()
            TransactionQueryResult.Success(snapshot.toObjects(Transaction::class.java))
        } catch (e: Exception) {
            TransactionQueryResult.Error()
        }
    }

    suspend fun deleteUserData(userId: String): UserConfigResult = withContext(Dispatchers.IO) {
        return@withContext try {
            // Delete all transactions first
            val transactions = firestore.collection("users").document(userId).collection("transactions").get().await()
            for (doc in transactions.documents) {
                doc.reference.delete().await()
            }
            // Then delete user config
            firestore.collection("users").document(userId).delete().await()
            UserConfigResult.Success(UserConfig())
        } catch (e: Exception) {
            UserConfigResult.Error(e.message ?: "Failed to delete user data")
        }
    }

    /**
     * Purges the user's Firestore data over the Firestore REST API using a token
     * captured BEFORE the Firebase Auth account was deleted (see §28 fix). It is
     * only ever invoked AFTER `deleteCurrentUser()` has SUCCEEDED, so the SDK
     * session is gone and plain SDK writes would be denied by the ownership
     * rules — this REST path reuses the same captured ID token (which still
     * carries `request.auth.uid == userId`) to delete every transaction under
     * `users/{uid}/transactions` and finally the `users/{uid}` document itself.
     */
    suspend fun purgeUserDataWithCapturedToken(userId: String, idToken: String): UserConfigResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val projectId = firestore.app.options.projectId
            val base = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents"
            val transactionsPrefix = "$base/users/$userId/transactions"

            // List (paginated) then delete every transaction document.
            var pageToken: String? = null
            do {
                val listUrl = buildString {
                    append("$transactionsPrefix?pageSize=300")
                    if (pageToken != null) append("&pageToken=$pageToken")
                }
                val (listCode, listBody) = firestoreRestJson("GET", listUrl, idToken)
                if (listCode != 200) {
                    return@withContext UserConfigResult.Error("Failed to list transactions (HTTP $listCode)")
                }
                val docs = listBody.getJSONArray("documents")
                for (i in 0 until docs.length()) {
                    val name = docs.getJSONObject(i).getString("name")
                    val docUrl = "https://firestore.googleapis.com/v1/$name"
                    val (delCode, _) = firestoreRestJson("DELETE", docUrl, idToken)
                    if (delCode != 200) {
                        return@withContext UserConfigResult.Error("Failed to delete transaction (HTTP $delCode)")
                    }
                }
                pageToken = if (listBody.has("nextPageToken")) listBody.getString("nextPageToken") else null
            } while (pageToken != null)

            // Finally delete the user config document itself.
            val (userCode, _) = firestoreRestJson("DELETE", "$base/users/$userId", idToken)
            if (userCode != 200) {
                return@withContext UserConfigResult.Error("Failed to delete user config (HTTP $userCode)")
            }
            UserConfigResult.Success(UserConfig())
        } catch (e: Exception) {
            UserConfigResult.Error(e.message ?: "Failed to purge user data")
        }
    }

    private fun firestoreRestJson(method: String, url: String, idToken: String): Pair<Int, org.json.JSONObject> {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer $idToken")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        connection.disconnect()
        val json = if (text.isBlank()) org.json.JSONObject() else org.json.JSONObject(text)
        return Pair(code, json)
    }
}
