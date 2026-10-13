#!/system/bin/sh
# Run in an isolated adb shell directory containing the built runtime and helpers.
set -eu
cd "$(dirname "$0")"
BASE=$(pwd)
export PROOT_LOADER="${PROOT_LOADER:-$BASE/libproot_loader.so}"
export PROOT_TMP_DIR="$BASE"
P="${PROOT_EXEC:-$BASE/libproot_exec.so}"
"$P" --version
for MODE in seccomp no-seccomp; do
    if [ "$MODE" = no-seccomp ]; then export PROOT_NO_SECCOMP=1; else unset PROOT_NO_SECCOMP; fi
    CASE="$BASE/$MODE"
    mkdir -p "$CASE/lib" "$CASE/bin" "$CASE/links"
    export PROOT_L2S_DIR="$CASE/links"
    cp "$BASE/puts_proc_self_exe" "$CASE/lib/original"
    "$P" -l /system/bin/ln "$CASE/lib/original" "$CASE/lib/link"
    # Verify that this exercises a PRoot-faked hard link.
    test -L "$CASE/lib/link"
    ln -s ../lib/link "$CASE/bin/symlink"
    for NAME in "$CASE/lib/link" "$CASE/bin/symlink"; do
        test "$("$P" -l "$NAME")" = "$CASE/lib/link"
        test "$("$P" -l /system/bin/sh -c "$NAME")" = "$CASE/lib/link"
        test "$("$P" -l "$BASE/execveat" "$NAME")" = "$CASE/lib/link"
    done
    test "$("$P" -l "$CASE/lib/original")" = "$CASE/lib/original"
    test "$("$P" -l "$BASE/open_nofollow" "$CASE/lib/link")" = "$CASE/lib/link"
    test "$("$P" -l "$BASE/open_nofollow" "$CASE/lib/link" path)" = "$CASE/lib/link"
    "$P" -l "$BASE/test-fork"
    echo "$MODE: LINK_EXE_EXECVEAT_NOFOLLOW_FORK_OK"

    ROOT="$CASE/rootfs"
    mkdir -p "$ROOT/bin" "$ROOT/work" "$ROOT/tmp"
    cp "$BASE/test-upstream" "$BASE/test-571fb953" "$BASE/fchmodat2" "$BASE/open_tree" "$ROOT/bin/"
    guest() {
        "$P" -r "$ROOT" -b /system -b /apex -b /proc -b /dev -w /work "$@"
    }
    guest -0 /bin/test-upstream check 0 0
    guest -i 123456:234567 /bin/test-upstream check 123456 234567
    guest /bin/test-571fb953
    guest -0 /bin/test-571fb953

    # Ensure AT_EMPTY_PATH changed the guest cwd, leaving the launch cwd alone.
    test "$(stat -c %Y "$BASE")" != 123456789
    test "$(stat -c %Y "$ROOT/work")" = 123456789

    # SO_PEERCRED must translate peers in another PRoot instance.
    guest -0 /bin/test-upstream server /work/peer 0 0 &
    SERVER=$!
    ATTEMPTS=0
    until [ -S "$ROOT/work/peer" ]; do
        ATTEMPTS=$((ATTEMPTS + 1))
        test "$ATTEMPTS" -lt 100
        sleep 0.05
    done
    guest -i 123456:234567 /bin/test-upstream client /work/peer 123456 234567
    wait "$SERVER"

    # All fd-mount entry points must return ENOSYS.
    for CALL in fsopen fsconfig fsmount fspick move_mount mount_setattr open_tree_attr; do
        guest /bin/open_tree -m "$CALL"
        guest -0 /bin/open_tree -m "$CALL"
    done
    guest /bin/open_tree -c / ENOSYS
    guest -0 /bin/open_tree -c -e "" ENOSYS

    # Older kernels and Android's outer seccomp policy can omit these calls.
    STATUS=0
    guest /bin/open_tree -e -s /work "" ok || STATUS=$?
    if [ "$STATUS" = 125 ]; then
        echo "$MODE: SKIP open_tree (kernel or outer seccomp policy)"
    else
        test "$STATUS" = 0
        guest /bin/open_tree -s / / ok
        guest -0 /bin/open_tree -d /work -s / ../../../../.. ok
    fi
    echo test > "$ROOT/work/file"
    ln -s file "$ROOT/work/link"
    STATUS=0
    guest /bin/fchmodat2 /work/file 600 ok || STATUS=$?
    if [ "$STATUS" = 125 ]; then
        echo "$MODE: SKIP fchmodat2 (kernel or outer seccomp policy)"
    else
        test "$STATUS" = 0
        test "$(stat -c %a "$ROOT/work/file")" = 600
        guest /bin/fchmodat2 -d /work file 640 ok
        chmod 444 "$ROOT/work/file"
        guest -0 /bin/fchmodat2 -n /work/link 600 EOPNOTSUPP
        test "$(stat -c %a "$ROOT/work/file")" = 444
    fi
    echo "$MODE: OCTOBER_UPSTREAM_REGRESSIONS_OK"
done
# Exercise Lyra's sibling-loader discovery without PROOT_LOADER.
unset PROOT_LOADER PROOT_NO_SECCOMP
"$P" -l /system/bin/sh -c 'echo SIBLING_LOADER_OK'
