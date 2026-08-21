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
#   - 取得: 一意な一時ディレクトリに info(PID/TIME/TOKEN) を書き、書込を検証してから
#     `mv -T` でロック名へ改名する。ロック名が存在する時点で必ず info が揃っている
#     （mkdir 直後に空ディレクトリが見える瞬間を作らない）。
#     `mv -T` はカーネルの rename(2) を直接使うため、ロック名が既に存在する
#     （＝非空ディレクトリ）場合は ENOTEMPTY で失敗する＝atomic な「先客あり」判定になる。
#   - stale奪取: 生存判定は「ロック内 PID が死んでいるか」のみを条件とする
#     （生存中は経過時間に関わらず奪取しない。待つのは取得できない実経過時間で最大180分）。
#     奪取も同じ `mv -T` で隔離名へ改名を試みるが、判定〜改名の間にも別プロセスが
#     先に奪取・再取得している「多重奪取」の極小窓は理論上残る。これを完全には塞がず、
#     【設計判断】改名成功後に隔離先の info を再読し、判定時点のスナップショット
#     （PID+TOKEN）と一致するかを照合する。不一致なら「自分より先に誰かが奪取して
#     世代が進んでいた」ということなので、直ちに元のロック名へ復元して通常待機に戻る。
#     turnstile はあくまで速度最適化であり、この極小窓を割ったところで最悪の帰結は
#     「一時的に2本のビルドが並走して遅くなるだけ」（直列化の一時的喪失＝性能劣化のみ）
#     であるため、完全な排他の証明までは求めず上記の緩和策で十分と判断し受容する。
#     ただし復元自体が失敗した場合は握りつぶさずエラー終了する（fail-safe）。
#   - 解放: 自分の PID+TOKEN が info と一致する場合のみ、同じ `mv -T` 隔離方式で解放する。
#   - 恒久エラー検知: 「先客あり」以外の異常（取得側の mv 失敗で LOCK_DIR 不在、
#     奪取側の隔離 mv 失敗が繰り返される等）が連続して起きた場合は原因不明のまま
#     待ち続けず即エラー終了する。取得側と奪取側は別カウンタで管理し、
#     どちらか一方の正常系がもう一方の異常カウントを打ち消さないようにする。
#   - 残骸掃除: 一時/隔離ディレクトリ名には隔離実行時刻（epoch秒）を埋め込み、
#     起動時のベストエフォート掃除は名前中の時刻で1日超のものだけを対象にする
#     （mtime 依存にすると、奪取直後でまだ使用中の隔離ディレクトリを他プロセスの
#     起動時掃除が誤って削除しうるため）。時刻を埋め込んでいない旧形式の残骸は
#     無条件で掃除対象とする。
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
# 180分（恒久値）。テスト時のみ TURNSTILE_MAX_WAIT_SEC で一時的に短縮できる
MAX_WAIT_SEC="${TURNSTILE_MAX_WAIT_SEC:-$((180 * 60))}"
MAX_ABNORMAL_STREAK=5             # 「先客あり」以外の異常が連続した場合の即時エラー閾値
STALE_TMP_AGE_SEC=86400           # 残骸掃除の対象年齢（秒）。86400秒＝1日

TOKEN="$$-$(date +%s%N 2>/dev/null || date +%s)-$RANDOM"
LOCK_HELD=0
START_EPOCH="$(date +%s)"

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

# info ファイルに PID/TIME/TOKEN の3キーが揃っているか検証する
verify_info_complete() {
    local dir="$1"
    local pid time token
    pid="$(read_info_field "${dir}" PID)"
    time="$(read_info_field "${dir}" TIME)"
    token="$(read_info_field "${dir}" TOKEN)"
    [ -n "${pid}" ] && [ -n "${time}" ] && [ -n "${token}" ]
}

# 一時/隔離ディレクトリの命名規則: .turnstile.<kind>.<epoch>.<pid>.<rand>.<rand>
# <kind> は tmp(取得中) / stale(奪取隔離) / release(解放隔離)
make_scratch_dir_name() {
    local kind="$1"
    echo "${LOCK_ROOT}/.turnstile.${kind}.$(now_epoch).$$.${RANDOM}.${RANDOM}"
}

