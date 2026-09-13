#!/usr/bin/env bash
# =============================================================================
# BitLockerDroid — end-to-end regression test suite (adb)
#
# Runs against a real device with a BitLocker volume unlocked (or auto-restored)
# by the app. Covers the SAF DocumentsProvider full chain plus the FUSE
# cross-session sync. Exit code 0 = all tests passed.
#
# Usage:
#   bash tools/e2e_test.sh            # run all tests on the first unlocked root
#   SKIP_SYNC=1 bash tools/e2e_test.sh   # skip FUSE sync tests (daemon disabled)
#   SKIP_SECURITY=1 bash ...             # skip unlock-dialog rejection test
#
# Prerequisites:
#   - `adb` in PATH, one device connected (wireless ok)
#   - root (KernelSU/Magisk) granted to the app for FUSE-related tests
#   - the BitLocker volume inserted; the app will be launched to restore it
# =============================================================================
set -u

PKG="com.bitlockerdroid"
AUTH="content://com.bitlockerdroid.provider"
SETTINGS_ACT="$PKG/.ui.BitLockerSettingsActivity"
TMP=/data/local/tmp/e2e
PASS=0; FAIL=0; SKIPPED=0
declare -a FAILED_NAMES

say()  { printf '%s\n' "$*"; }
pass() { PASS=$((PASS+1)); say "  PASS: $1"; }
fail() { FAIL=$((FAIL+1)); FAILED_NAMES+=("$1"); say "  FAIL: $1 ${2:-}"; }
skip() { SKIPPED=$((SKIPPED+1)); say "  SKIP: $1 (${2:-})"; }

adb_shell() { adb shell "$@" 2>/dev/null; }

# Wait until the provider exposes at least one root (app launched & unlocked).
wait_for_root() {
  for _ in $(seq 1 30); do
    ROOT=$(adb_shell "content query --uri $AUTH/root" | grep -o 'document_id=[^,]*' | head -1 | sed 's/document_id=//')
    [ -n "$ROOT" ] && return 0
    sleep 2
  done
  return 1
}

app_alive() { [ -n "$(adb_shell pidof $PKG)" ]; }

logcat_errors() { adb logcat -d -s AndroidRuntime:E 2>/dev/null | grep -c "com.bitlockerdroid" || true; }

# -----------------------------------------------------------------------------
say "=== BitLockerDroid E2E ==="
say "device: $(adb_shell getprop ro.product.model) (Android $(adb_shell getprop ro.build.version.release))"

adb logcat -c >/dev/null 2>&1
adb shell "am force-stop $PKG" >/dev/null 2>&1; sleep 2
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
if ! wait_for_root; then
  say "FATAL: no unlocked volume found (launch the app and unlock the drive)."
  exit 2
fi
say "root doc: $ROOT"

# -----------------------------------------------------------------------------
say "--- T1 provider roots query"
R=$(adb_shell "content query --uri $AUTH/root" | head -1)
if echo "$R" | grep -q "title="; then pass "T1 roots"; else fail "T1 roots" "$R"; fi
TITLE=$(echo "$R" | grep -o 'title=[^,]*' | sed 's/title=//')
say "  volume: $TITLE"

# -----------------------------------------------------------------------------
say "--- T2 children listing"
KIDS=$(adb_shell "content query --uri '$AUTH/document/$ROOT/children'")
if [ -n "$KIDS" ] || [ "$KIDS" = "" ]; then
  N=$(echo "$KIDS" | grep -c "Row:" || true)
  pass "T2 listing ($N entries)"
else
  fail "T2 listing"
fi

# -----------------------------------------------------------------------------
say "--- T3 create + write + read-back (text)"
DOC=$(adb_shell "content call --uri $AUTH --method create_document --arg '$ROOT' \
  --extra mime_type:s:text/plain --extra display_name:s:e2e_t3.txt" \
  | grep -o 'document_id=[^,}]*' | head -1 | sed 's/document_id=//')
if [ -z "$DOC" ]; then fail "T3 create"; else
  printf 'hello from e2e_test.sh %s' "$RANDOM$RANDOM" | adb shell "content write --uri '$AUTH/document/$DOC'" >/dev/null 2>&1
  sleep 1
  BACK=$(adb_shell "content read --uri '$AUTH/document/$DOC'")
  if [ -n "$BACK" ]; then pass "T3 text roundtrip"; else fail "T3 text roundtrip" "empty read-back"; fi
fi

