/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.log

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import org.fcitx.fcitx5.android.utils.LogFileManager

/**
 * 一行 threadtime 日志解析出来的字段。
 *
 * logcat 会把 tag 用空格补齐到固定宽度（I Tprobe  : msg），而 LogSink 写的是
 * %c %s: %s（I Tprobe: msg）。同一事件在两处来源的原始行因此并不相等，
 * 去重只能比较字段，不能比较整行。
 */
internal data class ParsedLogLine(
    val pid: Int,
    val level: Char,
    val tid: Int,
    val tag: String,
    val message: String,
    val epochMillis: Long
) {
    /**
     * 事件的跨来源身份。时间不进这个字符串：LogSink 记的是 System.currentTimeMillis()，
     * logd 记的是接收时刻，同一事件两边会差几毫秒，要求相等反而匹配不上；两者只在
     * [DUPLICATE_TIME_TOLERANCE_MS] 的邻域内配对，见 [DuplicateCandidates]。
     * pid 参与比较：历史文件跨进程保留，不同进程里 tid 与正文可能偶然相同。
     */
    val identity: String get() = "$pid\t$tid\t$level\t$tag\t$message"
}

/**
 * MM-DD HH:mm:ss.mmm  PID  TID LEVEL TAG: message
 *
 * 捕获组依次是月、日、时、分、秒、毫秒、PID、TID、级别、TAG: message。
 */
private val threadtimeRegex = Regex(
    """^(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.*)$"""
)

/** 跨年容忍：12 月的历史行在 1 月被读到时会落到未来，那时按上一年解释。 */
private const val YEAR_ROLLOVER_TOLERANCE_MS = 36L * 60 * 60 * 1000

/**
 * 同一条事件在两个来源之间的时间差容忍。
 *
 * LogSink 记的是 System.currentTimeMillis()，logd 记的是它收到消息的时刻，同一事件两边
 * 只差几毫秒，要求相等就永远匹配不上。窗口也不能宽：同一个身份对应的就是同一条正文，
 * 日志里反复出现的同文事件本身是不同事件（例如 "service.onStartInput restarting=false"），
 * 窗口越宽，越可能拿一条早已被 logcat 缓冲区丢掉的旧记录去抵消新事件。
 */
private const val DUPLICATE_TIME_TOLERANCE_MS = 500L

/**
 * 一个身份在持久历史里的候选时间。
 *
 * 匹配必须落在 [DUPLICATE_TIME_TOLERANCE_MS] 的邻域里：只按字段身份计数的话，一条旧的
 * 同文事件会抵消一次新的同文事件。历史读取完成之后、logcat 快照完成之前写下的那条事件
 * 正落在这种窗口里——它的 logcat 记录还在缓冲区，计数却被旧记录占着，于是这一行被丢掉，
 * resumeFrom 又推到它之后，实时 reader 也不会补回来。
 */
private class DuplicateCandidates(timestamps: LongArray) {

    private val times = timestamps
    private val consumed = BooleanArray(times.size)

    /** 上次查询的时间；快照按时间递增，只有递增时才能把游标推到窗口下界。 */
    private var lastQuery = Long.MIN_VALUE
    private var cursor = 0

    /** 消耗掉时间邻域内**最接近**的那条候选；邻域里没有候选时返回 false。 */
    fun consume(epochMillis: Long): Boolean {
        // 快照时间倒退（不同缓冲区交错）时退回从头扫描，否则游标会越过仍可用的候选。
        if (epochMillis < lastQuery) cursor = 0
        val lowerBound = epochMillis - DUPLICATE_TIME_TOLERANCE_MS
        // 下界对倒退的查询同样成立：只重置游标却不推下界的话，下面的扫描只看上界，
        // 比邻域早得多的一条同文候选也会被当成「最接近」而消耗掉，新事件跟着一起消失。
        while (cursor < times.size && times[cursor] < lowerBound) cursor++
        lastQuery = epochMillis
        val upperBound = epochMillis + DUPLICATE_TIME_TOLERANCE_MS
        var best = -1
        var index = cursor
        while (index < times.size && times[index] <= upperBound) {
            if (!consumed[index] &&
                (best < 0 || abs(times[index] - epochMillis) < abs(times[best] - epochMillis))
            ) {
                best = index
            }
            index++
        }
        if (best < 0) return false
        consumed[best] = true
        return true
    }
}

/**
 * threadtime 只写月日、不写年份，年份要用读取时刻补全。
 *
 * 复用同一个 Calendar：历史文件可能有几十万行，每行新建一个 Calendar 会把整次合并
 * 拖成几秒。
 */
internal class ThreadtimeClock(private val nowMillis: Long) {

