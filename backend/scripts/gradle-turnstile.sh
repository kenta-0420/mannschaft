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
#   - 取得: 一意な一時ディレクトリに info(PID/WINPID/CREATED/TIME/TOKEN) を書き、書込を検証してから
#     `mv -T` でロック名へ改名する。ロック名が存在する時点で必ず info が揃っている
#     （mkdir 直後に空ディレクトリが見える瞬間を作らない）。
#     `mv -T` はカーネルの rename(2) を直接使うため、ロック名が既に存在する
#     （＝非空ディレクトリ）場合は ENOTEMPTY で失敗する＝atomic な「先客あり」判定になる。
#   - stale奪取: 生存判定は「ロック内の保持者が死んでいるか」のみを条件とする
#     （Git Bash 上では kill -0 ではなく Windows 側の PID 照会を正とする。is_pid_alive のコメント参照）
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
# 180分（恒久値・上限）。テスト時のみ TURNSTILE_MAX_WAIT_SEC で一時的に短縮できるが、
# 正の整数以外・または既定値超過は警告のうえ既定値へフォールバックする（重大1是正: 恒久上限180分を保証）
DEFAULT_MAX_WAIT_SEC=$((180 * 60))
MAX_WAIT_SEC="${TURNSTILE_MAX_WAIT_SEC:-${DEFAULT_MAX_WAIT_SEC}}"
case "${MAX_WAIT_SEC}" in
    ''|*[!0-9]*)
        echo "[turnstile] 警告: TURNSTILE_MAX_WAIT_SEC='${MAX_WAIT_SEC}' は正の整数ではないため既定値${DEFAULT_MAX_WAIT_SEC}秒を使用します" >&2
        MAX_WAIT_SEC="${DEFAULT_MAX_WAIT_SEC}"
        ;;
    0)
        echo "[turnstile] 警告: TURNSTILE_MAX_WAIT_SEC=0 は不正なため既定値${DEFAULT_MAX_WAIT_SEC}秒を使用します" >&2
        MAX_WAIT_SEC="${DEFAULT_MAX_WAIT_SEC}"
        ;;
    *)
        if [ "${MAX_WAIT_SEC}" -gt "${DEFAULT_MAX_WAIT_SEC}" ]; then
            echo "[turnstile] 警告: TURNSTILE_MAX_WAIT_SEC=${MAX_WAIT_SEC} は既定上限${DEFAULT_MAX_WAIT_SEC}秒(180分)を超えるため既定値へフォールバックします" >&2
            MAX_WAIT_SEC="${DEFAULT_MAX_WAIT_SEC}"
        fi
        ;;
esac
MAX_ABNORMAL_STREAK=5             # 「先客あり」以外の異常が連続した場合の即時エラー閾値
STALE_TMP_AGE_SEC=86400           # 残骸掃除の対象年齢（秒）。86400秒＝1日
LEGACY_STALE_AGE_SEC=$((6 * 3600))  # WINPID を持たない旧形式ロックを stale とみなす経過秒数（6時間）

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

# 実行環境の判定。Git Bash(MSYS2) では $$ は MSYS の PID であり、
# Windows のプロセス PID（WINPID）とは別の名前空間である。
# 例（実測）: bash の $$=35213 に対し WINPID=28860。
if [ -r "/proc/$$/winpid" ]; then
    IS_MSYS=1
    SELF_WINPID="$(cat "/proc/$$/winpid" 2>/dev/null)"
else
    IS_MSYS=0
    SELF_WINPID="$$"
fi
[ -n "${SELF_WINPID}" ] || SELF_WINPID="$$"

# Windows 側へ PID を照会する。標準出力は次のいずれか:
#   DEAD            : その Windows PID のプロセスは存在しない
#   ALIVE:<created> : 存在する（created は UTC の生成時刻 yyyyMMddHHmmss）
#   （空文字）      : 照会自体ができなかった（PowerShell 不在等）
# PowerShell に渡すコマンドにダブルクオートを含めない（MSYS の引用符変換事故を避ける）。
win_query() {
    local winpid="$1" ps_cmd
    case "${winpid}" in
        ''|*[!0-9]*) return 0 ;;
    esac
    ps_cmd="\$p = Get-CimInstance Win32_Process -Filter ('ProcessId=${winpid}') -ErrorAction SilentlyContinue; if (-not \$p) { 'DEAD' } else { 'ALIVE:' + \$p.CreationDate.ToUniversalTime().ToString('yyyyMMddHHmmss') }"
    powershell.exe -NoProfile -NonInteractive -Command "${ps_cmd}" 2>/dev/null | tr -d '\r\n'
}

# 自分自身の生成時刻を控えておく（同じ PID 番号の使い回しを見分けるため）。
# 取得できなければ空のままとし、判定側は「照合しない」に倒す。
SELF_CREATED=""
if [ "${IS_MSYS}" -eq 1 ]; then
    __self_q="$(win_query "${SELF_WINPID}")"
    case "${__self_q}" in
        ALIVE:*) SELF_CREATED="${__self_q#ALIVE:}" ;;
    esac
    unset __self_q
fi

