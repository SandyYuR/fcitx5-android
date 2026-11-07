/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.stats

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.ArrayDeque

internal object StatsLimits {
    const val MAX_SCHEMAS = 256
    const val MAX_WORDS = 512
    const val MAX_KEYS = 128
    const val MAX_CODE_LENGTH = 256
    const val MAX_COMMIT_UNITS = 4096
    const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
    const val MAX_BUCKETS = 100_000
    const val MAX_PENDING = 1024
    fun sum(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    fun schema(value: String) = value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl)
    fun label(value: String) = value.isNotBlank() && value.length <= 64 && value.none(Char::isISOControl)
}

@Serializable
internal data class StatsBucket(val day: String, val schemaId: String, var totals: StatsTotals = StatsTotals(),
    val codeLengths: MutableMap<Int, Long> = linkedMapOf(), val wordLengths: MutableMap<Int, Long> = linkedMapOf(),
    val heat: MutableMap<String, Long> = linkedMapOf(), val words: MutableMap<String, Long> = linkedMapOf(),
    val speedTail: MutableList<StatsSpeedSample> = mutableListOf()) {
    fun key(deletion: Boolean) {
        totals = totals.copy(keys = StatsLimits.sum(totals.keys, 1), deletions = StatsLimits.sum(totals.deletions, if (deletion) 1 else 0))
    }
    fun heat(label: String) { if (label in heat || heat.size < StatsLimits.MAX_KEYS) increment(heat, label) }
    fun commit(characters: Int, codeLength: Int, text: String?) {
        totals = totals.copy(characters = StatsLimits.sum(totals.characters, characters.toLong()),
            commits = StatsLimits.sum(totals.commits, 1), totalCodeLength = StatsLimits.sum(totals.totalCodeLength, codeLength.toLong()))
        increment(codeLengths, codeLength)
        increment(wordLengths, characters)
        if (text != null) StatsText.words(text).forEach { word ->
            if (word !in words && words.size >= StatsLimits.MAX_WORDS) words.entries.minWithOrNull(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })?.let { words.remove(it.key) }
            increment(words, word)
        }
    }
    fun speed(sample: StatsSpeedSample, peak: StatsSpeedSample?) {
        var next = totals.copy(activeMillis = StatsLimits.sum(totals.activeMillis, sample.millis),
            speedCharacters = StatsLimits.sum(totals.speedCharacters, sample.characters))
        if (peak != null && peak.cpm > totals.peakCpm) next = next.copy(peakCpm = peak.cpm, peakKps = peak.kps, peakCodeLength = peak.codeLength)
        totals = next
    }
    fun clearPeaks() { totals = totals.copy(peakCpm = 0.0, peakKps = 0.0, peakCodeLength = 0.0); speedTail.clear() }
    companion object {
        fun <T> increment(map: MutableMap<T, Long>, key: T, amount: Long = 1) { map[key] = StatsLimits.sum(map[key] ?: 0, amount) }
    }
}

internal object StatsText {
    private fun han(cp: Int) = cp == 0x3007 || cp in 0x3400..0x4dbf || cp in 0x4e00..0x9fff ||
        cp in 0xf900..0xfaff || cp in 0x20000..0x2ee5f || cp in 0x2f800..0x2fa1f || cp in 0x30000..0x3347f
    fun characters(text: String): Int {
        if (text.isEmpty() || text.length > StatsLimits.MAX_COMMIT_UNITS) return 0
        var offset = 0
        var count = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            if (!han(cp)) return 0
            offset += Character.charCount(cp); count++
        }
        return count
    }
    /** Local vocabulary uses whole candidate commits and individual Han characters, without a tokenizer. */
    fun words(text: String): List<String> {
        val count = characters(text)
        if (count == 0) return emptyList()
        val result = ArrayList<String>()
        if (count in 2..12) result += text
        var offset = 0
        repeat(minOf(count, 64)) {
            val end = offset + Character.charCount(text.codePointAt(offset))
            result += text.substring(offset, end); offset = end
        }
        return result
    }
}

@Serializable
internal data class StatsSpeedSample(val millis: Long, val characters: Long, val keys: Long, val codes: Long) {
    val cpm get() = characters * 60_000.0 / millis
    val kps get() = keys * 1000.0 / millis
    val codeLength get() = if (characters > 0) codes.toDouble() / characters else 0.0
}