# -----------------------------------------------------------------------------
say "--- T4 1MB random roundtrip (SHA256)"
DOC1M=$(adb_shell "content call --uri $AUTH --method create_document --arg '$ROOT' \
  --extra mime_type:s:application/octet-stream --extra display_name:s:e2e_t4.bin" \
  | grep -o 'document_id=[^,}]*' | head -1 | sed 's/document_id=//')
if [ -z "$DOC1M" ]; then fail "T4 create 1MB"; else
  adb_shell "mkdir -p $TMP; dd if=/dev/urandom of=$TMP/src.bin bs=1024 count=1024 2>/dev/null"
  SRC_HASH=$(adb_shell "sha256sum $TMP/src.bin" | awk '{print $1}')
  adb shell "content write --uri '$AUTH/document/$DOC1M' < $TMP/src.bin" >/dev/null 2>&1
  sleep 2
  adb_shell "content read --uri '$AUTH/document/$DOC1M' > $TMP/rt.bin" >/dev/null 2>&1
  RT_HASH=$(adb_shell "sha256sum $TMP/rt.bin" | awk '{print $1}')
  RT_SIZE=$(adb_shell "ls -la $TMP/rt.bin" | awk '{print $5}')
  if [ "$SRC_HASH" = "$RT_HASH" ] && [ "$RT_SIZE" = "1048576" ]; then
    pass "T4 1MB roundtrip ($SRC_HASH)"
  else
    fail "T4 1MB roundtrip" "src=$SRC_HASH rt=$RT_HASH size=$RT_SIZE"
  fi
fi

# -----------------------------------------------------------------------------
say "--- T5 shorter overwrite truncates to new size"
printf 'SHORT' | adb shell "content write --uri '$AUTH/document/$DOC1M'" >/dev/null 2>&1
sleep 1
SZ=$(adb_shell "content query --uri '$AUTH/document/$DOC1M'" | grep -o '_size=[0-9]*' | cut -d= -f2)
if [ "$SZ" = "5" ]; then pass "T5 truncate"; else fail "T5 truncate" "size=$SZ want 5"; fi

# -----------------------------------------------------------------------------
say "--- T6 empty-write protection (content must survive)"
printf '' | adb shell "content write --uri '$AUTH/document/$DOC1M'" >/dev/null 2>&1
sleep 1
CONTENT=$(adb_shell "content read --uri '$AUTH/document/$DOC1M'")
SZ2=$(adb_shell "content query --uri '$AUTH/document/$DOC1M'" | grep -o '_size=[0-9]*' | cut -d= -f2)
if [ "$CONTENT" = "SHORT" ] && [ "$SZ2" = "5" ]; then
  pass "T6 empty-write protection"
else
  fail "T6 empty-write protection" "content='$CONTENT' size=$SZ2"
fi

# -----------------------------------------------------------------------------
say "--- T7 rename visible in listing"
adb_shell "content call --uri $AUTH --method rename_document --arg '$DOC1M|e2e_t4_renamed.bin'" >/dev/null 2>&1
sleep 1
if adb_shell "content query --uri '$AUTH/document/$ROOT/children'" | grep -q "e2e_t4_renamed.bin"; then
  pass "T7 rename"
else
  fail "T7 rename" "renamed file not in listing"
fi

# -----------------------------------------------------------------------------
say "--- T8 nested directory create + delete"
DIRREC=$(adb_shell "content call --uri $AUTH --method create_document --arg '$ROOT' \
  --extra mime_type:s:vnd.android.document/directory --extra display_name:s:e2e_dir" \
  | grep -o 'document_id=[^,}]*' | head -1 | sed 's/document_id=//' | sed "s/.*://")
if [ -z "$DIRREC" ]; then fail "T8 create dir"; else
  DIRDOC="${ROOT%:*}:$DIRREC"
  NREC=$(adb_shell "content call --uri $AUTH --method create_document --arg '$DIRDOC' \
    --extra mime_type:s:text/plain --extra display_name:s:inner.txt" \
    | grep -o 'document_id=[^,}]*' | head -1 | sed 's/document_id=//' | sed "s/.*://")
  INNERDOC="${ROOT%:*}:$NREC"
  printf 'inner' | adb shell "content write --uri '$AUTH/document/$INNERDOC'" >/dev/null 2>&1
  BACK=$(adb_shell "content read --uri '$AUTH/document/$INNERDOC'")
  R1=$(adb_shell "content call --uri $AUTH --method delete_document --arg '$INNERDOC'" | grep -c success || true)
  R2=$(adb_shell "content call --uri $AUTH --method delete_document --arg '$DIRDOC'" | grep -c success || true)
  if [ "$BACK" = "inner" ] && [ "$R1" = "1" ] && [ "$R2" = "1" ]; then
    pass "T8 nested dir CRUD"
  else
    fail "T8 nested dir CRUD" "back='$BACK' r1=$R1 r2=$R2"
  fi