# 残骸（クラッシュ等で取り残された一時/隔離ディレクトリ）をベストエフォートで掃除する。
# 名前に埋め込まれた epoch で1日超のものだけを対象にする（mtimeは使わない。
# 奪取直後でまだ使用中の隔離ディレクトリを誤削除しないため）。
# epoch を持たない旧形式名は無条件で掃除対象とする。
# 削除失敗は握りつぶさず警告を1行出す。
cleanup_stale_temp_dirs() {
    local d base epoch age now
    now="$(now_epoch)"

    while IFS= read -r d; do
        [ -z "${d}" ] && continue
        base="$(basename "${d}")"

        # 期待形式: .turnstile.<kind>.<epoch>.<pid>.<rand>.<rand>
        epoch="$(echo "${base}" | cut -d. -f4)"

        case "${epoch}" in
            ''|*[!0-9]*)
                # epoch が数値でない＝旧形式名。無条件で掃除対象
                ;;
            *)
                age=$((now - epoch))
                if [ "${age}" -le "${STALE_TMP_AGE_SEC}" ]; then
                    # まだ新しい（使用中の可能性がある）ので今回はスキップ
                    continue
                fi
                ;;
        esac

        if ! rm -rf "${d}" 2>/dev/null; then
            echo "[turnstile] 警告: 残骸ディレクトリの削除に失敗しました: ${d}" >&2
        fi
    done < <(find "${LOCK_ROOT}" -maxdepth 1 -mindepth 1 \
        \( -name '.turnstile.tmp.*' -o -name '.turnstile.stale.*' -o -name '.turnstile.release.*' \) 2>/dev/null)
}

# LOCK_DIR が通常ファイル等、ディレクトリ以外の異常な状態でないか検査する
check_lock_dir_sane() {
    if [ -e "${LOCK_DIR}" ] && [ ! -d "${LOCK_DIR}" ]; then
        echo "[turnstile] エラー: ${LOCK_DIR} がディレクトリではありません（想定外の状態）。手動確認が必要です" >&2
        exit 1
    fi
}

# 取得側（try_acquire_once）の「先客あり以外の異常」連続カウンタ
ACQUIRE_ABNORMAL_STREAK=0
# 奪取側（try_reclaim_stale_lock）の「隔離できない異常」連続カウンタ
# 取得側の正常系がこちらをリセットしてしまわないよう、カウンタを分離する
RECLAIM_ABNORMAL_STREAK=0

record_acquire_abnormal() {
    ACQUIRE_ABNORMAL_STREAK=$((ACQUIRE_ABNORMAL_STREAK + 1))
    if [ "${ACQUIRE_ABNORMAL_STREAK}" -ge "${MAX_ABNORMAL_STREAK}" ]; then
        echo "[turnstile] エラー: 取得処理で「先客あり」以外の異常（権限/IO等の可能性）が${ACQUIRE_ABNORMAL_STREAK}回連続しました。原因を確認してください: $1" >&2
        exit 1
    fi
}

record_acquire_normal() {
    ACQUIRE_ABNORMAL_STREAK=0
}

record_reclaim_abnormal() {
    RECLAIM_ABNORMAL_STREAK=$((RECLAIM_ABNORMAL_STREAK + 1))
    if [ "${RECLAIM_ABNORMAL_STREAK}" -ge "${MAX_ABNORMAL_STREAK}" ]; then
        echo "[turnstile] エラー: staleと判定したロックの隔離/奪取に${RECLAIM_ABNORMAL_STREAK}回連続で失敗しました。原因を確認してください: $1" >&2
        exit 1
    fi
}

record_reclaim_normal() {
    RECLAIM_ABNORMAL_STREAK=0
}

# 一意な一時ディレクトリに info を書き、mv -T でロック名へ改名を試みる。
# 戻り値 0: 取得成功（自分がロックを保持） / 1: 先客あり（改名失敗、tmpは掃除済み）
try_acquire_once() {
    check_lock_dir_sane

    local tmp
    tmp="$(make_scratch_dir_name tmp)"

    if ! mkdir "${tmp}" 2>/dev/null; then
        # 一意名のはずだが万一衝突したら失敗として扱い、上位で少し待って再試行させる
        record_acquire_abnormal "一時ディレクトリ作成失敗: ${tmp}"
        return 1
    fi

    {
        echo "PID=$$"
        echo "TIME=$(now_epoch)"
        echo "TOKEN=${TOKEN}"
    } > "${tmp}/info"
    local write_status=$?

    if [ "${write_status}" -ne 0 ] || ! verify_info_complete "${tmp}"; then
        echo "[turnstile] エラー: ロック情報(info)の書込に失敗しました。ディスク/権限を確認してください" >&2
        rm -rf "${tmp}" 2>/dev/null
        exit 1
    fi

    if mv -T "${tmp}" "${LOCK_DIR}" 2>/dev/null; then
        LOCK_HELD=1
        record_acquire_normal
        return 0
    fi

    # 改名失敗。LOCK_DIR が存在すれば「先客あり」の正常な競合、
    # 存在しないのに失敗した場合は権限/IO等の恒久異常の疑いがある
    if [ -d "${LOCK_DIR}" ]; then
        record_acquire_normal
    else
        record_acquire_abnormal "mv -T 失敗（LOCK_DIR不在）: ${tmp} -> ${LOCK_DIR}"
    fi

    rm -rf "${tmp}" 2>/dev/null
    return 1
}

