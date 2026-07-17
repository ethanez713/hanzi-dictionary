package com.hanzidictionary.core

/**
 * Positional/combining radical forms and their parent characters (spec §9 component
 * page "positional variants (水/氵/氺)"). Fixed Unicode/paleographic facts.
 *
 * KEEP IN SYNC with pipeline/pipeline/radicals.py (RADICAL_PARENT) — same mapping,
 * duplicated because the pack doesn't store it and core stays dependency-free.
 */
val RADICAL_PARENT: Map<String, String> = mapOf(
    "讠" to "言", "訁" to "言",
    "氵" to "水", "氺" to "水",
    "忄" to "心",
    "扌" to "手",
    "亻" to "人",
    "犭" to "犬",
    "饣" to "食", "飠" to "食",
    "纟" to "糸", "糹" to "糸",
    "钅" to "金", "釒" to "金",
    "衤" to "衣",
    "礻" to "示",
    "刂" to "刀",
    "灬" to "火",
    "冫" to "冰",
    "艹" to "艸",
    "辶" to "辵", "廴" to "辵",
    "攵" to "攴",
    "牜" to "牛",
    "罒" to "网",
    "𤣩" to "玉",
    "⺼" to "肉",
)

/** All combining forms of [char] (水 -> [氵, 氺]); empty if none. */
fun radicalFormsOf(char: String): List<String> =
    RADICAL_PARENT.filterValues { it == char }.keys.toList()

/** The parent character [char] abbreviates (氵 -> 水), or null if it isn't a form. */
fun radicalParentOf(char: String): String? = RADICAL_PARENT[char]
