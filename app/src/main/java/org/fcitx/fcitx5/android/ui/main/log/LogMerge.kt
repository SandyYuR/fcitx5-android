/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.log

import java.util.Calendar
import kotlin.math.abs

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
        if (epochMillis < lastQuery) {
            // 快照时间倒退（不同缓冲区交错）时退回从头扫描，否则游标会越过仍可用的候选
            cursor = 0
        } else {
            val lowerBound = epochMillis - DUPLICATE_TIME_TOLERANCE_MS
            // 比窗口下界还早的候选不会再匹配任何后续快照行
            while (cursor < times.size && times[cursor] < lowerBound) cursor++
        }
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

    val anchor = maxOf(persistedMax, snapshotMax)
    return MergedLogs(merged, if (anchor == Long.MIN_VALUE) null else anchor + 1)
}