# stale（ロック内 PID が既に死んでいる）なロックの奪取を試みる。
# 戻り値 0: 奪取・掃除を実施した（呼び出し元は取得を再試行してよい）
# 戻り値 1: stale ではない、または奪取競争に負けた（呼び出し元は通常待機へ）
try_reclaim_stale_lock() {
    if [ ! -d "${LOCK_DIR}" ]; then
        return 1
    fi

    local snap_pid snap_token
    snap_pid="$(read_info_field "${LOCK_DIR}" PID)"
    snap_token="$(read_info_field "${LOCK_DIR}" TOKEN)"

    if [ -n "${snap_pid}" ] && is_pid_alive "${snap_pid}"; then
        # 生存中は経過時間に関わらず奪取しない（重大3の是正）
        return 1
    fi

    # PID が死んでいる（info が壊れて PID が読めない場合も stale とみなす）
    local isolate
    isolate="$(make_scratch_dir_name stale)"
    if ! mv -T "${LOCK_DIR}" "${isolate}" 2>/dev/null; then
        # 改名に失敗＝他プロセスが先に奪取済み、または既に解放済み。
        # 奪取競争に負けただけなら正常だが、staleと判定したのに繰り返し隔離できない場合は
        # 恒久異常の疑いがあるため別カウンタで検知する
        record_reclaim_abnormal "mv -T 失敗（隔離できず）: ${LOCK_DIR} -> ${isolate}"
        return 1
    fi

    # 重大1の緩和策: 隔離先の info を再読し、判定時点のスナップショットと一致するか照合する。
    # 一致しなければ、自分が改名する間に別プロセスが既に奪取して世代が進んでいたということ
    # （多重奪取の極小窓）。turnstile は最適化であり最悪帰結は性能劣化のみのため、
    # ここでは元のロック名へ復元して通常待機へ戻す（復元失敗のみ fail-safe でエラー終了）。
    local reread_pid reread_token
    reread_pid="$(read_info_field "${isolate}" PID)"
    reread_token="$(read_info_field "${isolate}" TOKEN)"

    if [ "${reread_pid}" != "${snap_pid}" ] || [ "${reread_token}" != "${snap_token}" ]; then
        echo "[turnstile] 多重奪取の競合を検知（世代不一致）。ロックを元に戻して通常待機へ戻ります" >&2
        if mv -T "${isolate}" "${LOCK_DIR}" 2>/dev/null; then
            record_reclaim_normal
            return 1
        else
            echo "[turnstile] エラー: 競合検知後のロック復元に失敗しました。手動確認が必要です: ${isolate}" >&2
            exit 1
        fi
    fi

    echo "[turnstile] ロック保持プロセス PID=${snap_pid:-不明} は既に終了しているため強制奪取します" >&2
    rm -rf "${isolate}" 2>/dev/null
    record_reclaim_normal
    return 0
}

acquire_lock() {
    local elapsed last_log=0

    while true; do
        elapsed=$(( $(now_epoch) - START_EPOCH ))
        if [ "${elapsed}" -ge "${MAX_WAIT_SEC}" ]; then
            echo "[turnstile] エラー: ロックを${MAX_WAIT_SEC}秒(実経過時間ベース)取得できませんでした。原因を問わず上限に達したため終了します。人間の確認が必要です" >&2
            exit 1
        fi

        if try_acquire_once; then
            return 0
        fi

        if try_reclaim_stale_lock; then
            # 奪取に成功したので即座に取得を再試行する（sleepなしでよい）。
            # ループ先頭に戻るため、次のイテレーションで必ず上限チェックを通る
            continue
        fi

        if [ "${elapsed}" -eq 0 ] || [ $((elapsed - last_log)) -ge "${LOG_EVERY_SEC}" ]; then
            echo "[turnstile] 他のビルドが進行中のため待機中... (${elapsed}秒経過)" >&2
            last_log="${elapsed}"
        fi

        sleep "${POLL_INTERVAL_SEC}"
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
        local isolate
        isolate="$(make_scratch_dir_name release)"
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

cleanup_stale_temp_dirs
acquire_lock
echo "[turnstile] ロック取得。コマンドを実行します: $*" >&2

"$@"
status=$?

exit "${status}"
