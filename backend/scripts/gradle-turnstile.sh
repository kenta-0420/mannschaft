#!/usr/bin/env bash
#
# gradle-turnstile.sh — 並列ビルドの交通整理スクリプト
#
# 背景（実証事実）:
#   このマシン上で Gradle の heavy build を複数同時に走らせると、
#   遅くなる原因はファイルロック待ちではなく CPU/IO リソース競合である
#   （3並列 --info ログでロック待ちゼロ件、単独310秒 → 3並列489〜513秒。約1.6倍悪化）。
#   本スクリプトは、このマシン上の heavy build を原則1本に絞る「順番待ち」を提供する。
#   --no-build-cache は不要（build cache 自体は正常に機能している）。
#
# heavy（このスクリプト経由が必須）:
#   test / build / bootJar / check / compileJava / compileTestJava を含む実行
# 軽量（対象外・素の gradlew を直接叩いてよい）:
#   help / tasks / properties / --status / --stop
#
# 使い方:
#   ./scripts/gradle-turnstile.sh ./gradlew build
#   ./scripts/gradle-turnstile.sh ./gradlew test
#
# ロック方式（TOCTOU対策）:
#   - 取得: 一意な一時ディレクトリに info(PID/TIME/TOKEN) を書いてから
#     `mv -T` でロック名へ改名する。ロック名が存在する時点で必ず info が揃っている
#     （mkdir 直後に空ディレクトリが見える瞬間を作らない）。
#     `mv -T` はカーネルの rename(2) を直接使うため、ロック名が既に存在する
#     （＝非空ディレクトリ）場合は ENOTEMPTY で失敗する＝atomic な「先客あり」判定になる。
#   - stale奪取: 生存判定は「ロック内 PID が死んでいるか」のみを条件とする
#     （生存中は90分超過でも奪取しない。異常終了して人間に委ねるまで待つ）。
#     奪取も同じ `mv -T` で「隔離名へ改名できた1プロセスだけ」が掃除できる方式にし、
#     判定〜削除の間に他プロセスが横取りしても二重削除・誤削除が起きないようにする。
#   - 解放: 自分の PID+TOKEN が info と一致する場合のみ、同じ `mv -T` 隔離方式で解放する。
#
set -u

if [ "$#" -eq 0 ]; then
    echo "使い方: $0 <command> [args...]" >&2
    echo "例: $0 ./gradlew build" >&2
    exit 1
fi

# ロック置き場はマシン全体で固定の1箇所とする（直列化の単位はマシンなのでロックも1つでよい）。
# GRADLE_USER_HOME には依存しない（worktree ごとに異なりうるため分裂を防ぐ）。
LOCK_ROOT="${LOCALAPPDATA:-$HOME}/gradle-turnstile"
LOCK_DIR="${LOCK_ROOT}/turnstile.lock.d"

POLL_INTERVAL_SEC=15
LOG_EVERY_SEC=60
MAX_WAIT_SEC=$((180 * 60)) # 180分。生存中の先客を待ち続ける上限

TOKEN="$$-$(date +%s%N 2>/dev/null || date +%s)-$RANDOM"
LOCK_HELD=0

if ! mkdir -p "${LOCK_ROOT}" 2>/dev/null; then
    echo "[turnstile] エラー: ロック置き場 ${LOCK_ROOT} を作成できません" >&2
    exit 1
fi

now_epoch() {
    date +%s
}

is_pid_alive() {
    local pid="$1"
    if [ -z "${pid}" ]; then
        return 1
    fi
    # Git Bash では kill -0 が Windows PID に対しても機能する
    kill -0 "${pid}" 2>/dev/null
}

read_info_field() {
    local dir="$1" key="$2"
    if [ -f "${dir}/info" ]; then
        grep -m1 "^${key}=" "${dir}/info" 2>/dev/null | cut -d= -f2-
    fi
}