    private val calendar = Calendar.getInstance()

    private val year: Int = Calendar.getInstance()
        .apply { timeInMillis = nowMillis }
        .get(Calendar.YEAR)

    fun epochMillis(
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        millis: Int
    ): Long {
        val currentYear = at(year, month, day, hour, minute, second, millis)
        return if (currentYear > nowMillis + YEAR_ROLLOVER_TOLERANCE_MS) {
            at(year - 1, month, day, hour, minute, second, millis)
        } else {
            currentYear
        }
    }

    private fun at(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        millis: Int
    ): Long {
        calendar.clear()
        calendar.set(year, month - 1, day, hour, minute, second)
        calendar.set(Calendar.MILLISECOND, millis)
        return calendar.timeInMillis
    }
}

/**
 * 解析一行 threadtime 日志；不是 threadtime 格式（例如 --------- beginning of main
 * 这类分节行、没有前缀的堆栈帧）时返回 null。
 */
internal fun parseLogLine(line: String, clock: ThreadtimeClock): ParsedLogLine? {
    val groups = threadtimeRegex.find(line)?.groupValues ?: return null
    val pid = groups[7].toIntOrNull() ?: return null
    val tid = groups[8].toIntOrNull() ?: return null
    val rest = groups[10]
    // tag 与正文以第一个 ": " 分界；正文里出现的 ": " 不再切开。
    val separator = rest.indexOf(": ")
    val tag = if (separator < 0) rest.trim() else rest.substring(0, separator).trim()
    val message = if (separator < 0) "" else rest.substring(separator + 2)
    return ParsedLogLine(
        pid = pid,
        level = groups[9][0],
        tid = tid,
        tag = tag,
        message = message,
        epochMillis = clock.epochMillis(
            month = groups[1].toInt(),
            day = groups[2].toInt(),
            hour = groups[3].toInt(),
            minute = groups[4].toInt(),
            second = groups[5].toInt(),
            millis = groups[6].toInt()
        )
    )
}

internal fun parseLogLine(line: String, nowMillis: Long): ParsedLogLine? =
    parseLogLine(line, ThreadtimeClock(nowMillis))

/**
 * 合并结果。[resumeFrom] 是快照覆盖到的最后一刻之后一毫秒。
 *
 * 实时 reader 从这里开始：既不会重放整个 logd 积压（那正是重复与时间线倒置的来源），
 * 也不会漏掉快照读完之后才写下的日志。
 */
internal data class MergedLogs(val lines: List<String>, val resumeFrom: Long?)

/**
 * 把持久化历史与 logcat 快照合成一条时间线。
 *
 * Logx 的每条事件都同时写文件和 logcat，所以两个来源必然重叠；logcat 里还夹着只存在于
 * logcat 的记录（native、直接调用 Log 的代码）。这里按字段身份消掉重叠的那一份，保留
 * 没有孪生的记录，并让所有行重新按时间排序。
 */
internal fun mergePersistedAndLogcat(
    persisted: List<String>,
    logcatSnapshot: List<String>,
    nowMillis: Long = System.currentTimeMillis()
): MergedLogs {
    val clock = ThreadtimeClock(nowMillis)
    val parsedPersisted = persisted.map { parseLogLine(it, clock) }

    // 同一事件在两个来源各有一份时，logcat 那份是多出来的。匹配按身份找出候选，
    // 再要求时间落在 DUPLICATE_TIME_TOLERANCE_MS 的邻域里：反复出现的同文事件各消几次，
    // 但绝不会用一条几秒前的旧记录抵消掉刚写下的新事件。
    val persistedTimes = HashMap<String, MutableList<Long>>()
    var persistedMax = Long.MIN_VALUE
    parsedPersisted.forEach { parsed ->
        if (parsed == null) return@forEach
        persistedTimes.getOrPut(parsed.identity) { ArrayList(1) }.add(parsed.epochMillis)
        if (parsed.epochMillis > persistedMax) persistedMax = parsed.epochMillis
    }
    // 候选必须按时间升序：快照游标只会前进，靠升序才能高效地跳过已过窗口的候选。
    val duplicated = HashMap<String, DuplicateCandidates>(persistedTimes.size)
    persistedTimes.forEach { (identity, times) ->
        duplicated[identity] = DuplicateCandidates(times.sorted().toLongArray())
    }

    var snapshotMax = Long.MIN_VALUE
    val extras = ArrayList<String>(logcatSnapshot.size)
    val extraTimes = ArrayList<Long>(logcatSnapshot.size)
    var cursor = Long.MIN_VALUE
    logcatSnapshot.forEach { line ->
        val parsed = parseLogLine(line, clock)
        if (parsed == null) {
            // 没有 threadtime 前缀的行跟着上一行的时间走，不会被甩到列表顶端。
            extras.add(line)
            extraTimes.add(cursor)
            return@forEach
        }
        cursor = parsed.epochMillis
        if (parsed.epochMillis > snapshotMax) snapshotMax = parsed.epochMillis
        if (duplicated[parsed.identity]?.consume(parsed.epochMillis) == true) {
            return@forEach
        }
        extras.add(line)
        extraTimes.add(parsed.epochMillis)
    }

    val merged = ArrayList<String>(persisted.size + extras.size)
    var next = 0
    parsedPersisted.forEachIndexed { index, parsed ->
        if (parsed != null) {
            while (next < extras.size && extraTimes[next] <= parsed.epochMillis) {
                merged.add(extras[next])
                next++
            }
        }
        merged.add(persisted[index])
    }
    while (next < extras.size) {
        merged.add(extras[next])
        next++
    }

    // 实时 reader 的起点不能落在未来：logd 只保留已经发生的记录，拿一个未来时刻当 -T
    // 的起点会让页面一直空到时钟追上。历史行只有当地墙上时间，改过时区或时钟之后最新的
    // 那行可能被解释成未来时刻，这里一律压回 now。
    val anchor = minOf(maxOf(persistedMax, snapshotMax), nowMillis)
    return MergedLogs(merged, if (anchor == Long.MIN_VALUE) null else anchor + 1)
}