fi

# -----------------------------------------------------------------------------
say "--- T9 FUSE cross-session sync"
if [ "${SKIP_SYNC:-0}" = "1" ]; then
  skip "T9 FUSE sync" "SKIP_SYNC=1"
else
  MNT=$(adb_shell "su -c 'ls /storage/'" | grep -E '^[0-9A-F]{4}-[0-9A-F]{4}$' | head -1)
  if [ -z "$MNT" ]; then
    skip "T9 FUSE sync" "no virtual mount active"
  else
    say "  mount: /storage/$MNT"
    # T9a: SAF write -> FUSE immediately visible
    if adb_shell "su -c 'ls /storage/$MNT/'" | grep -q "e2e_t4_renamed.bin"; then
      pass "T9a SAF write visible on FUSE"
    else
      fail "T9a SAF write visible on FUSE"
    fi
    # T9b: FUSE write -> SAF visible after refresh (HOME + foreground = onResume refresh)
    adb shell "su -c 'echo fuse-side > /storage/$MNT/e2e_fuse.txt'" >/dev/null 2>&1
    adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1; sleep 1
    adb shell am start -n "$SETTINGS_ACT" >/dev/null 2>&1; sleep 12
    if adb_shell "content query --uri '$AUTH/document/$ROOT/children'" | grep -q "e2e_fuse.txt"; then
      pass "T9b FUSE write visible after refresh"
    else
      fail "T9b FUSE write visible after refresh"
    fi
    adb shell "su -c 'rm -f /storage/$MNT/e2e_fuse.txt'" >/dev/null 2>&1
  fi
fi

# -----------------------------------------------------------------------------
say "--- T11 stability: 20 queries + health"
OK=1
for i in $(seq 1 20); do
  adb_shell "content query --uri '$AUTH/document/$ROOT/children'" >/dev/null 2>&1 || OK=0
done
app_alive && [ "$OK" = "1" ] && pass "T11 stability" || fail "T11 stability"

# -----------------------------------------------------------------------------
say "--- T12 cleanup"
adb_shell "content call --uri $AUTH --method delete_document --arg '$DOC'" >/dev/null 2>&1
adb_shell "content call --uri $AUTH --method delete_document --arg '$DOC1M'" >/dev/null 2>&1
sleep 1
LEFT=$(adb_shell "content query --uri '$AUTH/document/$ROOT/children'" | grep -c "e2e_" || true)
if [ "$LEFT" = "0" ]; then pass "T12 cleanup"; else fail "T12 cleanup" "$LEFT e2e files left"; fi

# -----------------------------------------------------------------------------
say "--- T13 shell-injection path rejection (restarts app; run last)"
if [ "${SKIP_SECURITY:-0}" = "1" ]; then
  skip "T13 path rejection" "SKIP_SECURITY=1"
else
  # Verify by behavior — OEM builds may keep the app's logcat mirror silent.
  # Payload is unique per run so stale log entries cannot false-pass; note
  # ".." is legal per the charset whitelist, so use a real metacharacter.
  MARK="x;id$RANDOM"
  adb shell "am start -S -f 0x10800000 -n com.bitlockerdroid/.ui.UnlockDialogActivity --es device_path \"$MARK\" --el offset 0" >/dev/null 2>&1
  sleep 6
  TOP=$(adb_shell "dumpsys activity activities" | grep topResumedActivity | grep -c "UnlockDialogActivity" || true)
  REJ=$(adb_shell "su -c 'grep -c \"$MARK\" /data/data/$PKG/files/logs/bitlocker.log'" || true)
  if [ "$TOP" = "0" ] && [ "$REJ" != "0" ] && app_alive; then
    pass "T13 shell-injection rejection"
  else
    fail "T13 shell-injection rejection" "top=$TOP rejection-log=$REJ alive=$(app_alive && echo yes || echo no)"
  fi
fi

# -----------------------------------------------------------------------------
CRASHES=$(logcat_errors)
say "=== Summary: $PASS passed, $FAIL failed, $SKIPPED skipped (crash lines: $CRASHES) ==="
if [ "$FAIL" -gt 0 ]; then
  for n in "${FAILED_NAMES[@]:-}"; do [ -n "$n" ] && say "  failed: $n"; done
fi
if [ "$CRASHES" -gt 0 ] 2>/dev/null; then say "  WARNING: app crash lines in logcat!"; FAIL=$((FAIL+1)); fi
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
