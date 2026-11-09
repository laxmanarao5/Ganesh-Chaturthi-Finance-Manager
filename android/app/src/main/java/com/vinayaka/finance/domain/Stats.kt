package com.vinayaka.finance.domain

import com.vinayaka.finance.data.model.Entry
import com.vinayaka.finance.data.model.EntryType

/** Headline numbers for one year. All amounts are in paise. */
data class Totals(
    /** Income that someone has collected. */
    val collected: Long,
    /** Income promised but not collected by anyone yet. */
    val toCollect: Long,
    val spent: Long,
) {
    val listed: Long get() = collected + toCollect
    /** Cash that should be in people's hands: collected minus spent. */
    val cash: Long get() = collected - spent
}

data class PersonTotals(val person: String, val collected: Long, val spent: Long) {
    val net: Long get() = collected - spent
}

/** Everything the app calculates, so the Sheet only needs to hold plain rows. */
object Stats {
    private fun live(entries: List<Entry>) = entries.filter { !it.isDeleted }

    fun totals(entries: List<Entry>): Totals {
        val e = live(entries)
        return Totals(
            collected = e.filter { it.type.isIncome && it.person.isNotBlank() }.sumOf { it.amount },
            toCollect = e.filter { it.isPending }.sumOf { it.amount },
            spent = e.filter { !it.type.isIncome }.sumOf { it.amount },
        )
    }

    /** One row per person who collected or paid anything, biggest collector first. */
    fun byPerson(entries: List<Entry>): List<PersonTotals> {
        val e = live(entries).filter { it.person.isNotBlank() }
        return e.groupBy { it.person.trim().lowercase() }.map { (_, rows) ->
            PersonTotals(
                person = rows.first().person.trim(),
                collected = rows.filter { it.type.isIncome }.sumOf { it.amount },
                spent = rows.filter { !it.type.isIncome }.sumOf { it.amount },
            )
        }.sortedWith(compareByDescending<PersonTotals> { it.collected }.thenBy { it.person.lowercase() })
    }

    /** Amount per category, categories of the type first in their usual order. */
    fun byCategory(entries: List<Entry>, type: EntryType): List<Pair<String, Long>> {
        val sums = live(entries).filter { it.type == type }.groupBy { it.category.ifBlank { "Other" } }
            .mapValues { (_, v) -> v.sumOf { it.amount } }
        val ordered = type.categories.filter { it in sums }.map { it to sums.getValue(it) }
        return ordered + sums.filterKeys { it !in type.categories }.toList().sortedByDescending { it.second }
    }

    /** What one person collected or paid, split by type and category. */
    fun personBreakdown(entries: List<Entry>, person: String): List<Triple<EntryType, String, Long>> =
        live(entries).filter { it.person.equals(person.trim(), ignoreCase = true) }
            .groupBy { it.type to it.category.ifBlank { "Other" } }
            .map { (k, v) -> Triple(k.first, k.second, v.sumOf { it.amount }) }
            .sortedWith(compareBy({ it.first.ordinal }, { -it.third }))

    /** Reuse the existing spelling of a name ("bhanu" -> "Bhanu") so one person is not split in two. */
    fun canonicalPerson(name: String, known: Collection<String>): String {
        val t = name.trim()
        return known.firstOrNull { it.equals(t, ignoreCase = true) } ?: t
    }

    /** People to offer in pickers: committee members plus anyone already named on a record. */
    fun people(members: Collection<String>, entries: List<Entry>, extra: Collection<String> = emptyList()): List<String> {
        val all = LinkedHashMap<String, String>()
        (members + entries.map { it.person } + extra).map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { all.putIfAbsent(it.lowercase(), it) }
        return all.values.sortedBy { it.lowercase() }
    }
}