/**
 * threadtime 前缀的前 18 个字符：MM-DD HH:mm:ss.SSS。
 *
 * 与 [threadtimeRegex] 不同，这里只认开头的时间，不去切 tag 与正文——重写时间时后半段
 * 必须原样保留。
 */
private val wallClockPrefix = Regex("""^(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s""")

/** [wallClockPrefix] 匹配到的长度，重写时从这一位之后原样接回去。 */
private const val WALL_CLOCK_PREFIX_LENGTH = 18

/** 时区偏移都是整分钟；mtime 与那一行的毫秒差必须抹掉，否则「偏移没变」的快速路径永远不成立。 */
private const val MILLIS_PER_MINUTE = 60_000.0

/** 文件名里记的偏移是分钟，还原成 epoch 要的是毫秒。 */
private const val MILLIS_PER_MINUTE_EXACT = 60_000L

/** 现实中的时区偏移都是 15 分钟的整数倍（含 +5:45 这类）。 */
private const val QUARTER_HOUR_MS = 15 * 60_000L

/** 最大的真实时区偏移（UTC+14）；超出的反推值只可能是 mtime 被改过。 */
private const val MAX_PLAUSIBLE_OFFSET_MS = 14 * 60 * 60_000L

/**
 * 把持久化历史里的当地墙上时间重写成当前时区下的同一条时间线。
 *
 * 文件里只有 threadtime 的当地月日时分，既没有 epoch 也没有时区，读取端只能用当前默认
 * 时区把它解释回去。设备改过时区之后，同一份历史会被整体挪几个小时：最新的行可能被解释
 * 成未来时刻，实时 reader 拿着一个未来的 -T 起点，页面一直空到时钟追上；与 logcat 里那
 * 批孪生行的时间也不再落进去重容差，同一条事件于是显示两遍。
 *
 * 写入偏移优先取文件名里记的那一段：LogFileManager 让每份文件自带一个偏移，时区变了就轮转
 * 到新文件，所以同一份文件里的行属于同一个偏移。这是权威来源，与内容、mtime 都无关。
 * 旧版本文件没有这段元数据，才退回用「文件最后修改时间就是最后一行写入的 epoch」反推；
 * 而且只在 [LogFileManager.LogFileContent.modifiedMillis] 可信（读取期间没有新写入）时才反
 * 推，否则原样放着——拿一个对不上的 mtime 去算偏移，真实时区没变也会把整份历史挪走。
 *
 * 偏移与当前时区一致时整份文件原样返回；不一致时逐行还原成真实 epoch 再按当前时区重新
 * 渲染，读取端于是只用一把时钟就能解释整份历史。
 *
 * 同一文件里跨夏令时写入的行会按该文件记录的偏移整体平移，误差等于夏令时的差值；这比按
 * 当前时区整体错开几小时要小得多，也不再影响实时 reader 的起点。
 */