# ロック保持者が生存しているかを判定する。
#
# 【重要・実測に基づく】Git Bash の `kill -0` は Windows の PID に対しては機能しない。
#   MSYS の PID 名前空間と Windows の PID 名前空間は別物であり、
#   `kill -0 <n>` は「MSYS PID が n のプロセス」の有無しか見ていない。
#   実測(2026-08-21): 現に生きている MSYS PID 群(34388/28697/25499/10720 等)は
#   すべて `kill -0` が成功する一方、同じ番号を Windows の PID として
#   `Get-CimInstance Win32_Process -Filter "ProcessId=<n>"` で照会すると全て not running だった。
#   逆に、死んだビルドが記録した MSYS PID は番号が小さく再利用も速いため、
#   無関係な別の bash に容易に一致し「常に生存」と誤判定される。
#   その結果 stale 奪取が原理的に発動せず、死んだロックのせいで待機上限まで
#   待たされる事故が起きた（実測835秒待機、手動でロックを退避して復旧）。
#
# よって MSYS 上では Windows 側の PID 照会を正とする。
# 判定不能な場合は必ず「生存」に倒す（生きているビルドのロックを誤って
# 奪うと、turnstile が防いでいる並列ビルド衝突が起きるため）。
#
# 引数: <MSYS PID> <WINPID> <CREATED> <TIME>
is_pid_alive() {
    local pid="$1" winpid="$2" created="$3" locktime="$4"

    if [ "${IS_MSYS}" -ne 1 ]; then
        # POSIX 環境では PID 名前空間が1つなので kill -0 が正しい
        [ -n "${pid}" ] || return 1
        kill -0 "${pid}" 2>/dev/null
        return $?
    fi

    if [ -z "${winpid}" ]; then
        # WINPID を持たない旧形式のロック。Windows 側へ照会する術がないため、
        # 誤って奪わないよう原則は「生存」とみなす。ただし永久に待ち続けると
        # 本欠陥と同じ症状になるため、明らかに古い（LEGACY_STALE_AGE_SEC 超）場合のみ stale とみなす
        local lage
        case "${locktime}" in
            ''|*[!0-9]*) return 0 ;;
        esac
        lage=$(( $(now_epoch) - locktime ))
        if [ "${lage}" -gt "${LEGACY_STALE_AGE_SEC}" ]; then
            echo "[turnstile] 旧形式(WINPID無し)のロックが${lage}秒経過しているため stale とみなします" >&2
            return 1
        fi
        return 0
    fi

    local q
    q="$(win_query "${winpid}")"
    case "${q}" in
        DEAD)
            return 1
            ;;
        ALIVE:*)
            local live_created="${q#ALIVE:}"
            # 生成時刻を記録していない（旧形式）か、読めなかった場合は照合せず生存とみなす
            if [ -z "${created}" ] || [ -z "${live_created}" ]; then
                return 0
            fi
            if [ "${created}" = "${live_created}" ]; then
                return 0
            fi
            # PID は一致するが生成時刻が違う（PID 使い回し）＝記録された主は既に死んでいる
            return 1
            ;;
        *)
            # 照会不能。安全側（生存とみなして待つ）へ倒す
            return 0
            ;;
    esac
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
        echo "WINPID=${SELF_WINPID}"
        echo "CREATED=${SELF_CREATED}"
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

    local snap_pid snap_token snap_winpid snap_created snap_time
    snap_pid="$(read_info_field "${LOCK_DIR}" PID)"
    snap_token="$(read_info_field "${LOCK_DIR}" TOKEN)"
    snap_winpid="$(read_info_field "${LOCK_DIR}" WINPID)"
    snap_created="$(read_info_field "${LOCK_DIR}" CREATED)"
    snap_time="$(read_info_field "${LOCK_DIR}" TIME)"

    if { [ -n "${snap_pid}" ] || [ -n "${snap_winpid}" ]; } \
        && is_pid_alive "${snap_pid}" "${snap_winpid}" "${snap_created}" "${snap_time}"; then
        # 生存中は経過時間に関わらず奪取しない（重大3の是正）
        return 1
    fi

    # PID が死んでいる（info が壊れて PID が読めない場合も stale とみなす）
    local isolate
    isolate="$(make_scratch_dir_name stale)"
    if ! mv -T "${LOCK_DIR}" "${isolate}" 2>/dev/null; then
        # 改名失敗。中2の是正: LOCK_DIR の現況を再確認し、正常な競争負けと
        # 恒久異常を区別する。(a) LOCK_DIR が既に消えている、または
        # (b) LOCK_DIR の TOKEN が判定時スナップショットと異なる（＝別プロセスが
        # 既に奪取・再取得して世代が進んでいた）場合は正常な競争負けとしてカウントしない。
        # 同一世代のまま LOCK_DIR が残っているのに mv が失敗した場合のみ恒久異常とみなす
        if [ ! -d "${LOCK_DIR}" ]; then
            return 1
        fi

        local cur_token_after
        cur_token_after="$(read_info_field "${LOCK_DIR}" TOKEN)"
        if [ "${cur_token_after}" != "${snap_token}" ]; then
            return 1
        fi

        record_reclaim_abnormal "mv -T 失敗（同一世代のまま隔離できず）: ${LOCK_DIR} -> ${isolate}"
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
