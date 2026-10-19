#include "io-trace.h"
#include <cstdarg>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

using direct_rime::IoCall;
using direct_rime::IoCounters;

extern "C" {
int __real_open(const char*, int, ...);
int __real___open_2(const char*, int);
ssize_t __real_read(int, void*, size_t);
ssize_t __real_pread(int, void*, size_t, off_t);
ssize_t __real_write(int, const void*, size_t);
int __real_fsync(int);
int __real_fdatasync(int);
int __real_stat(const char*, struct stat*);

int __wrap_open(const char* path, int flags, ...) {
    IoCall call(IoCounters::Open);
    bool hasMode = flags & O_CREAT;
#ifdef O_TMPFILE
    hasMode = hasMode || (flags & O_TMPFILE) == O_TMPFILE;
#endif
    if (hasMode) {
        va_list args;
        va_start(args, flags);
        const auto mode = va_arg(args, int);
        va_end(args);
        return __real_open(path, flags, mode);
    }
    return __real_open(path, flags);
}
int __wrap___open_2(const char* path, int flags) {
    IoCall call(IoCounters::Open);
    return __real___open_2(path, flags);
}
ssize_t __wrap_read(int fd, void* buffer, size_t length) {
    IoCall call(IoCounters::Read);
    return __real_read(fd, buffer, length);
}
ssize_t __wrap_pread(int fd, void* buffer, size_t length, off_t offset) {
    IoCall call(IoCounters::Read);
    return __real_pread(fd, buffer, length, offset);
}
ssize_t __wrap_write(int fd, const void* buffer, size_t length) {
    IoCall call(IoCounters::Write);
    return __real_write(fd, buffer, length);
}
int __wrap_fsync(int fd) {
    IoCall call(IoCounters::Sync);
    return __real_fsync(fd);
}
int __wrap_fdatasync(int fd) {
    IoCall call(IoCounters::Sync);
    return __real_fdatasync(fd);
}
int __wrap_stat(const char* path, struct stat* result) {
    IoCall call(IoCounters::Stat);
    return __real_stat(path, result);
}
}
