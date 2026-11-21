/* Android emulator probe. Read or count the cached pages of one regular file. */
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>

static void fail(const char *operation) {
    perror(operation);
    exit(EXIT_FAILURE);
}

int main(int argc, char **argv) {
    if (argc != 3 || (strcmp(argv[1], "read") && strcmp(argv[1], "inspect"))) {
        fprintf(stderr, "usage: %s read|inspect FILE\n", argv[0]);
        return EXIT_FAILURE;
    }
    int fd = open(argv[2], O_RDONLY | O_CLOEXEC);
    if (fd < 0) fail("open");
    struct stat info;
    if (fstat(fd, &info) < 0) fail("fstat");
    long page_size = sysconf(_SC_PAGESIZE);
    if (!S_ISREG(info.st_mode) || info.st_size <= 0 || page_size <= 0) {
        fprintf(stderr, "expected a nonempty regular file and a valid page size\n");
        return EXIT_FAILURE;
    }
    size_t size = (size_t)info.st_size;
    size_t page = (size_t)page_size;
    size_t pages = (size + page - 1) / page;
    size_t bytes_read = 0;
    if (!strcmp(argv[1], "read")) {
        static unsigned char buffer[1024 * 1024];
        ssize_t count;
        while ((count = read(fd, buffer, sizeof(buffer))) > 0) bytes_read += (size_t)count;
        if (count < 0) fail("read");
        if (bytes_read != size) {
            fprintf(stderr, "file changed while reading\n");
            return EXIT_FAILURE;
        }
    }
    void *mapping = mmap(NULL, size, PROT_READ, MAP_SHARED, fd, 0);
    if (mapping == MAP_FAILED) fail("mmap");
    unsigned char *resident = malloc(pages);
    if (resident == NULL) fail("malloc");
    if (mincore(mapping, size, resident) < 0) fail("mincore");
    size_t resident_pages = 0;
    for (size_t i = 0; i < pages; i++) resident_pages += resident[i] & 1;
    free(resident);
    munmap(mapping, size);
    close(fd);
    struct rusage usage;
    if (getrusage(RUSAGE_SELF, &usage) < 0) fail("getrusage");
    double user_ms = usage.ru_utime.tv_sec * 1000.0 + usage.ru_utime.tv_usec / 1000.0;
    double system_ms = usage.ru_stime.tv_sec * 1000.0 + usage.ru_stime.tv_usec / 1000.0;
    printf("{\"size_bytes\":%zu,\"bytes_read\":%zu,\"resident_pages\":%zu,\"page_size\":%zu,"
           "\"worker_max_rss_kib\":%ld,\"user_cpu_ms\":%.3f,\"system_cpu_ms\":%.3f}\n",
           size, bytes_read, resident_pages, page, usage.ru_maxrss, user_ms, system_ms);
    return EXIT_SUCCESS;
}
