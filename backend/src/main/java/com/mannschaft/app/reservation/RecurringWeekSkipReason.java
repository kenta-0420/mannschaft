package com.mannschaft.app.reservation;

/**
 * 定期予約（F03.4.5 §6.2 W2-5）で「その回を処理できなかった理由」を表す列挙。
 *
 * <p>設計書 §6.2 は<b>スキップ＋結果明細</b>を採用している（満席週で中断しない）。
 * 会員は 1 回の操作で「8 週指定 → 6 件成立・2 件スキップ（7/21 満席・8/4 枠なし）」という
 * 確定した結果を受け取る。本 enum はその明細（{@code skippedWeeks[].reason}）の語彙である。</p>
 *
 * <h2>用途ごとの有効値</h2>
 * <table>
 *   <caption>フローと理由の対応</caption>
 *   <tr><th>フロー</th><th>取り得る値</th></tr>
 *   <tr>
 *     <td>作成（{@code repeatWeeks}）</td>
 *     <td>{@link #NOT_GENERATED} / {@link #FULL} / {@link #CLOSED} / {@link #BLOCKED}
 *         / {@link #ALREADY_RESERVED} / {@link #UNAVAILABLE}</td>
 *   </tr>
 *   <tr>
 *     <td>以降すべてキャンセル（{@code THIS_AND_FOLLOWING}）</td>
 *     <td>{@link #NOT_CANCELLABLE} / {@link #CANCEL_DEADLINE_PASSED}</td>
 *   </tr>
 *   <tr>
 *     <td>series 一括承認（{@code scope=SERIES}）</td>
 *     <td>{@link #NOT_PENDING}</td>
 *   </tr>
 * </table>
 *
 * <p><b>なぜ 3 値（{@code NOT_GENERATED|FULL|BLOCKED}）に留めなかったか</b>: 設計書 §6.2 は主要 3 値のみを
 * 列挙しているが、実装では「受付停止（CLOSED）」「その枠に既に自分の予約がある」「その他の業務エラー」が
 * 実際に起こり得る。これらを {@code FULL} に丸めると会員に嘘の理由を伝えることになり、
 * 「症状を隠さない」原則（CLAUDE.md 障害対応の原則 2）に反する。値を増やす方向で正直に表現する
 * （FE は未知値をフォールバック文言で表示すればよく、契約は additive）。</p>
 */
public enum RecurringWeekSkipReason {

    /** その週に該当する枠がまだ生成されていない（枠の生成 horizon は 28 日 rolling・§6.2）。 */
    NOT_GENERATED,

    /** 枠が満席（{@code slot_status=FULL} もしくは {@code booked_count >= capacity}）。 */
    FULL,

    /** 枠が受付停止（{@code slot_status=CLOSED}）。管理者が明示的に閉じた枠。 */
    CLOSED,

    /** 単発または定期の予約不可枠と overlap している（§4.2 の {@code isBlockedByAny} 判定・AC-5-12）。 */
    BLOCKED,

    /** その枠に既に自分の active 予約（PENDING/CONFIRMED）がある（二重予約ガード）。 */
    ALREADY_RESERVED,

    /**
     * 上記以外の業務エラーで確保できなかった（並行予約による確保競合など）。
     *
     * <p>握り潰しではない: 実際の {@code ReservationErrorCode} は WARN ログに残す。</p>
     */
    UNAVAILABLE,

    /** キャンセル可能な状態でない（既に CANCELLED / COMPLETED / NO_SHOW）。 */
    NOT_CANCELLABLE,

    /** 会員キャンセルの締切（{@code cancel_deadline_hours}）を過ぎている（AC-5-7）。 */
    CANCEL_DEADLINE_PASSED,

    /** 承認対象（PENDING）でない（既に確定済み・キャンセル済み等）。 */
    NOT_PENDING
}
