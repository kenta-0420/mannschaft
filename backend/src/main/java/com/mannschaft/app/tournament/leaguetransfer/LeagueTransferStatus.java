package com.mannschaft.app.tournament.leaguetransfer;

/**
 * リーグ移籍の状態（F08.7.1 / 03 §3.2）。両方向（昇格・降格）で共通の状態機械。
 *
 * <pre>
 * DISPATCHED ──┬─ approve ─→ PLACED      （受け入れ側 org が承認・配属）
 *              ├─ decline ─→ DECLINED    （受け入れ側 org が受け入れ拒否）
 *              └─ cancel  ─→ CANCELLED   （手放す側 org が応答前に取り消し）
 * </pre>
 *
 * <p><strong>Y-4（状態語彙の取り違え防止・§3.2）</strong>: {@code PLACED} は
 * transfer（移籍手続き）の状態であって participant の状態ではない。承認・配属（PLACED）時に作成する
 * {@code tournament_participant} は {@code ParticipantStatus.REGISTERED} で作成する
 * （participant に {@code PLACED} は存在しない）。</p>
 */
public enum LeagueTransferStatus {
    /** 送り出し起票済み・受け入れ承認待ち（手放す側 org ADMIN が起票時にセット）。 */
    DISPATCHED,
    /** 受け入れ側で配属完了（tournament_participant 作成済み・受け入れ側 org ADMIN がセット）。 */
    PLACED,
    /** 受け入れ側が拒否（受け入れ側 org ADMIN がセット）。 */
    DECLINED,
    /** 手放す側が応答前に取り消し（手放す側 org ADMIN がセット）。 */
    CANCELLED
}
