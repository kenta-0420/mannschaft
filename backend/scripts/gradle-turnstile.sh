#!/usr/bin/env bash
#
# gradle-turnstile.sh — 並列ビルドの交通整理スクリプト
#
# 背景（実証事実）:
#   このマシン上で Gradle の heavy build（compileJava/test/build 等）を複数同時に走らせると、
#   遅くなる原因はファイルロック待ちではなく CPU/IO リソース競合である
#   （3並列 --info ログでロック待ちゼロ件、単独310秒 → 3並列489〜513秒。約1.6倍悪化）。
#   本スクリプトは、このマシン上の heavy build を原則1本に絞る「順番待ち」を提供する。
#   --no-build-cache は不要（build cache 自体は正常に機能している）。
#
# 使い方:
#   ./scripts/gradle-turnstile.sh ./gradlew build
#   ./scripts/gradle-turnstile.sh ./gradlew test
#
# 対象外（このスクリプトを経由する必要はない）:
#   compileJava のみ等の軽量タスク・単発の ./gradlew help 等
#
set -u

if [ "$#" -eq 0 ]; then
    echo "使い方: $0 <command> [args...]" >&2
    echo "例: $0 ./gradlew build" >&2
    exit 1
fi

# GRADLE_USER_HOME 配下にロックディレクトリを置く（gradlew が C:/gradle-home を自動設定する前提）
LOCK_ROOT="${GRADLE_USER_HOME:-C:/gradle-home}"
LOCK_DIR="${LOCK_ROOT}/turnstile.lock.d"
LOCK_INFO="${LOCK_DIR}/info"

POLL_INTERVAL_SEC=15
STALE_TIMEOUT_SEC=$((90 * 60)) # 90分
LOG_EVERY_SEC=60

mkdir -p "${LOCK_ROOT}" 2>/dev/null

# 指定 PID が生存しているか判定する（Git Bash 環境向け）
is_pid_alive() {
    local pid="$1"
    if [ -z "${pid}" ]; then
        return 1
    fi
    # Git Bash では kill -0 が Windows PID に対して動作する
    kill -0 "${pid}" 2>/dev/null
}

now_epoch() {
    date +%s
}

# ロック内情報（PID とタイムスタンプ）を読む
read_lock_pid() {
    if [ -f "${LOCK_INFO}" ]; then
        grep -m1 '^PID=' "${LOCK_INFO}" 2>/dev/null | cut -d= -f2
    fi
}

read_lock_time() {
    if [ -f "${LOCK_INFO}" ]; then
        grep -m1 '^TIME=' "${LOCK_INFO}" 2>/dev/null | cut -d= -f2
    fi
}

# stale（先客が死んでいる or 90分超過）なロックを強制奪取する
try_reclaim_stale_lock() {
    local lock_pid lock_time cur_time age

    if [ ! -d "${LOCK_DIR}" ]; then
        return 1
    fi

    lock_pid="$(read_lock_pid)"
    lock_time="$(read_lock_time)"
    cur_time="$(now_epoch)"

    if [ -z "${lock_time}" ]; then
        # info が壊れている/存在しない場合も stale とみなす
        echo "[turnstile] ロック情報が読めないため stale とみなし強制奪取します" >&2
        rm -rf "${LOCK_DIR}" 2>/dev/null
        return 0
    fi

    age=$((cur_time - lock_time))

    if [ -n "${lock_pid}" ] && is_pid_alive "${lock_pid}"; then
        if [ "${age}" -gt "${STALE_TIMEOUT_SEC}" ]; then
            echo "[turnstile] ロック保持プロセス PID=${lock_pid} は生存中だが90分超過のため強制奪取します" >&2
            rm -rf "${LOCK_DIR}" 2>/dev/null
            return 0
        fi
        return 1
    else
        echo "[turnstile] ロック保持プロセス PID=${lock_pid} は既に終了しているため強制奪取します" >&2
        rm -rf "${LOCK_DIR}" 2>/dev/null
        return 0
    fi
}

acquire_lock() {
    local waited=0
    local last_log=0

    while true; do
        if mkdir "${LOCK_DIR}" 2>/dev/null; then
            {
                echo "PID=$$"
                echo "TIME=$(now_epoch)"
            } > "${LOCK_INFO}"
            return 0
        fi

        # 先客あり。stale なら奪取を試みる
        if try_reclaim_stale_lock; then
            continue
        fi

        if [ "${waited}" -eq 0 ] || [ $((waited - last_log)) -ge "${LOG_EVERY_SEC}" ]; then
            echo "[turnstile] 他のビルドが進行中のため待機中... (${waited}秒経過)" >&2
            last_log="${waited}"
        fi

        sleep "${POLL_INTERVAL_SEC}"
        waited=$((waited + POLL_INTERVAL_SEC))
    done
}

release_lock() {
    # 自分が保持しているロックのみ解放する
    if [ -f "${LOCK_INFO}" ]; then
        local lock_pid
        lock_pid="$(read_lock_pid)"
        if [ "${lock_pid}" = "$$" ]; then
            rm -rf "${LOCK_DIR}" 2>/dev/null
        fi
    fi
}

trap release_lock EXIT INT TERM

acquire_lock
echo "[turnstile] ロック取得。コマンドを実行します: $*" >&2

"$@"
status=$?

exit "${status}"
