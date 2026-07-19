package com.samdvich.familyarchivegallery

import com.samdvich.familyarchivegallery.domain.model.NaturalOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test
    fun numericPartsAreSortedNaturally() {
        val values = listOf("photo20.jpg", "photo2.jpg", "photo10.jpg", "photo1.jpg")

        assertEquals(
            listOf("photo1.jpg", "photo2.jpg", "photo10.jpg", "photo20.jpg"),
            values.sortedWith(NaturalOrder)
        )
    }

    @Test
    fun comparisonIsCaseInsensitive() {
        val values = listOf("Travel10", "travel2", "Family")

        assertEquals(listOf("Family", "travel2", "Travel10"), values.sortedWith(NaturalOrder))
    }
}
