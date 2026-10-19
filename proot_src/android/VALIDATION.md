# Lyra .5 validation (2026-10-19)

## Source integration

All 21 upstream master commits from 9688d01 through e2d843e, inclusive,
are integrated on main in their original order. UPSTREAM_COMMITS.md records
all full SHAs. All 22 files changed in that range match e2d843e exactly.
The earlier Lyra external-loader lookup, static talloc dependency and loader
address adaptations are retained.

USERLAND remains disabled. Its upstream metadata fixes are included in the
source but are not active or runtime-tested in these Android packages.

## Android builds and source reproducibility

Both arm64-v8a and x86_64 build with NDK 29.0.14206865, Clang 21,
CMake 3.22.1, Ninja, Android API 24, -O2 and static talloc 2.5.0.
Version: `5.1.107.100-lyra.5`; libandroid-shmem remains disabled.

ELF inspection confirms the expected ELF64 architectures, PIE executables
using Bionic linker64, static freestanding loaders, non-executable stacks,
and 16 KB LOAD alignment. Loader entry points remain 0x5400000000 on ARM64
and 0x600000000000 on x86_64. The rebuilt loaders have unchanged hashes.

Fresh, separate builds from the Git-free proot_src mirror reproduce all
four packaged files byte-for-byte:

- arm64-v8a/libproot_exec.so: be2fbd3a0e88c0d159ef6cd044392720ea3f0bc1ee524bf67879d49f5e9b9eb4
- arm64-v8a/libproot_loader.so: 825d0362db3319c2b3ed3b75fe300287c0f9eeb7f2427ceb05a4a004ad33ed12
- x86_64/libproot_exec.so: 5f4dc068cb80fa23713573f14105d597c225e8d56ace3b4d0a77c87824710dad
- x86_64/libproot_loader.so: 61b2dd1a858caddbab4647c519cfe0c23bbd2d47981358b9cc6ae818e79e6869

The new descriptor/shared-memory helper and upstream fork_regs helper also
compile for both ABIs. ARM64 has no raw fork syscall and fork_regs reports
125 there by design.

## Android runtime

The installed Lyra debug APK was tested with test-runtime.ps1 -RunAs in an
x86_64 Android API 37 emulator, kernel 6.12.58, with 16 KB pages. Both
installed native-library hashes were checked against dist before execution.
The full script exits successfully in both seccomp and no-seccomp modes:

- Simulated hard-link execution, symlinks, execveat, O_NOFOLLOW/O_PATH,
  fork/vfork/system and 80 concurrent posix_spawn calls across four threads.
- Upstream fork_regs: raw fork preserves the argument registers of both
  parent and child, including with an explicit outer SECCOMP_RET_TRAP filter.
- Full-width fake IDs 0:0 and 123456:234567, regular-file read-only reopens,
  guest-cwd empty paths, statx, and cross-instance SO_PEERCRED.
- Descriptor queries through fstat, fstatat(AT_EMPTY_PATH) and statx for
  pipes, sockets and deleted files; successful anonymous eventfd queries.
- Simulated hard-link counts through stat and fstat, including unlink.
- SysV shared memory through two mappings and an inherited fork mapping,
  with writes visible across mappings/processes, detach and IPC_RMID.
- All seven fd-mount APIs and OPEN_TREE_CLONE returning ENOSYS, open_tree
  path translation and fchmodat2 permissions including symlink targets.
- Upstream Unix datagram sendto/sendmsg/sendmmsg tests, partial batches,
  reply addresses and signal-interrupted restarted sends.
- Sibling-loader discovery with PROOT_LOADER unset.

The emulator lacks user namespaces, so namespace-map reads are explicitly
skipped. Its application security context denies memfd reopening, which is
also explicitly skipped; memfd-backed SysV shared memory succeeds.
Bionic emits linker-configuration warnings in the minimal regression rootfs;
they do not fail the checks.

No ARM64 Android device was available. ARM64 runtime behavior, its large
mmap reservations and device-specific lost ptrace event messages have not
been exercised. No new Linux-host runtime results are claimed here.

## Application integration

:app:verifyBundledProotRuntime, :app:testDebugUnitTest and :app:assembleDebug
pass. The JUnit reports contain 448 tests: 445 passed, 3 skipped, 0 failures
and 0 errors. APK installation with adb install -r succeeds. Both ABI pairs
are packaged, with hashes/version updated in Gradle, the runtime README,
the in-app license text and third-party notices.
