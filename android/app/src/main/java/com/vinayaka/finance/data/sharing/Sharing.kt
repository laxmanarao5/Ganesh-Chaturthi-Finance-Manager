package com.vinayaka.finance.data.sharing

import com.vinayaka.finance.data.model.AppUser
import com.vinayaka.finance.data.model.Role
import kotlinx.coroutines.CancellationException

/** How much a Google account may do in the Sheet itself (Google Drive's "reader" / "writer" / "owner"). */
enum class ShareLevel(val phrase: String) {
    OWNER("owner"), WRITER("can edit"), READER("can view");
}

/** The Sheet access an app role needs: Editors and Admins edit the Sheet, Viewers only look. */
val Role.shareLevel: ShareLevel get() = if (canEdit) ShareLevel.WRITER else ShareLevel.READER

/** One person (or Google group) the Sheet is shared with. */
data class Share(val id: String, val email: String, val level: ShareLevel)

/** Access that is not tied to a person: "anyone with the link" or a whole organisation. */
data class OpenAccess(val who: String, val level: ShareLevel)

data class ShareSnapshot(val people: List<Share>, val open: List<OpenAccess> = emptyList())

/** Who the Google Sheet is shared with. Implemented by the Google Drive API client and by an in-memory fake. */
interface SheetSharing {
    suspend fun list(): ShareSnapshot
    suspend fun add(email: String, level: ShareLevel)
    suspend fun change(shareId: String, level: ShareLevel)
    suspend fun remove(shareId: String)
}

sealed interface ShareAction {
    val email: String
    data class Add(override val email: String, val level: ShareLevel) : ShareAction
    data class Change(override val email: String, val shareId: String, val level: ShareLevel) : ShareAction
    data class Remove(override val email: String, val shareId: String) : ShareAction
}

data class SharingReport(
    val added: List<String> = emptyList(),
    val changed: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val failed: List<Pair<String, String>> = emptyList(),
    /** Have access to the Sheet but are not on the app's list (they could still open the Sheet directly). */
    val others: List<String> = emptyList(),
    val open: List<OpenAccess> = emptyList(),
) {
    val changes get() = added.size + changed.size + removed.size

    /** Plain sentences for the result dialog. */
    fun lines(): List<String> = buildList {
        if (changes == 0 && failed.isEmpty()) add("The Sheet's sharing already matches the list.")
        if (added.isNotEmpty()) add("Shared with ${added.joinToString()}.")
        if (changed.isNotEmpty()) add("Access changed for ${changed.joinToString()}.")
        if (removed.isNotEmpty()) add("Access removed for ${removed.joinToString()}.")
        failed.forEach { (email, why) -> add("Could not update $email: $why") }
        if (others.isNotEmpty()) add("Also has access to the Sheet, but is not on the list: ${others.joinToString()}. They cannot use the app, but can open the Sheet directly.")
        open.forEach { add("${it.who} ${it.level.phrase} the Sheet. Turn that off in Google Sheets (Share) if only listed people should see it.") }
    }
}

object SheetSharingService {
    /**
     * What to change in the Sheet's sharing so it matches the app's list.
     * Everyone on the list gets the level their role needs. People who were removed from the list lose access.
     * Anyone else who already has access is left alone (and reported). The owner is never touched.
     */
    fun plan(desired: List<AppUser>, removed: Collection<String>, existing: List<Share>, keep: Collection<String>): List<ShareAction> {
        val have = existing.associateBy { it.email.trim().lowercase() }
        val keepSet = keep.map { it.trim().lowercase() }.toSet()
        val wanted = desired.map { it.email.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val actions = mutableListOf<ShareAction>()
        for (u in desired) {
            val email = u.email.trim().lowercase()
            if (email.isEmpty()) continue
            val level = u.role.shareLevel
            val current = have[email]
            when {
                current == null -> actions += ShareAction.Add(email, level)
                current.level == ShareLevel.OWNER -> Unit
                current.level != level -> actions += ShareAction.Change(email, current.id, level)
            }
        }
        for (r in removed.map { it.trim().lowercase() }.distinct()) {
            if (r in wanted || r in keepSet) continue
            val current = have[r] ?: continue
            if (current.level != ShareLevel.OWNER) actions += ShareAction.Remove(r, current.id)
        }
        return actions
    }

    suspend fun reconcile(
        sharing: SheetSharing,
        desired: List<AppUser>,
        removed: Collection<String> = emptyList(),
        keep: Collection<String> = emptyList(),
    ): SharingReport {
        val snapshot = sharing.list()
        val added = mutableListOf<String>(); val changed = mutableListOf<String>(); val gone = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        for (a in plan(desired, removed, snapshot.people, keep)) {
            try {
                when (a) {
                    is ShareAction.Add -> { sharing.add(a.email, a.level); added += a.email }
                    is ShareAction.Change -> { sharing.change(a.shareId, a.level); changed += a.email }
                    is ShareAction.Remove -> { sharing.remove(a.shareId); gone += a.email }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += a.email to (e.message ?: "Google refused the change.")
            }
        }
        val listed = desired.map { it.email.trim().lowercase() }.toSet()
        val removedSet = removed.map { it.trim().lowercase() }.toSet()
        val others = snapshot.people
            .filter { it.level != ShareLevel.OWNER && it.email.lowercase() !in listed && it.email.lowercase() !in removedSet }
            .map { it.email }
        return SharingReport(added, changed, gone, failed, others, snapshot.open)
    }
}

/** In-memory stand-in for Drive sharing: used by demo mode and tests. */
class FakeSharing(initial: List<Share> = emptyList(), private val open: List<OpenAccess> = emptyList()) : SheetSharing {
    val shares = initial.toMutableList()
    /** Emails for which every change fails, to test partial failures. */
    val failFor = mutableSetOf<String>()
    private var next = 1

    override suspend fun list() = ShareSnapshot(shares.toList(), open)

    override suspend fun add(email: String, level: ShareLevel) {
        if (email in failFor) error("Google refused to share with $email.")
        shares += Share("p${next++}", email, level)
    }

    override suspend fun change(shareId: String, level: ShareLevel) {
        val i = shares.indexOfFirst { it.id == shareId }
        if (i < 0) error("No such permission.")
        if (shares[i].email in failFor) error("Google refused to change ${shares[i].email}.")
        shares[i] = shares[i].copy(level = level)
    }

    override suspend fun remove(shareId: String) {
        val i = shares.indexOfFirst { it.id == shareId }
        if (i < 0) error("No such permission.")
        if (shares[i].email in failFor) error("Google refused to remove ${shares[i].email}.")
        shares.removeAt(i)
    }
}