internal class StatsSpeedWindow(restored: List<StatsSpeedSample> = emptyList()) {
    private val samples = ArrayDeque<StatsSpeedSample>(restored)
    fun add(sample: StatsSpeedSample): StatsSpeedSample? {
        require(sample.millis >= 1000 && sample.characters > 0)
        samples.addLast(sample)
        var time = samples.fold(0L) { total, it -> StatsLimits.sum(total, it.millis) }
        while (samples.size > 1 && time - samples.first.millis >= 10_000) time -= samples.removeFirst().millis
        if (time < 10_000) return null
        return StatsSpeedSample(time, samples.fold(0L) { total, it -> StatsLimits.sum(total, it.characters) },
            samples.fold(0L) { total, it -> StatsLimits.sum(total, it.keys) }, samples.fold(0L) { total, it -> StatsLimits.sum(total, it.codes) })
    }
    fun snapshot(): List<StatsSpeedSample> = samples.toList()
}

internal fun combineTotals(values: Iterable<StatsTotals>): StatsTotals {
    var result = StatsTotals()
    values.forEach { next ->
        val peak = if (next.peakCpm > result.peakCpm) next else result
        result = StatsTotals(StatsLimits.sum(result.characters, next.characters), StatsLimits.sum(result.commits, next.commits),
            StatsLimits.sum(result.keys, next.keys), StatsLimits.sum(result.deletions, next.deletions),
            StatsLimits.sum(result.totalCodeLength, next.totalCodeLength), StatsLimits.sum(result.activeMillis, next.activeMillis),
            StatsLimits.sum(result.speedCharacters, next.speedCharacters), peak.peakCpm, peak.peakKps, peak.peakCodeLength)
    }
    return result
}

@Serializable
internal data class StatsArchive(val format: String = "zhongzhou-input-statistics", val version: Int = 1,
    val schemas: List<StatsSchema>, val buckets: List<StatsBucket>, val layouts: Map<String, StatsLayout> = emptyMap()) {
    fun validate() {
        require(format == "zhongzhou-input-statistics" && version == 1) { "Unsupported input statistics format" }
        require(schemas.size <= StatsLimits.MAX_SCHEMAS && schemas.map { it.id }.distinct().size == schemas.size)
        schemas.forEach { require(StatsLimits.schema(it.id) && it.name.length <= 128 && it.careerCharacters >= 0) }
        val ids = schemas.map { it.id }.toSet()
        require(buckets.size <= StatsLimits.MAX_BUCKETS && buckets.map { it.day to it.schemaId }.distinct().size == buckets.size)
        buckets.forEach { bucket ->
            require(bucket.schemaId in ids && StatsRange.validDay(bucket.day))
            val t = bucket.totals
            require(listOf(t.characters, t.commits, t.keys, t.deletions, t.totalCodeLength, t.activeMillis, t.speedCharacters).all { it >= 0 })
            require(t.deletions <= t.keys && t.speedCharacters <= t.characters)
            require(listOf(t.peakCpm, t.peakKps, t.peakCodeLength).all { it.isFinite() && it >= 0 })
            require(bucket.codeLengths.size <= StatsLimits.MAX_CODE_LENGTH + 1 && bucket.codeLengths.keys.all { it in 0..StatsLimits.MAX_CODE_LENGTH })
            require(bucket.wordLengths.size <= StatsLimits.MAX_COMMIT_UNITS && bucket.wordLengths.keys.all { it in 1..StatsLimits.MAX_COMMIT_UNITS })
            require(bucket.heat.size <= StatsLimits.MAX_KEYS && bucket.heat.keys.all(StatsLimits::label))
            require(bucket.words.size <= StatsLimits.MAX_WORDS && bucket.words.keys.all { StatsText.characters(it) in 1..12 })
            require((bucket.codeLengths.values + bucket.wordLengths.values + bucket.heat.values + bucket.words.values).all { it > 0 })
            require(bucket.speedTail.size <= 10 && bucket.speedTail.all {
                it.millis in 1000..31_622_400_000L && it.characters > 0 && it.keys >= 0 && it.codes >= 0
            })
        }
        require(layouts.size <= StatsLimits.MAX_SCHEMAS && layouts.keys.all { it in ids })
        layouts.values.forEach(::validateLayout)
    }
    companion object {
        val json = Json { encodeDefaults = true }
        fun decode(text: String): StatsArchive {
            require(text.toByteArray(Charsets.UTF_8).size <= StatsLimits.MAX_ARCHIVE_BYTES) { "Input statistics file exceeds 32 MiB" }
            return json.decodeFromString<StatsArchive>(text).also { it.validate() }
        }
        fun validateLayout(layout: StatsLayout) {
            require(layout.rows.size <= 32 && layout.rows.sumOf { it.size } <= StatsLimits.MAX_KEYS)
            require(layout.rows.flatten().all(StatsLimits::label))
            require(layout.geometry.size <= StatsLimits.MAX_KEYS)
            layout.geometry.forEach {
                require(StatsLimits.label(it.label) && listOf(it.x, it.y, it.width, it.height).all { number -> number.isFinite() && number >= 0 && number <= 100_000 })
            }
        }
    }
}
