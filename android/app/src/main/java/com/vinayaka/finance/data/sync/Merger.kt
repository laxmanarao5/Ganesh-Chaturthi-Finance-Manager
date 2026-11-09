package com.vinayaka.finance.data.sync

import com.vinayaka.finance.data.model.RemoteRow
import com.vinayaka.finance.data.model.RowWrite
import com.vinayaka.finance.data.model.SyncRecord

data class ConflictNote(val summary: String, val keptMine: Boolean)

data class SyncReport(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val removed: Int = 0,
    val restored: Int = 0,
    val skippedRows: Int = 0,
    val skippedWrites: Int = 0,
    val conflicts: List<ConflictNote> = emptyList(),
    val years: List<Int> = emptyList(),
    /** Year tabs that exist in the Sheet right now. */
    val remoteYears: List<Int> = emptyList(),
) {
    /** True when both sides already agreed on everything. */
    val nothingToSync: Boolean
        get() = pushed == 0 && pulled == 0 && removed == 0 && restored == 0 && conflicts.isEmpty()

    operator fun plus(o: SyncReport) = SyncReport(
        pushed + o.pushed, pulled + o.pulled, removed + o.removed, restored + o.restored,
        skippedRows + o.skippedRows, skippedWrites + o.skippedWrites,
        conflicts + o.conflicts, (years + o.years).distinct().sorted(),
        (remoteYears + o.remoteYears).distinct().sorted(),
    )
}

data class MergePlan<T>(
    val localUpserts: List<T>,
    val localDeletes: List<String>,
    val remoteUpdates: List<RowWrite<T>>,
    val remoteAppends: List<T>,
    val report: SyncReport,
) {
    val hasRemoteWrites: Boolean get() = remoteUpdates.isNotEmpty() || remoteAppends.isNotEmpty()
}

/**
 * Pure merge of one tab's local rows against the Sheet's rows.
 * Rules: unchanged -> nothing; one side changed -> that side wins; both changed -> newest edit wins
 * (ties go to the Sheet); rows edited/added by hand in the Sheet are adopted and given a new rev.
 */
object Merger {
    const val SHEET_EDITOR = "Sheet edit"

    fun <T : SyncRecord<T>> merge(local: List<T>, remote: List<RemoteRow<T>>, now: Long): MergePlan<T> {
        val localById = local.associateBy { it.id }
        val seen = HashSet<String>()
        val upserts = mutableListOf<T>()
        val deletes = mutableListOf<String>()
        val updates = mutableListOf<RowWrite<T>>()
        val appends = mutableListOf<T>()
        val conflicts = mutableListOf<ConflictNote>()
        var pushed = 0
        var pulled = 0
        var removed = 0
        var restored = 0

        // Whole tab wiped (or wrong sheet): put our rows back instead of deleting everything locally.
        if (remote.isEmpty() && local.isNotEmpty()) {
            local.forEach { appends += it.withRev(maxOf(it.rev, 1)).synced() }
            return MergePlan(
                localUpserts = appends.toList(),
                localDeletes = emptyList(),
                remoteUpdates = emptyList(),
                remoteAppends = appends.toList(),
                report = SyncReport(pushed = local.count { it.dirty }, restored = local.count { !it.dirty }),
            )
        }

        for (r in remote) {
            if (!seen.add(r.record.id)) continue // duplicate id in the Sheet: first one wins
            val (rem, writeBack) = adoptHandEdits(r, now)
            val l = localById[rem.id]
            when {
                l == null -> {
                    upserts += rem.synced(); pulled++
                    if (writeBack) updates += RowWrite(r.rowNumber, rem)
                }
                !l.dirty -> {
                    if (!(l.rev == rem.rev && l.contentHash() == rem.contentHash())) {
                        upserts += rem.synced(); pulled++
                        if (writeBack) updates += RowWrite(r.rowNumber, rem)
                    }
                }
                else -> {
                    val sameContent = l.contentHash() == rem.contentHash()
                    val remoteUnchanged = rem.rev == l.baseRev && !writeBack
                    when {
                        sameContent && !writeBack -> upserts += l.withRev(maxOf(l.rev, rem.rev)).synced()
                        remoteUnchanged -> {
                            updates += RowWrite(r.rowNumber, l)
                            upserts += l.synced(); pushed++
                        }
                        else -> {
                            val localWins = l.updatedAt > rem.updatedAt
                            conflicts += ConflictNote(l.describe(), keptMine = localWins)
                            if (localWins) {
                                val win = l.withRev(maxOf(l.rev, rem.rev) + 1)
                                updates += RowWrite(r.rowNumber, win)
                                upserts += win.synced(); pushed++
                            } else {
                                upserts += rem.synced(); pulled++
                                if (writeBack) updates += RowWrite(r.rowNumber, rem)
                            }
                        }
                    }
                }
            }
        }

        for (l in local) {
            if (l.id in seen) continue
            if (l.dirty) {
                appends += l; upserts += l.synced(); pushed++
            } else {
                deletes += l.id; removed++ // someone deleted the row by hand in the Sheet
            }
        }

        return MergePlan(
            upserts, deletes, updates, appends,
            SyncReport(pushed, pulled, removed, restored, conflicts = conflicts),
        )
    }

    /** Rows typed or edited directly in Google Sheets get a fresh rev so every phone notices them. */
    private fun <T : SyncRecord<T>> adoptHandEdits(r: RemoteRow<T>, now: Long): Pair<T, Boolean> {
        if (r.hadId && r.hashMatches) return r.record to false
        return r.record.adopted(if (r.hadId) r.record.rev + 1 else 1, now, SHEET_EDITOR) to true
    }
}
