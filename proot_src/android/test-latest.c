/* Android regressions for upstream 9688d01 through e2d843e. */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/stat.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

static int failures;
#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "line %d: %s (%s)\n", __LINE__, #condition, strerror(errno)); \
        failures++; \
    } \
} while (0)

static void check_fd(int fd, mode_t type, uid_t uid, gid_t gid)
{
    struct stat by_fd = {0}, by_empty = {0};
    struct statx extended = {0};
    CHECK(fstat(fd, &by_fd) == 0);
    CHECK((by_fd.st_mode & S_IFMT) == type);
    CHECK(by_fd.st_uid == uid && by_fd.st_gid == gid);
    CHECK(fstatat(fd, "", &by_empty, AT_EMPTY_PATH) == 0);
    CHECK(by_empty.st_dev == by_fd.st_dev && by_empty.st_ino == by_fd.st_ino);
    CHECK(by_empty.st_uid == uid && by_empty.st_gid == gid);
    if (syscall(SYS_statx, fd, "", AT_EMPTY_PATH, STATX_BASIC_STATS, &extended) == 0) {
        CHECK(extended.stx_ino == (uint64_t) by_fd.st_ino);
        CHECK(extended.stx_uid == uid && extended.stx_gid == gid);
    } else {
        CHECK(errno == ENOSYS);
        puts("SKIP: descriptor statx unavailable");
    }
}

static void check_descriptors(uid_t uid, gid_t gid)
{
    int pair[2], fd;
    if (pipe(pair) == 0) {
        check_fd(pair[0], S_IFIFO, uid, gid);
        check_fd(pair[1], S_IFIFO, uid, gid);
        close(pair[0]);
        close(pair[1]);
    } else CHECK(0);
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) == 0) {
        check_fd(pair[0], S_IFSOCK, uid, gid);
        check_fd(pair[1], S_IFSOCK, uid, gid);
        close(pair[0]);
        close(pair[1]);
    } else CHECK(0);
    fd = eventfd(0, EFD_CLOEXEC);
    CHECK(fd >= 0);
    if (fd >= 0) {
        struct stat by_fd = {0};
        /* Anonymous inodes may belong to root independently of the app UID. */
        CHECK(fstat(fd, &by_fd) == 0);
        CHECK(fstatat(fd, "", &by_fd, AT_EMPTY_PATH) == 0);
        close(fd);
    }
    fd = open("deleted", O_CREAT | O_RDWR | O_TRUNC, 0600);
    CHECK(fd >= 0);
    if (fd >= 0) {
        CHECK(unlink("deleted") == 0);
        check_fd(fd, S_IFREG, uid, gid);
        close(fd);
    }
}

static void check_links(void)
{
    struct stat by_fd = {0}, by_path = {0};
    int fd = open("original", O_CREAT | O_RDWR | O_TRUNC, 0600);
    CHECK(fd >= 0);
    if (fd < 0) return;
    CHECK(link("original", "hardlink") == 0);
    CHECK(stat("hardlink", &by_path) == 0);
    CHECK(fstat(fd, &by_fd) == 0);
    CHECK(by_path.st_nlink == 2 && by_fd.st_nlink == 2);
    CHECK(by_fd.st_ino == by_path.st_ino);
    CHECK(unlink("hardlink") == 0);
    CHECK(fstat(fd, &by_fd) == 0 && by_fd.st_nlink == 1);
    close(fd);
    CHECK(unlink("original") == 0);
}

static void check_shared_memory(void)
{
    int shmid = syscall(SYS_shmget, 0, 4096, 01000 | 0600);
    int status;
    pid_t pid;
    volatile unsigned char *first, *second;
    CHECK(shmid >= 0);
    if (shmid < 0) return;
    first = (void *) syscall(SYS_shmat, shmid, NULL, 0);
    CHECK(first != (void *) -1);
    if (first == (void *) -1) goto remove;
    second = (void *) syscall(SYS_shmat, shmid, NULL, 0);
    CHECK(second != (void *) -1);
    if (second == (void *) -1) goto detach;
    first[0] = 37;
    CHECK(second[0] == 37);
    pid = fork();
    CHECK(pid >= 0);
    if (pid == 0) {
        second[1] = 42;
        _exit(first[0] == 37 ? 0 : 1);
    }
    if (pid > 0) {
        CHECK(waitpid(pid, &status, 0) == pid);
        CHECK(WIFEXITED(status) && WEXITSTATUS(status) == 0);
        CHECK(first[1] == 42);
    }
    CHECK(syscall(SYS_shmdt, (void *) second) == 0);
detach:
    CHECK(syscall(SYS_shmdt, (void *) first) == 0);
remove:
    CHECK(syscall(SYS_shmctl, shmid, 0, NULL) == 0);
}

int main(int argc, char **argv)
{
    if (argc != 3) return 2;
    check_descriptors(strtoul(argv[1], NULL, 10), strtoul(argv[2], NULL, 10));
    check_links();
    check_shared_memory();
    if (failures == 0) puts("LATEST_DESCRIPTOR_LINK_SYSVIPC_OK");
    return failures != 0;
}
