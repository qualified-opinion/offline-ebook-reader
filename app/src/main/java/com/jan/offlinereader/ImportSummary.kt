package com.jan.offlinereader

internal fun importSummary(added: Int, duplicates: Int, failures: List<String>): String =
    buildList {
        if (added > 0) add(if (added == 1) "1 book added." else "$added books added.")
        if (duplicates > 0) add(if (duplicates == 1) "1 book was already in your library." else "$duplicates books were already in your library.")
        addAll(failures.take(2))
        if (failures.size > 2) add("And ${failures.size - 2} more failed.")
        if (isEmpty()) add("No books selected.")
    }.joinToString(" ")
