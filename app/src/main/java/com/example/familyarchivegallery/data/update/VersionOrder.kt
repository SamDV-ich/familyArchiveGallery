package com.example.familyarchivegallery.data.update

object VersionOrder {
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    fun compare(first: String, second: String): Int {
        val firstParts = numericParts(first)
        val secondParts = numericParts(second)
        val size = maxOf(firstParts.size, secondParts.size)
        repeat(size) { index ->
            val result = firstParts.getOrElse(index) { 0 }.compareTo(secondParts.getOrElse(index) { 0 })
            if (result != 0) return result
        }
        return 0
    }

    private fun numericParts(value: String): List<Int> = value
        .removePrefix("v")
        .substringBefore('-')
        .split('.')
        .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
