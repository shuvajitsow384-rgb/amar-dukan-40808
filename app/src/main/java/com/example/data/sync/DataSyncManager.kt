package com.example.data.sync

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Diagnostic data class representing a single recorded Firestore sync operation.
 */
data class FirestoreSyncLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val formattedTime: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp)),
    val operationType: String, // e.g., "SET_MERGE", "TRANSACTION_SET", "BATCH_WRITE", "DELETE", "INBOUND_RECONCILE"
    val collection: String,    // e.g., "sales", "products", "customers", "ledger_entries"
    val documentId: String,
    val payload: Map<String, Any?>,
    val payloadSummary: String,
    val isDuplicateOrIncrementalWarning: Boolean = false,
    val warningDetails: String? = null,
    val caller: String = ""
)

/**
 * Diagnostic logger and manager for tracking every Firestore write operation
 * and detecting duplicate, compounding, or incremental writes during data reconciliation.
 */
object DataSyncManager {

    private const val TAG = "DataSyncDiagnostic"
    private const val MAX_BUFFER_SIZE = 300

    // Ring buffer of recent synchronization events
    private val logBuffer = ConcurrentLinkedDeque<FirestoreSyncLogEntry>()
    private val _recentLogsFlow = MutableStateFlow<List<FirestoreSyncLogEntry>>(emptyList())
    val recentLogsFlow: StateFlow<List<FirestoreSyncLogEntry>> = _recentLogsFlow.asStateFlow()

    // History map per document ID to detect rapid duplicate writes and compounding quantity drift
    private val writeHistoryMap = ConcurrentHashMap<String, MutableList<FirestoreSyncLogEntry>>()

    /**
     * Record a Firestore write operation with its full payload and analyze for duplicates or compounding.
     */
    fun logFirestoreWrite(
        operationType: String,
        collection: String,
        documentId: String,
        payload: Map<String, Any?>,
        caller: String = ""
    ): FirestoreSyncLogEntry {
        val now = System.currentTimeMillis()
        val summary = generatePayloadSummary(collection, payload)

        // Run anomaly detection against previous writes for this document
        val history = writeHistoryMap.getOrPut("$collection/$documentId") { mutableListOf() }
        var isAnomaly = false
        var anomalyMessage: String? = null

        synchronized(history) {
            val previousWrite = history.lastOrNull()
            if (previousWrite != null) {
                val timeDiffSec = (now - previousWrite.timestamp) / 1000

                // Check 1: Rapid repeated write within 10 seconds with identical payload
                if (timeDiffSec <= 10 && arePayloadsIdentical(previousWrite.payload, payload)) {
                    isAnomaly = true
                    anomalyMessage = "DUPLICATE WRITE DETECTED: Document $documentId written twice within ${timeDiffSec}s with identical payload."
                }

                // Check 2: Incremental or compounding quantity/amount growth on the same document
                val growthWarning = detectCompoundingGrowth(collection, previousWrite.payload, payload)
                if (growthWarning != null) {
                    isAnomaly = true
                    anomalyMessage = if (anomalyMessage != null) "$anomalyMessage | $growthWarning" else growthWarning
                }
            }
        }

        val entry = FirestoreSyncLogEntry(
            timestamp = now,
            operationType = operationType,
            collection = collection,
            documentId = documentId,
            payload = payload.toMap(),
            payloadSummary = summary,
            isDuplicateOrIncrementalWarning = isAnomaly,
            warningDetails = anomalyMessage,
            caller = caller
        )

        // Store in ring buffer
        logBuffer.addFirst(entry)
        while (logBuffer.size > MAX_BUFFER_SIZE) {
            logBuffer.removeLast()
        }
        _recentLogsFlow.value = logBuffer.toList()

        // Keep last 10 entries per document in history map
        synchronized(history) {
            history.add(entry)
            if (history.size > 10) {
                history.removeAt(0)
            }
        }

        // Emit logcat diagnostic output
        if (isAnomaly) {
            Log.w(
                TAG,
                "[DIAGNOSTIC WARNING] [$operationType] $collection/$documentId | $anomalyMessage\nPayload: $payload"
            )
        } else {
            Log.d(
                TAG,
                "[WRITE LOGGED] [$operationType] $collection/$documentId | $summary"
            )
        }

        return entry
    }

    /**
     * Record an inbound reconciliation event from remote Firestore snapshot listener.
     */
    fun logInboundReconciliation(
        collection: String,
        documentId: String,
        payload: Map<String, Any?>,
        actionTaken: String
    ) {
        val summary = "$actionTaken | ${generatePayloadSummary(collection, payload)}"
        val entry = FirestoreSyncLogEntry(
            operationType = "INBOUND_RECONCILE",
            collection = collection,
            documentId = documentId,
            payload = payload,
            payloadSummary = summary,
            isDuplicateOrIncrementalWarning = false,
            warningDetails = null,
            caller = "SnapshotListener"
        )

        logBuffer.addFirst(entry)
        while (logBuffer.size > MAX_BUFFER_SIZE) {
            logBuffer.removeLast()
        }
        _recentLogsFlow.value = logBuffer.toList()

        Log.d(TAG, "[INBOUND RECONCILE] $collection/$documentId -> $summary")
    }

