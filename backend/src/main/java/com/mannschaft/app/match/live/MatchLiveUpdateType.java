package com.mannschaft.app.match.live;

/**
 * F08.10 / 07 §J.2.1 ライブ配信メッセージの種別。
 *
 * <p>観戦者は本種別で差分を弁別し、タイムライン/スコア/ステータスを部分更新する
 * （全件再取得は serverSeq の飛び検知時のみ・07 §J.4）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/07_realtime_spectator.md §J.2.1</p>
 */
public enum MatchLiveUpdateType {
    /** タイムラインイベントが追加された。 */
    EVENT_ADDED,
    /** タイムラインイベントが更新された。 */
    EVENT_UPDATED,
    /** タイムラインイベントが削除された。 */
    EVENT_DELETED,
    /** スコアが更新（確定）された。 */
    SCORE_UPDATED,
    /** 試合ステータスが遷移した。 */
    STATUS_CHANGED
}
