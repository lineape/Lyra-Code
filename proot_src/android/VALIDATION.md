# Lyra .4 validation (2026-10-13)

## Source and build

All nine requested upstream commits, a179d3e through b8c1f5c, were cherry-picked
onto main with their original commit IDs recorded. The existing external-loader
lookup, link-directory pinning and ptrace recovery fixes are retained.

Both Android ABIs build with NDK 29.0.14206865, Clang 21, CMake 3.22.1, Ninja,
API 24 and static talloc 2.5.0. Version: `5.1.107.100-lyra.4`.
ELF inspection confirms AArch64/x86-64 ELF64 executables, Bionic linker64,
freestanding static loaders, non-executable stacks and 16 KB LOAD alignment.
The ARM64 loader entry point is `0x5400000000`; x86-64 is `0x600000000000`.

## Android runtime

The x86-64 Android API 37 emulator uses kernel 6.12.58 and 16 KB pages.
The installed Lyra debug native-library pair was checked against both dist
hashes before execution through run-as. `test-runtime.ps1 -RunAs` passes in
both seccomp and no-seccomp modes:

- Simulated hard links, executable descriptor paths, symbolic links, execveat,
  O_NOFOLLOW/O_PATH, fork/vfork/system and concurrent threaded posix_spawn.
- Full-width getresuid/getresgid and fake UID/GID values 123456/234567.
- Read-only reopening of regular files, including rejected writes.
- Guest-cwd AT_EMPTY_PATH through fstatat, statx and utimensat; real directory
  descriptors and empty paths without flags; unchanged host launch directory.
- SO_PEERCRED between separate PRoot instances.
- All seven fd-mount APIs and OPEN_TREE_CLONE returning ENOSYS.
- open_tree guest-cwd/absolute/dirfd traversal and fchmodat2 permissions,
  including read-only symlink targets under fake root.
- The upstream test-571fb953: sendto/sendmsg/sendmmsg, absolute and relative
  paths, partial batches, returned lengths, unchanged caller structures,
  replies to recvfrom/recvmsg addresses and signal-interrupted restarted sends.
- Sibling-loader discovery without PROOT_LOADER.

The emulator kernel has no user namespaces: uid_map/gid_map reads are explicitly
skipped. The application context denies memfd reopening with EACCES; this is
explicitly skipped there. The separate adb-shell check passes memfd reopening.
The initial adb-shell datagram test was denied by SELinux; the full named-socket
checks pass under the application's run-as context with short fixture paths.

ARM64 binaries and the ARM64 regression helper were compiled and ELF-checked.
No ARM64 Android device was available, so ARM64 runtime behavior and the
256/300/330 GiB mmap checks have not been exercised locally.

## Supplementary Linux checks

On WSL2 kernel 6.6.87.2, a native x86-64 build passes the upstream fchmodat2,
open_tree and fd-mount tests in both modes. Full-width IDs, namespace-map reads,
regular-file/memfd reopens and guest-cwd empty paths also pass. The upstream
datagram test passes with seccomp disabled. Its seccomp-enabled WSL run fails
at the initial bind with ENOENT, before the send tests; this host result remains
unresolved. The Android application runs of the same datagram helper pass
with and without seccomp.

## Application integration

`:app:verifyBundledProotRuntime`, `:app:testDebugUnitTest` and
`:app:assembleDebug` pass. Unit tests: 375 total, 372 passed, 3 skipped,
0 failures and 0 errors. Both ABI pairs are packaged and their fixed hashes
are updated in Gradle, the runtime README, license text and third-party notices.
