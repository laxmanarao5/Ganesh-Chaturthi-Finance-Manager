package com.vinayaka.finance

import com.vinayaka.finance.data.Access
import com.vinayaka.finance.data.DEFAULT_ADMIN_EMAIL
import com.vinayaka.finance.data.model.AppUser
import com.vinayaka.finance.data.model.Role
import com.vinayaka.finance.data.sheets.FakeSheetGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTest {
    private val users = listOf(
        AppUser("Asha", "asha@example.com", Role.VIEWER),
        AppUser("Ben", "Ben@Example.com", Role.EDITOR),
        AppUser("Chitra", "chitra@example.com", Role.ADMIN),
    )

    @Test fun `default admin is hard coded and always an admin`() {
        assertEquals("lakshmana5296@gmail.com", DEFAULT_ADMIN_EMAIL)
        assertEquals(Role.ADMIN, Access.resolve(null, "lakshmana5296@gmail.com"))        // blank sheet, no Users tab
        assertEquals(Role.ADMIN, Access.resolve(emptyList(), " Lakshmana5296@Gmail.com ")) // case and spaces ignored
        // Even if someone lists the default admin with a lower role, the built-in rule wins.
        assertEquals(Role.ADMIN, Access.resolve(listOf(AppUser("", DEFAULT_ADMIN_EMAIL, Role.VIEWER)), DEFAULT_ADMIN_EMAIL))
    }

    @Test fun `only listed emails get in and each gets their own role`() {
        assertEquals(Role.VIEWER, Access.resolve(users, "asha@example.com"))
        assertEquals(Role.EDITOR, Access.resolve(users, "ben@example.com"))          // case-insensitive
        assertEquals(Role.ADMIN, Access.resolve(users, "CHITRA@example.com"))
        assertNull(Access.resolve(users, "stranger@example.com"))
        assertNull(Access.resolve(null, "asha@example.com"))                          // no Users tab: nobody but the default admin
        assertNull(Access.resolve(users, ""))
    }

    @Test fun `what each role may do`() {
        assertFalse(Role.VIEWER.canEdit); assertFalse(Role.VIEWER.canManageUsers)
        assertTrue(Role.EDITOR.canEdit); assertFalse(Role.EDITOR.canManageUsers)
        assertTrue(Role.ADMIN.canEdit); assertTrue(Role.ADMIN.canManageUsers)
    }

    @Test fun `roles typed by hand in the sheet are understood and unknown ones fall back to viewer`() {
        assertEquals(Role.ADMIN, Access.parseRole("Admin"))
        assertEquals(Role.ADMIN, Access.parseRole(" administrator "))
        assertEquals(Role.EDITOR, Access.parseRole("editor"))
        assertEquals(Role.EDITOR, Access.parseRole("EDITOR "))
        assertEquals(Role.VIEWER, Access.parseRole("Viewer"))
        assertEquals(Role.VIEWER, Access.parseRole("read only"))
        assertEquals(Role.VIEWER, Access.parseRole("ReadOnly"))
        assertEquals(Role.VIEWER, Access.parseRole("something odd"))
        assertEquals(Role.VIEWER, Access.parseRole(null))
    }

    @Test fun `saving always keeps the default admin first, once, and drops blanks and duplicates`() {
        val saved = Access.normalized(
            listOf(
                AppUser("Asha", "ASHA@example.com", Role.VIEWER),
                AppUser("Asha again", "asha@example.com", Role.ADMIN),
                AppUser("No email", "", Role.EDITOR),
                AppUser("Me", DEFAULT_ADMIN_EMAIL.uppercase(), Role.VIEWER),
            ),
        )
        assertEquals(listOf(DEFAULT_ADMIN_EMAIL, "asha@example.com"), saved.map { it.email })
        assertEquals(Role.ADMIN, saved[0].role)
        assertEquals("Me", saved[0].name)
        assertEquals(Role.VIEWER, saved[1].role)                                      // first entry wins
        assertEquals(listOf(DEFAULT_ADMIN_EMAIL), Access.normalized(emptyList()).map { it.email })
    }

    @Test fun `a blank sheet lets the default admin in, add users, and then those users get in`() {
        val sheet = FakeSheetGateway()
        runBlocking {
            assertNull(sheet.readUsers())
            assertEquals(Role.ADMIN, Access.resolve(sheet.readUsers(), DEFAULT_ADMIN_EMAIL))
            assertNull(Access.resolve(sheet.readUsers(), "ben@example.com"))
            sheet.writeUsers(Access.normalized(users))
            assertEquals(Role.EDITOR, Access.resolve(sheet.readUsers(), "ben@example.com"))
            assertEquals(Role.ADMIN, Access.resolve(sheet.readUsers(), DEFAULT_ADMIN_EMAIL))
            // Removing someone locks them out again.
            sheet.writeUsers(Access.normalized(users.filter { it.email != "Ben@Example.com" }))
            assertNull(Access.resolve(sheet.readUsers(), "ben@example.com"))
        }
    }
}
