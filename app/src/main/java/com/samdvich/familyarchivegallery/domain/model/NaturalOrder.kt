package com.samdvich.familyarchivegallery.domain.model

object NaturalOrder : Comparator<String> {
    override fun compare(first: String, second: String): Int {
        var firstIndex = 0
        var secondIndex = 0

        while (firstIndex < first.length && secondIndex < second.length) {
            val firstChar = first[firstIndex]
            val secondChar = second[secondIndex]

            if (firstChar.isDigit() && secondChar.isDigit()) {
                val firstEnd = first.runEnd(firstIndex, Char::isDigit)
                val secondEnd = second.runEnd(secondIndex, Char::isDigit)
                val firstNumber = first.substring(firstIndex, firstEnd).trimStart('0').ifEmpty { "0" }
                val secondNumber = second.substring(secondIndex, secondEnd).trimStart('0').ifEmpty { "0" }

                val lengthResult = firstNumber.length.compareTo(secondNumber.length)
                if (lengthResult != 0) return lengthResult

                val numberResult = firstNumber.compareTo(secondNumber)
                if (numberResult != 0) return numberResult

                firstIndex = firstEnd
                secondIndex = secondEnd
            } else {
                val charResult = firstChar.lowercaseChar().compareTo(secondChar.lowercaseChar())
                if (charResult != 0) return charResult
                firstIndex++
                secondIndex++
            }
        }

        return first.length.compareTo(second.length)
    }

    private fun String.runEnd(start: Int, predicate: (Char) -> Boolean): Int {
        var index = start
        while (index < length && predicate(this[index])) index++
        return index
    }
}
