package com.vinayaka.finance

import com.vinayaka.finance.data.DEFAULT_ADMIN_EMAIL
import com.vinayaka.finance.data.model.AppUser
import com.vinayaka.finance.data.model.Role
import com.vinayaka.finance.data.sharing.FakeSharing
import com.vinayaka.finance.data.sharing.OpenAccess
import com.vinayaka.finance.data.sharing.Share
import com.vinayaka.finance.data.sharing.ShareAction
import com.vinayaka.finance.data.sharing.ShareLevel
import com.vinayaka.finance.data.sharing.SheetSharingService
import com.vinayaka.finance.data.sharing.shareLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingTest {
    private val owner = Share("o", "owner@example.com", ShareLevel.OWNER)

    private fun level(sharing: FakeSharing, email: String) = sharing.shares.firstOrNull { it.email == email }?.level

    @Test fun `editors and admins edit the sheet and viewers only look`() {
        assertEquals(ShareLevel.READER, Role.VIEWER.shareLevel)
        assertEquals(ShareLevel.WRITER, Role.EDITOR.shareLevel)
        assertEquals(ShareLevel.WRITER, Role.ADMIN.shareLevel)
    }

    @Test fun `adding users shares the sheet with the level their role needs`() = runBlocking {
        val sharing = FakeSharing(listOf(owner))
        val users = listOf(
            AppUser("Asha", "asha@example.com", Role.VIEWER),
            AppUser("Ben", "Ben@Example.com", Role.EDITOR),
        )
        val report = SheetSharingService.reconcile(sharing, users)
        assertEquals(listOf("asha@example.com", "ben@example.com"), report.added)
        assertEquals(ShareLevel.READER, level(sharing, "asha@example.com"))
        assertEquals(ShareLevel.WRITER, level(sharing, "ben@example.com"))
        assertTrue(report.failed.isEmpty())
    }

    @Test fun `changing a role updates the existing permission instead of adding another`() = runBlocking {
        val sharing = FakeSharing(listOf(owner, Share("p", "asha@example.com", ShareLevel.READER)))
        val report = SheetSharingService.reconcile(sharing, listOf(AppUser("Asha", "asha@example.com", Role.EDITOR)))
        assertEquals(listOf("asha@example.com"), report.changed)
        assertEquals(1, sharing.shares.count { it.email == "asha@example.com" })
        assertEquals(ShareLevel.WRITER, level(sharing, "asha@example.com"))
    }

    @Test fun `a viewer who was shared as editor by hand is brought down to view only`() = runBlocking {
        val sharing = FakeSharing(listOf(owner, Share("p", "asha@example.com", ShareLevel.WRITER)))
        SheetSharingService.reconcile(sharing, listOf(AppUser("Asha", "asha@example.com", Role.VIEWER)))
        assertEquals(ShareLevel.READER, level(sharing, "asha@example.com"))
    }

    @Test fun `removing a user takes their access to the sheet away`() = runBlocking {
        val sharing = FakeSharing(listOf(owner, Share("p", "ben@example.com", ShareLevel.WRITER)))
        val report = SheetSharingService.reconcile(sharing, emptyList(), removed = listOf("Ben@Example.com"))
        assertEquals(listOf("ben@example.com"), report.removed)
        assertEquals(null, level(sharing, "ben@example.com"))
    }

    @Test fun `the owner is never changed or removed, even if listed or removed`() {
        val actions = SheetSharingService.plan(
            desired = listOf(AppUser("Owner", "owner@example.com", Role.VIEWER)),
            removed = listOf("owner@example.com"),
            existing = listOf(owner),
            keep = emptyList(),
        )
        assertTrue(actions.isEmpty())
    }

    @Test fun `the default admin and the signed in admin are never removed`() {
        val existing = listOf(
            Share("a", DEFAULT_ADMIN_EMAIL, ShareLevel.WRITER),
            Share("b", "me@example.com", ShareLevel.WRITER),
        )
        val actions = SheetSharingService.plan(
            desired = emptyList(),
            removed = listOf(DEFAULT_ADMIN_EMAIL, "me@example.com"),
            existing = existing,
            keep = listOf(DEFAULT_ADMIN_EMAIL, "Me@Example.com"),
        )
        assertTrue(actions.isEmpty())
    }

    @Test fun `someone re-added in the same save is not removed`() {
        val actions = SheetSharingService.plan(
            desired = listOf(AppUser("Ben", "ben@example.com", Role.EDITOR)),
            removed = listOf("ben@example.com"),
            existing = listOf(Share("p", "ben@example.com", ShareLevel.WRITER)),
            keep = emptyList(),
        )
        assertTrue(actions.isEmpty())
    }

    @Test fun `nothing to do when the sheet already matches`() = runBlocking {
        val sharing = FakeSharing(listOf(owner, Share("p", "ben@example.com", ShareLevel.WRITER)))
        val report = SheetSharingService.reconcile(sharing, listOf(AppUser("Ben", "ben@example.com", Role.EDITOR)))
        assertEquals(0, report.changes)
        assertEquals(listOf("The Sheet's sharing already matches the list."), report.lines())
    }

    @Test fun `one failure does not stop the others and is reported with its reason`() = runBlocking {
        val sharing = FakeSharing(listOf(owner)).apply { failFor += "bad@example.com" }
        val report = SheetSharingService.reconcile(
            sharing,
            listOf(AppUser("Bad", "bad@example.com", Role.EDITOR), AppUser("Good", "good@example.com", Role.VIEWER)),
        )
        assertEquals(listOf("good@example.com"), report.added)
        assertEquals(1, report.failed.size)
        assertEquals("bad@example.com", report.failed[0].first)
        assertTrue(report.failed[0].second.contains("refused"))
        assertTrue(report.lines().any { it.startsWith("Could not update bad@example.com") })
    }

    @Test fun `people with access who are not on the list and open links are reported, not touched`() = runBlocking {
        val sharing = FakeSharing(
            listOf(owner, Share("x", "stranger@example.com", ShareLevel.WRITER)),
            open = listOf(OpenAccess("Anyone with the link", ShareLevel.WRITER)),
        )
        val report = SheetSharingService.reconcile(sharing, listOf(AppUser("Ben", "ben@example.com", Role.EDITOR)))
        assertEquals(listOf("stranger@example.com"), report.others)
        assertEquals(ShareLevel.WRITER, level(sharing, "stranger@example.com"))
        assertTrue(report.lines().any { it.contains("Anyone with the link can edit the Sheet") })
    }

    @Test fun `plan lists adds before removals and uses the right actions`() {
        val actions = SheetSharingService.plan(
            desired = listOf(AppUser("A", "a@example.com", Role.EDITOR), AppUser("B", "b@example.com", Role.VIEWER)),
            removed = listOf("c@example.com"),
            existing = listOf(Share("pb", "b@example.com", ShareLevel.WRITER), Share("pc", "c@example.com", ShareLevel.READER)),
            keep = emptyList(),
        )
        assertEquals(
            listOf(
                ShareAction.Add("a@example.com", ShareLevel.WRITER),
                ShareAction.Change("b@example.com", "pb", ShareLevel.READER),
                ShareAction.Remove("c@example.com", "pc"),
            ),
            actions,
        )
    }
}
