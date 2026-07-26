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

/**
 * What each combining form is *called* — the name a learner hears and says (氵 is "三点水",
 * three-dots-water), not a pronunciation.
 *
 * These forms only ever appear inside other characters, so the reading Unihan carries for
 * them describes the parent character they abbreviate, not anything you'd ever pronounce on
 * seeing one: printing "shui" beside 氵 invites reading it as a sound the component lends,
 * which is exactly backwards for a radical that lends meaning. The name is the useful, and
 * true, thing to show instead.
 *
 * Deliberately wider than [RADICAL_PARENT]: several of these (宀, 勹, 冂) abbreviate no
 * single parent character but are still named forms rather than standalone characters. A
 * form missing from here just falls back to its reading, so it is safer to omit a radical
 * than to guess at its name — entries here are the conventional ones only.
 */
val RADICAL_NAME: Map<String, String> = mapOf(
    "氵" to "三点水", "氺" to "水字底",
    "冫" to "两点水",
    "亻" to "单人旁", "彳" to "双人旁",
    "讠" to "言字旁", "訁" to "言字旁",
    "扌" to "提手旁",
    "忄" to "竖心旁",
    "犭" to "反犬旁",
    "纟" to "绞丝旁", "糹" to "绞丝旁",
    "钅" to "金字旁", "釒" to "金字旁",
    "饣" to "食字旁", "飠" to "食字旁",
    "衤" to "衣字旁",
    "礻" to "示字旁",
    "刂" to "立刀旁",
    "灬" to "四点底",
    "艹" to "草字头",
    "辶" to "走之底", "廴" to "建之旁",
    "攵" to "反文旁",
    "牜" to "牛字旁",
    "罒" to "四字头",
    "𤣩" to "王字旁",
    "⺼" to "肉月旁",
    "宀" to "宝盖头", "冖" to "秃宝盖",
    "疒" to "病字旁",
    "阝" to "双耳旁",
    "勹" to "包字头",
    "匚" to "三框儿",
    "卩" to "单耳旁",
    "厶" to "私字儿",
    "耂" to "老字头",
    "⺮" to "竹字头",
    "爫" to "爪字头",
    "彡" to "三撇儿",
    "冂" to "同字框",
    "尢" to "尤字旁",
    "夂" to "折文旁",
    "廾" to "弄字底",
)

/** All combining forms of [char] (水 -> [氵, 氺]); empty if none. */
fun radicalFormsOf(char: String): List<String> =
    RADICAL_PARENT.filterValues { it == char }.keys.toList()

/** The parent character [char] abbreviates (氵 -> 水), or null if it isn't a form. */
fun radicalParentOf(char: String): String? = RADICAL_PARENT[char]

/** The conventional name of [char] as a radical form (氵 -> "三点水"), or null when [char]
 * is a standalone character in its own right and so has a reading worth showing instead. */
fun radicalNameOf(char: String): String? = RADICAL_NAME[char]
