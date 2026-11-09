package com.vinayaka.finance.data

import com.vinayaka.finance.data.model.AppUser
import com.vinayaka.finance.data.model.Role

/** Built in, so a blank Sheet still lets someone in to add the first users. Always an Admin, cannot be removed. */
const val DEFAULT_ADMIN_EMAIL = "lakshmana5296@gmail.com"

object Access {
    fun isDefaultAdmin(email: String) = email.trim().equals(DEFAULT_ADMIN_EMAIL, ignoreCase = true)

    /**
     * The role for [email], or null when the person is not on the list and so may not use the app.
     * [users] is null (or empty) when the Sheet has no Users tab yet; then only the default admin gets in.
     */
    fun resolve(users: List<AppUser>?, email: String): Role? {
        if (isDefaultAdmin(email)) return Role.ADMIN
        val e = email.trim()
        if (e.isEmpty()) return null
        return users.orEmpty().firstOrNull { it.email.equals(e, ignoreCase = true) }?.role
    }

    /** "Editor", "admin", "read only"... as typed in the Sheet. Anything unrecognised is the safest role, Viewer. */
    fun parseRole(text: String?): Role = when (text?.trim()?.lowercase()?.replace(Regex("[\\s_-]"), "")) {
        "admin", "administrator", "owner" -> Role.ADMIN
        "editor", "edit", "write" -> Role.EDITOR
        else -> Role.VIEWER
    }

    /** The list as it should be saved: the default admin is always present as Admin, and no email appears twice. */
    fun normalized(users: List<AppUser>): List<AppUser> {
        val seen = HashSet<String>()
        val rest = users.filter { it.email.isNotBlank() && !isDefaultAdmin(it.email) }
            .map { it.copy(email = it.email.trim().lowercase(), name = it.name.trim()) }
            .filter { seen.add(it.email) }
        val admin = users.firstOrNull { isDefaultAdmin(it.email) }?.name.orEmpty()
        return listOf(AppUser(admin, DEFAULT_ADMIN_EMAIL, Role.ADMIN)) + rest
    }

    fun displayName(u: AppUser) = u.name.ifBlank { u.email.substringBefore('@') }
}
