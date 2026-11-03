package com.hanzidictionary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScriptsTest {
    private fun edge(from: String, fromChar: String, to: String, toChar: String) =
        VariantView(from, to, fromChar, toChar, mappingType = null)

    @Test
    fun aSimplifiedCharacterShowsItsTraditionalForms() {
        val edges = listOf(edge("U+767C", "發", "U+53D1", "发"), edge("U+9AEE", "髮", "U+53D1", "发"))
        val other = otherScript("U+53D1", edges)
        assertEquals(true, other?.traditional)
        assertEquals(listOf("發", "髮"), other?.forms?.map { it.char })
    }

    @Test
    fun aTraditionalCharacterShowsItsSimplifiedForm() {
        val other = otherScript("U+8F15", listOf(edge("U+8F15", "輕", "U+8F7B", "轻")))
        assertEquals(false, other?.traditional)
        assertEquals(listOf(ScriptForm("U+8F7B", "轻")), other?.forms)
    }

    @Test
    fun theTraditionalDirectionWinsWhenACharacterHasBoth() {
        val edges = listOf(edge("U+4E7E", "乾", "U+5E72", "干"), edge("U+5E72", "干", "U+X", "x"))
        assertEquals(true, otherScript("U+5E72", edges)?.traditional)
    }

    @Test
    fun aCharacterWrittenTheSameInBothScriptsHasNone() {
        assertNull(otherScript("U+4EBA", emptyList()))
        // A self-edge is not a counterpart.
        assertNull(otherScript("U+4EBA", listOf(edge("U+4EBA", "人", "U+4EBA", "人"))))
    }

    @Test
    fun hidingTraditionalDropsOnlyTraditionalOnlyCharacters() {
        fun f(id: String, scripts: String?) = SearchResult(id, id, null, null, null, null, scripts = scripts)
        val finds = listOf(f("a", "simp"), f("b", "trad"), f("c", "both"), f("d", null))
        assertEquals(finds, filterScripts(finds, showTraditional = true))
        assertEquals(listOf("a", "c", "d"), filterScripts(finds, showTraditional = false).map { it.glyphId })
    }
}
