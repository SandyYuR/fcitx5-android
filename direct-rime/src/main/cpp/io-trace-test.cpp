#include "io-trace.h"
#include <cstdlib>
#include <fcntl.h>
#include <stdexcept>
#include <sys/stat.h>
#include <unistd.h>

using namespace direct_rime;
#define CHECK(condition) do { if (!(condition)) throw std::runtime_error(#condition); } while (0)
int main() {
    char name[] = "/tmp/rime-io-XXXXXX";
    int temporary = mkstemp(name);
    CHECK(temporary >= 0);
    close(temporary);
    {
        IoScope scope;
        int fd = open(name, O_RDWR | O_TRUNC);
        CHECK(fd >= 0);
        CHECK(write(fd, "rime", 4) == 4);
        CHECK(fdatasync(fd) == 0);
        char contents[4];
        CHECK(pread(fd, contents, 4, 0) == 4 && contents[0] == 'r');
        struct stat info{};
        CHECK(stat(name, &info) == 0 && info.st_size == 4);
        CHECK(scope.values.calls == 5);
        { IoScope nested; CHECK(read(-1, contents, 4) == -1 && errno == EBADF); CHECK(nested.values.calls == 1); }
        CHECK(scope.values.calls == 5);
        CHECK(open("/nonexistent/rime-io-test", O_RDONLY) == -1 && errno == ENOENT);
        CHECK(scope.values.calls == 6);
        CHECK(scope.values.nanos[IoCounters::Sync] > 0);
        close(fd);
    }
    CHECK(activeIo == nullptr);
    unlink(name);
}
