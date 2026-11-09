package com.vinayaka.finance.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import com.vinayaka.finance.data.model.Contact
import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.EntryType
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun typeToString(t: EntryType): String = t.name
    @TypeConverter fun stringToType(s: String): EntryType = EntryType.valueOf(s)
}

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE year = :year AND deletedAt IS NULL ORDER BY date DESC, updatedAt DESC")
    fun observeYear(year: Int): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE year = :year")
    suspend fun allForYear(year: Int): List<Entry>

    @Query("SELECT DISTINCT year FROM entries ORDER BY year DESC")
    fun observeYears(): Flow<List<Int>>

    @Query("SELECT DISTINCT year FROM entries")
    suspend fun years(): List<Int>

    @Query("SELECT COUNT(*) FROM entries WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    @Query("SELECT DISTINCT person FROM entries WHERE person <> '' AND deletedAt IS NULL")
    fun observePersons(): Flow<List<String>>

    @Upsert suspend fun upsert(entry: Entry)
    @Upsert suspend fun upsertAll(entries: List<Entry>)
    @Query("DELETE FROM entries WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<String>)
    @Query("DELETE FROM entries WHERE id = :id") suspend fun deleteById(id: String)
    @Query("DELETE FROM entries") suspend fun clear()

    @Transaction
    suspend fun apply(upserts: List<Entry>, deletes: List<String>) {
        if (deletes.isNotEmpty()) deleteByIds(deletes)
        if (upserts.isNotEmpty()) upsertAll(upserts)
    }
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE deletedAt IS NULL")
    fun observeLive(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts")
    suspend fun all(): List<Contact>

    @Query("SELECT * FROM contacts WHERE contactId = :contactId")
    suspend fun forContact(contactId: String): List<Contact>

    @Query("SELECT COUNT(*) FROM contacts WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    @Query("SELECT DISTINCT caller FROM contacts WHERE caller <> '' AND deletedAt IS NULL")
    fun observeCallers(): Flow<List<String>>

    @Query("SELECT DISTINCT year FROM contacts")
    fun observeYears(): Flow<List<Int>>

    @Upsert suspend fun upsert(contact: Contact)
    @Upsert suspend fun upsertAll(contacts: List<Contact>)
    @Query("DELETE FROM contacts WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<String>)
    @Query("DELETE FROM contacts WHERE id = :id") suspend fun deleteById(id: String)
    @Query("DELETE FROM contacts") suspend fun clear()

    @Transaction
    suspend fun apply(upserts: List<Contact>, deletes: List<String>) {
        if (deletes.isNotEmpty()) deleteByIds(deletes)
        if (upserts.isNotEmpty()) upsertAll(upserts)
    }
}

@Database(entities = [Entry::class, Contact::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun contactDao(): ContactDao

    companion object {
        /** The phone's copy is only a cache of the Sheet, so a schema change simply starts it afresh. */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "vinayaka.db")
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