    /**
     * Check if two payloads have compounding quantity/amount growth.
     */
    private fun detectCompoundingGrowth(
        collection: String,
        oldPayload: Map<String, Any?>,
        newPayload: Map<String, Any?>
    ): String? {
        if (collection == "sales") {
            val oldFinalAmount = (oldPayload["finalAmount"] as? Number)?.toDouble() ?: 0.0
            val newFinalAmount = (newPayload["finalAmount"] as? Number)?.toDouble() ?: 0.0

            val oldItems = (oldPayload["items"] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
            val newItems = (newPayload["items"] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()

            // Check if item quantities grew unexpectedly on the same sale
            for (newItem in newItems) {
                val prodId = newItem["productId"] as? String ?: continue
                val newQty = (newItem["quantity"] as? Number)?.toDouble() ?: 0.0
                val oldMatchingItem = oldItems.find { (it["productId"] as? String) == prodId }
                if (oldMatchingItem != null) {
                    val oldQty = (oldMatchingItem["quantity"] as? Number)?.toDouble() ?: 0.0
                    if (oldQty > 0 && newQty >= oldQty * 1.95 && newQty <= oldQty * 2.05) {
                        return "POTENTIAL DOUBLING ANOMALY: Product '$prodId' quantity doubled from $oldQty to $newQty (Old Total: ₹$oldFinalAmount, New Total: ₹$newFinalAmount)."
                    } else if (oldQty > 0 && newQty > oldQty) {
                        return "INCREMENTAL QUANTITY EXPANSION: Product '$prodId' quantity grew from $oldQty to $newQty on existing sale."
                    }
                }
            }

            if (oldFinalAmount > 0 && newFinalAmount >= oldFinalAmount * 1.95 && newFinalAmount <= oldFinalAmount * 2.05) {
                return "POTENTIAL DOUBLING ANOMALY: Sale total doubled from ₹$oldFinalAmount to ₹$newFinalAmount."
            }
        }
        return null
    }

    private fun arePayloadsIdentical(p1: Map<String, Any?>, p2: Map<String, Any?>): Boolean {
        // Compare keys excluding auto-generated timestamps
        val filterKeys = setOf("updated_at", "updatedAt", "timestamp")
        val filteredP1 = p1.filterKeys { it !in filterKeys }
        val filteredP2 = p2.filterKeys { it !in filterKeys }
        return filteredP1 == filteredP2
    }

    private fun generatePayloadSummary(collection: String, payload: Map<String, Any?>): String {
        return when (collection) {
            "sales" -> {
                val total = payload["finalAmount"] ?: payload["totalAmount"] ?: 0
                val mode = payload["paymentMode"] ?: "UNKNOWN"
                val cust = payload["customerName"] ?: "Walk-in"
                val items = (payload["items"] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
                val itemsDesc = items.take(3).joinToString {
                    val name = it["productNameEn"] ?: it["productId"] ?: "Item"
                    val qty = it["quantity"] ?: 1
                    val unit = it["unitType"] ?: "pc"
                    "$name: $qty$unit"
                } + if (items.size > 3) " (+${items.size - 3} more)" else ""
                "Total: ₹$total | Mode: $mode | Customer: $cust | Items (${items.size}): [$itemsDesc]"
            }
            "products" -> {
                val name = payload["nameEn"] ?: payload["nameBn"] ?: payload["name"] ?: "Product"
                val stock = payload["stock"] ?: 0
                val price = payload["sellPrice"] ?: 0
                "Name: $name | Stock: $stock | SellPrice: ₹$price"
            }
            "customers" -> {
                val name = payload["name"] ?: "Customer"
                val balance = payload["balance"] ?: 0
                "Name: $name | Khata Balance: ₹$balance"
            }
            "ledger_entries" -> {
                val type = payload["type"] ?: "ENTRY"
                val amount = payload["amount"] ?: 0
                val cust = payload["customerName"] ?: ""
                "Type: $type | Amount: ₹$amount | Customer: $cust"
            }
            else -> {
                "Fields (${payload.size}): ${payload.keys.take(5).joinToString()}"
            }
        }
    }

    /**
     * Get all recorded logs for a specific document ID.
     */
    fun getLogsForDocument(documentId: String): List<FirestoreSyncLogEntry> {
        return logBuffer.filter { it.documentId == documentId }
    }

    /**
     * Get all recorded logs that have active warnings.
     */
    fun getAnomalyLogs(): List<FirestoreSyncLogEntry> {
        return logBuffer.filter { it.isDuplicateOrIncrementalWarning }
    }

    /**
     * Export a clean diagnostic summary string for logs or support reports.
     */
    fun exportDiagnosticSummary(): String {
        val logs = logBuffer.toList()
        if (logs.isEmpty()) return "No Firestore sync operations recorded yet."

        val sb = StringBuilder()
        sb.appendLine("=== FIRESTORE DATA SYNC DIAGNOSTIC REPORT ===")
        sb.appendLine("Generated At: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("Total Operations Tracked: ${logs.size}")
        sb.appendLine("Anomalies/Warnings Flagged: ${logs.count { it.isDuplicateOrIncrementalWarning }}")
        sb.appendLine("----------------------------------------------")

        logs.forEachIndexed { index, entry ->
            sb.appendLine("[${index + 1}] ${entry.formattedTime} | [${entry.operationType}] ${entry.collection}/${entry.documentId}")
            sb.appendLine("    Summary: ${entry.payloadSummary}")
            if (entry.caller.isNotBlank()) {
                sb.appendLine("    Caller: ${entry.caller}")
            }
            if (entry.isDuplicateOrIncrementalWarning) {
                sb.appendLine("    ⚠️ WARNING: ${entry.warningDetails}")
            }
            sb.appendLine("    Full Payload: ${entry.payload}")
            sb.appendLine()
        }
        sb.appendLine("==============================================")
        return sb.toString()
    }

    /**
     * Clear recorded logs.
     */
    fun clearLogs() {
        logBuffer.clear()
        writeHistoryMap.clear()
        _recentLogsFlow.value = emptyList()
    }
}
