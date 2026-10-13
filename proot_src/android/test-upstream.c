/* Android regressions for the October 2026 upstream fixes. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>
#include <linux/stat.h>

static int failures;
#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "line %d: %s (%s)\n", __LINE__, #condition, strerror(errno)); \
        failures++; \
    } \
} while (0)

static void check_reopen(int fd, int memfd)
{
    char path[64];
    int readonly;
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    readonly = open(path, O_RDONLY);
    if (readonly < 0 && memfd && (errno == EACCES || errno == EPERM)) {
        puts("SKIP: memfd reopen denied by the process security context");
        return;
    }
    CHECK(readonly >= 0);
    if (readonly < 0)
        return;
    CHECK((fcntl(readonly, F_GETFL) & O_ACCMODE) == O_RDONLY);
    errno = 0;
    CHECK(write(readonly, "x", 1) == -1 && errno == EBADF);
    CHECK(write(fd, "x", 1) == 1);
    close(readonly);
}

static void check_ids(uid_t uid, gid_t gid)
{
    uid_t real_uid = UINT32_MAX, effective_uid = UINT32_MAX, saved_uid = UINT32_MAX;
    gid_t real_gid = UINT32_MAX, effective_gid = UINT32_MAX, saved_gid = UINT32_MAX;
    CHECK(getresuid(&real_uid, &effective_uid, &saved_uid) == 0);
    CHECK(real_uid == uid && effective_uid == uid && saved_uid == uid);
    CHECK(getresgid(&real_gid, &effective_gid, &saved_gid) == 0);
    CHECK(real_gid == gid && effective_gid == gid && saved_gid == gid);
    CHECK(getuid() == uid && geteuid() == uid && getgid() == gid && getegid() == gid);
}

static void check_maps(void)
{
    const char *paths[] = { "/proc/self/uid_map", "/proc/self/gid_map" };
    unsigned int i;
    for (i = 0; i < sizeof(paths) / sizeof(paths[0]); i++) {
        unsigned long long inside, outside, count;
        FILE *map = fopen(paths[i], "r");
        if (map == NULL && errno == ENOENT) {
            printf("SKIP: %s unavailable (kernel has no user namespaces)\n", paths[i]);
            continue;
        }
        CHECK(map != NULL);
        if (map == NULL)
            continue;
        CHECK(fscanf(map, "%llu %llu %llu", &inside, &outside, &count) == 3);
        fclose(map);
    }
}

static void check_empty_path(void)
{
    struct stat cwd, empty, after;
    struct statx extended;
    struct timespec times[2] = { { 123456789, 0 }, { 123456789, 0 } };
    int dirfd;
    CHECK(stat(".", &cwd) == 0);
    CHECK(fstatat(AT_FDCWD, "", &empty, AT_EMPTY_PATH) == 0);
    CHECK(cwd.st_dev == empty.st_dev && cwd.st_ino == empty.st_ino);
    errno = 0;
    CHECK(fstatat(AT_FDCWD, "", &empty, 0) == -1 && errno == ENOENT);
    dirfd = open("/", O_RDONLY | O_DIRECTORY);
    CHECK(dirfd >= 0);
    if (dirfd >= 0) {
        CHECK(fstat(dirfd, &cwd) == 0);
        CHECK(fstatat(dirfd, "", &empty, AT_EMPTY_PATH) == 0);
        CHECK(cwd.st_dev == empty.st_dev && cwd.st_ino == empty.st_ino);
        close(dirfd);
    }
    CHECK(stat(".", &cwd) == 0);
    memset(&extended, 0, sizeof(extended));
    if (syscall(SYS_statx, AT_FDCWD, "", AT_EMPTY_PATH, STATX_INO, &extended) < 0) {
        CHECK(errno == ENOSYS);
        puts("SKIP: statx unavailable");
    } else {
        CHECK(extended.stx_ino == (uint64_t) cwd.st_ino);
    }
    CHECK(utimensat(AT_FDCWD, "", times, AT_EMPTY_PATH) == 0);
    CHECK(stat(".", &after) == 0 && after.st_mtime == times[1].tv_sec);
}

static void check_mmap(void)
{
#if defined(__aarch64__)
    size_t sizes[] = { (size_t) 256 << 30, (size_t) 300 << 30, (size_t) 330 << 30 };
    unsigned int i;
    for (i = 0; i < sizeof(sizes) / sizeof(sizes[0]); i++) {
        void *mapping = mmap(NULL, sizes[i], PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        CHECK(mapping != MAP_FAILED);
        if (mapping != MAP_FAILED)
            CHECK(munmap(mapping, sizes[i]) == 0);
    }
#endif
}

/* The shell starts the server and client under separate PRoot supervisors. */
static void check_peer(const char *mode, const char *path, uid_t uid, gid_t gid)
{
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    struct ucred credential;
    socklen_t length = sizeof(credential);
    char byte;
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    int peer;
    CHECK(fd >= 0);
    if (fd < 0)
        return;
    CHECK(strlen(path) < sizeof(address.sun_path));
    snprintf(address.sun_path, sizeof(address.sun_path), "%s", path);
    if (strcmp(mode, "server") == 0) {
        CHECK(bind(fd, (struct sockaddr *) &address, sizeof(address)) == 0);
        CHECK(listen(fd, 1) == 0);
        peer = accept(fd, NULL, NULL);
        CHECK(peer >= 0);
        if (peer >= 0) {
            CHECK(read(peer, &byte, 1) == 1);
            close(peer);
        }
        unlink(path);
    } else {
        CHECK(connect(fd, (struct sockaddr *) &address, sizeof(address)) == 0);
        CHECK(getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &credential, &length) == 0);
        CHECK(credential.uid == uid && credential.gid == gid);
        CHECK(write(fd, "x", 1) == 1);
    }
    close(fd);
}

int main(int argc, char **argv)
{
    int fd;
    uid_t uid;
    gid_t gid;
    if (argc != 4 && argc != 5)
        return 2;
    uid = (uid_t) strtoul(argv[argc - 2], NULL, 10);
    gid = (gid_t) strtoul(argv[argc - 1], NULL, 10);
    if (argc == 5) {
        check_peer(argv[1], argv[2], uid, gid);
    } else {
        check_ids(uid, gid);
        check_maps();
        fd = syscall(SYS_memfd_create, "proot-reopen", 0);
        CHECK(fd >= 0);
        if (fd >= 0) {
            check_reopen(fd, 1);
            close(fd);
        }
        fd = open("reopen", O_CREAT | O_RDWR | O_TRUNC, 0600);
        CHECK(fd >= 0);
        if (fd >= 0) {
            check_reopen(fd, 0);
            close(fd);
            unlink("reopen");
        }
        check_empty_path();
        check_mmap();
    }
    if (failures == 0)
        puts("UPSTREAM_CHECKS_OK");
    return failures != 0;
}