# 一意な一時ディレクトリに info を書き、mv -T でロック名へ改名を試みる。
# 戻り値 0: 取得成功（自分がロックを保持） / 1: 先客あり（改名失敗、tmpは掃除済み）
try_acquire_once() {
    local tmp="${LOCK_ROOT}/.turnstile.tmp.$$.$RANDOM.$RANDOM"

    if ! mkdir "${tmp}" 2>/dev/null; then
        # 一意名のはずだが万一衝突したら失敗として扱い、上位で少し待って再試行させる
        return 1
    fi

    {
        echo "PID=$$"
        echo "TIME=$(now_epoch)"
        echo "TOKEN=${TOKEN}"
    } > "${tmp}/info"

    if mv -T "${tmp}" "${LOCK_DIR}" 2>/dev/null; then
        LOCK_HELD=1
        return 0
    fi

    # 改名失敗＝先客あり。自分の作業用一時ディレクトリだけを掃除する
    rm -rf "${tmp}" 2>/dev/null
    return 1
}

# stale（ロック内 PID が既に死んでいる）なロックの奪取を試みる。
# 隔離名への mv -T に成功した1プロセスだけが掃除できる。
# 戻り値 0: 奪取・掃除を実施した（呼び出し元は取得を再試行してよい）
# 戻り値 1: stale ではない、または奪取競争に負けた（呼び出し元は通常待機へ）
try_reclaim_stale_lock() {
    if [ ! -d "${LOCK_DIR}" ]; then
        return 1
    fi

    local lock_pid
    lock_pid="$(read_info_field "${LOCK_DIR}" PID)"

    if [ -n "${lock_pid}" ] && is_pid_alive "${lock_pid}"; then
        # 生存中は90分超過でも奪取しない（重大3の是正）
        return 1
    fi

    # PID が死んでいる（info が壊れて PID が読めない場合も stale とみなす）
    local isolate="${LOCK_ROOT}/.turnstile.stale.$$.$RANDOM.$RANDOM"
    if mv -T "${LOCK_DIR}" "${isolate}" 2>/dev/null; then
        echo "[turnstile] ロック保持プロセス PID=${lock_pid:-不明} は既に終了しているため強制奪取します" >&2
        rm -rf "${isolate}" 2>/dev/null
        return 0
    fi

    # 改名に失敗＝他プロセスが先に奪取済み、または既に解放済み。奪取競争に負けたとして通常待機へ
    return 1
}

acquire_lock() {
    local waited=0
    local last_log=0

    while true; do
        if try_acquire_once; then
            return 0
        fi

        if try_reclaim_stale_lock; then
            # 奪取に成功したので即座に取得を再試行する（sleepなしでよい）
            continue
        fi

        if [ -d "${LOCK_DIR}" ]; then
            local lock_pid
            lock_pid="$(read_info_field "${LOCK_DIR}" PID)"
            if [ -n "${lock_pid}" ] && is_pid_alive "${lock_pid}"; then
                if [ "${waited}" -ge "${MAX_WAIT_SEC}" ]; then
                    echo "[turnstile] エラー: PID=${lock_pid} の生存中ビルドを${MAX_WAIT_SEC}秒(180分)待ちましたが終わりません。人間の確認が必要です" >&2
                    exit 1
                fi
            fi
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
    if [ "${LOCK_HELD}" -ne 1 ]; then
        return 0
    fi

    if [ ! -d "${LOCK_DIR}" ]; then
        LOCK_HELD=0
        return 0
    fi

    local cur_pid cur_token
    cur_pid="$(read_info_field "${LOCK_DIR}" PID)"
    cur_token="$(read_info_field "${LOCK_DIR}" TOKEN)"

    if [ "${cur_pid}" = "$$" ] && [ "${cur_token}" = "${TOKEN}" ]; then
        local isolate="${LOCK_ROOT}/.turnstile.release.$$.$RANDOM.$RANDOM"
        if mv -T "${LOCK_DIR}" "${isolate}" 2>/dev/null; then
            rm -rf "${isolate}" 2>/dev/null
        fi
        # mv 失敗時は既に何者かに奪取/削除されている＝冪等に何もしない
    fi

    LOCK_HELD=0
}

# INT/TERM は解放後に trap を外して自分自身へ同シグナルを再送する定石形
handle_signal() {
    local sig="$1"
    trap - INT TERM EXIT
    release_lock
    kill -s "${sig}" $$
}

trap 'handle_signal INT' INT
trap 'handle_signal TERM' TERM
trap 'release_lock' EXIT

acquire_lock
echo "[turnstile] ロック取得。コマンドを実行します: $*" >&2

"$@"
status=$?

exit "${status}"
