package com.vinayaka.finance.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vinayaka.finance.BuildConfig
import com.vinayaka.finance.data.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Prefs(
    val spreadsheetId: String = "",
    val email: String = "",
    val name: String = "",
    val demo: Boolean = false,
    val knownYears: Set<Int> = emptySet(),
    val lastSyncMs: Long = 0,
    /** From the Users tab at the last access check. Viewer until the first check succeeds. */
    val role: Role = Role.VIEWER,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Committee names from the Sheet's Users tab, for the collector pickers. */
    val people: Set<String> = emptySet(),
    /** The name this phone's user collects under, used as the default on new records. */
    val myName: String = "",
    /** Which sheet the data on this phone came from; a different one means the local copy is stale. */
    val dataSheetId: String = "",
    /** True once the Users tab has been read for this account. */
    val accessChecked: Boolean = false,
    /** Set when this Google account is not on the Users list (or cannot open the Sheet). */
    val deniedMessage: String = "",
) {
    val denied: Boolean get() = !demo && deniedMessage.isNotEmpty()
    /** True when this build is fixed to one Sheet (set at build time), so the app never asks for a link. */
    val sheetFixed: Boolean get() = BuildConfig.SPREADSHEET_ID.isNotEmpty()
    val signedIn get() = demo || email.isNotEmpty()
    val connected get() = demo || spreadsheetId.isNotEmpty()
    val who get() = if (demo) "demo@local" else email
}

class Settings(private val context: Context) {
    private object K {
        val sheet = stringPreferencesKey("spreadsheet_id")
        val email = stringPreferencesKey("email")
        val name = stringPreferencesKey("name")
        val demo = booleanPreferencesKey("demo")
        val years = stringSetPreferencesKey("known_years")
        val last = longPreferencesKey("last_sync")
        val role = stringPreferencesKey("role")
        val theme = stringPreferencesKey("theme")
        val people = stringSetPreferencesKey("people")
        val myName = stringPreferencesKey("my_name")
        val dataSheet = stringPreferencesKey("data_sheet")
        val accessChecked = booleanPreferencesKey("access_checked")
        val denied = stringPreferencesKey("denied_message")
    }

    val flow: Flow<Prefs> = context.store.data.map { p ->
        Prefs(
            spreadsheetId = BuildConfig.SPREADSHEET_ID.ifEmpty { p[K.sheet].orEmpty() },
            dataSheetId = p[K.dataSheet].orEmpty(),
            email = p[K.email].orEmpty(),
            name = p[K.name].orEmpty(),
            demo = p[K.demo] ?: false,
            knownYears = p[K.years].orEmpty().mapNotNull { it.toIntOrNull() }.toSet(),
            lastSyncMs = p[K.last] ?: 0,
            role = runCatching { Role.valueOf(p[K.role] ?: "VIEWER") }.getOrDefault(Role.VIEWER),
            accessChecked = p[K.accessChecked] ?: false,
            deniedMessage = p[K.denied].orEmpty(),
            theme = runCatching { ThemeMode.valueOf(p[K.theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            people = p[K.people].orEmpty(),
            myName = p[K.myName].orEmpty(),
        )
    }

    suspend fun current(): Prefs = flow.first()

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.store.edit { block(it) }
    }

    suspend fun setUser(email: String, name: String) = edit { it[K.email] = email; it[K.name] = name }
    suspend fun setDemo(on: Boolean) = edit { it[K.demo] = on }
    suspend fun setSpreadsheet(id: String) = edit { it[K.sheet] = id; it.remove(K.years); it.remove(K.last) }
    suspend fun setSynced(ms: Long, years: List<Int>, role: Role, people: Set<String>, defaultMyName: String, sheetId: String) = edit {
        it[K.dataSheet] = sheetId
        it[K.last] = ms; it[K.years] = years.map(Int::toString).toSet(); it[K.role] = role.name
        it[K.people] = people
        if (it[K.myName].isNullOrBlank() && defaultMyName.isNotBlank()) it[K.myName] = defaultMyName
    }
    suspend fun setMyName(name: String) = edit { it[K.myName] = name.trim() }
    suspend fun setRole(role: Role) = edit { it[K.role] = role.name }

    /** This account may use the app with [role]; remembers the committee's names for the pickers. */
    suspend fun setAccess(role: Role, people: Set<String>, defaultMyName: String) = edit {
        it[K.role] = role.name; it[K.accessChecked] = true; it.remove(K.denied)
        it[K.people] = people
        if (it[K.myName].isNullOrBlank() && defaultMyName.isNotBlank()) it[K.myName] = defaultMyName
    }

    /** This account is not allowed in. The reason is shown on the "no access" screen. */
    suspend fun setDenied(message: String) = edit {
        it[K.denied] = message; it[K.accessChecked] = true; it[K.role] = Role.VIEWER.name
        it.remove(K.last); it.remove(K.years)
    }
    suspend fun setTheme(mode: ThemeMode) = edit { it[K.theme] = mode.name }
    suspend fun clearAccount() = edit {
        it.remove(K.email); it.remove(K.name); it.remove(K.sheet); it.remove(K.demo)
        it.remove(K.years); it.remove(K.last); it.remove(K.role); it.remove(K.people); it.remove(K.myName); it.remove(K.dataSheet)
        it.remove(K.accessChecked); it.remove(K.denied)
    }
}