internal fun normalizePersistedWallClock(
    groups: List<LogFileManager.LogFileContent>,
    nowMillis: Long
): List<String> {
    val zone = TimeZone.getDefault()
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    val local = Calendar.getInstance(zone)
    val normalized = ArrayList<String>(groups.sumOf { it.lines.size })
    groups.forEach { group ->
        val anchor = group.modifiedMillis ?: nowMillis
        val written = when {
            group.offsetMinutes != null -> group.offsetMinutes.toLong() * MILLIS_PER_MINUTE_EXACT
            group.modifiedMillis != null -> writtenOffsetMillis(group.lines, group.modifiedMillis, utc)
            // 内容与 mtime 对不上、名字里又没有偏移：没有可信的写入时区，原样留着
            else -> null
        }
        if (written == null || written == zone.getOffset(anchor).toLong()) {
            // 写这份文件的时区与现在一致：连字符串都不用重新渲染，几十万行也只是一次拷贝。
            normalized.addAll(group.lines)
            return@forEach
        }
        group.lines.forEach { line ->
            normalized.add(shiftWallClock(line, written, anchor, utc, local) ?: line)
        }
    }
    return normalized
}

/**
 * 反推一个日志文件写入时相对 UTC 的偏移。
 *
 * 取文件里最后一条能解析的行：它写下的那一刻就是文件的 mtime。文件为空、或整份都解析
 * 不出时间时返回 null，调用方按原样使用这些行。
 */
private fun writtenOffsetMillis(lines: List<String>, anchorMillis: Long, utc: Calendar): Long? {
    val last = lines.lastOrNull { wallClockPrefix.containsMatchIn(it) } ?: return null
    val asIfUtc = asIfUtcMillis(last, anchorMillis, utc) ?: return null
    // 偏移总是整分钟，现实中也不超过 UTC+14 / UTC-12。mtime 被外部改动过（文件被拷进来、
    // 整目录恢复）时反推出来的值不会同时满足这两条，此时宁可让这些行原样留着。
    val offset = Math.round((asIfUtc - anchorMillis) / MILLIS_PER_MINUTE) * 60_000L
    if (offset % QUARTER_HOUR_MS != 0L || abs(offset) > MAX_PLAUSIBLE_OFFSET_MS) return null
    return offset
}

/**
 * 把一行的时间字段当成 UTC 解释成 epoch。
 *
 * 年份的规则与 [ThreadtimeClock] 相同：threadtime 不写年份，比锚点晚太多的行属于上一年。
 */
private fun asIfUtcMillis(line: String, anchorMillis: Long, utc: Calendar): Long? {
    val match = wallClockPrefix.find(line) ?: return null
    val month = match.groupValues[1].toIntOrNull() ?: return null
    val day = match.groupValues[2].toIntOrNull() ?: return null
    val hour = match.groupValues[3].toIntOrNull() ?: return null
    val minute = match.groupValues[4].toIntOrNull() ?: return null
    val second = match.groupValues[5].toIntOrNull() ?: return null
    val millis = match.groupValues[6].toIntOrNull() ?: return null
    val year = yearOf(anchorMillis, utc)
    val candidate = atUtc(utc, year, month, day, hour, minute, second, millis)
    return if (candidate > anchorMillis + YEAR_ROLLOVER_TOLERANCE_MS) {
        atUtc(utc, year - 1, month, day, hour, minute, second, millis)
    } else {
        candidate
    }
}

private fun atUtc(
    utc: Calendar,
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
    millis: Int
): Long {
    utc.clear()
    utc.set(year, month - 1, day, hour, minute, second)
    utc.set(Calendar.MILLISECOND, millis)
    return utc.timeInMillis
}

private fun yearOf(epochMillis: Long, utc: Calendar): Int {
    utc.timeInMillis = epochMillis
    return utc.get(Calendar.YEAR)
}

/**
 * 把一行从「写入时区的墙上时间」换成「当前时区的墙上时间」。
 *
 * 只替换前 18 个字符，pid／tid／级别／tag／正文原样保留：去重用的身份键里不含时间，改写的
 * 只有时间本身。
 */
private fun shiftWallClock(
    line: String,
    writtenOffsetMillis: Long,
    anchorMillis: Long,
    utc: Calendar,
    local: Calendar
): String? {
    if (!wallClockPrefix.containsMatchIn(line)) return null
    val asIfUtc = asIfUtcMillis(line, anchorMillis, utc) ?: return null
    local.timeInMillis = asIfUtc - writtenOffsetMillis
    val prefix = String.format(
        Locale.US,
        "%02d-%02d %02d:%02d:%02d.%03d",
        local.get(Calendar.MONTH) + 1,
        local.get(Calendar.DAY_OF_MONTH),
        local.get(Calendar.HOUR_OF_DAY),
        local.get(Calendar.MINUTE),
        local.get(Calendar.SECOND),
        local.get(Calendar.MILLISECOND)
    )
    return prefix + line.substring(WALL_CLOCK_PREFIX_LENGTH)
}
