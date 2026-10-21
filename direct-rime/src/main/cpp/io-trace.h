#pragma once
#include <array>
#include <cerrno>
#include <chrono>
#include <cstdint>

namespace direct_rime {
// No filenames, text, heap allocation or extra filesystem probes. These counters cover
// wrapped calls made by the owner thread, not mmap faults or LevelDB background threads.
struct IoCounters {
    enum Kind { Open, Read, Write, Sync, Stat, Count };
    std::array<int64_t, Count> nanos{};
    int64_t calls = 0;
};
inline thread_local IoCounters* activeIo = nullptr;
struct IoScope {
    IoCounters values;
    IoCounters* previous = activeIo;
    IoScope() { activeIo = &values; }
    ~IoScope() { activeIo = previous; }
};
struct IoCall {
    IoCounters* counters = activeIo;
    IoCounters::Kind kind;
    std::chrono::steady_clock::time_point start;
    explicit IoCall(IoCounters::Kind kind) : kind(kind) {
        const int savedErrno = errno;
        if (counters) start = std::chrono::steady_clock::now();
        errno = savedErrno;
    }
    ~IoCall() {
        const int savedErrno = errno;
        if (counters) {
            counters->nanos[kind] += std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - start).count();
            ++counters->calls;
        }
        errno = savedErrno;
    }
};
}
