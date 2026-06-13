package com.mannschaft.app.match;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F08.10 試合記録・分析の機能固有エラーコード。
 *
 * <p>HTTP ステータスは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} で個別マッピングする
 * （NOT_FOUND→404 / FORBIDDEN→403 / 検証系→400・03 §C.4/C.6）。
 * IDOR 対策の原則に従い、不在・テナント越境・親子不一致は<b>すべて 404</b> で統一し存在を漏らさない。
 * 権限不足のみ 403、入力検証（カタログ列挙・event_type 整合・連鎖帰属など）は 400 とする。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/03_permissions_and_recording_modes.md §C</p>
 */
@Getter
@RequiredArgsConstructor
public enum MatchErrorCode implements ErrorCode {

    /** 試合が存在しない / テナント越境 / 削除済み（IDOR 対策で 404 に統一）。 */
    MATCH_001("MATCH_001", "対象の試合が見つかりません", Severity.WARN),

    /** イベントが存在しない / 親子 match_id 不一致（IDOR 対策で 404 に統一）。 */
    MATCH_002("MATCH_002", "対象のイベントが見つかりません", Severity.WARN),

    /** 出場記録が存在しない / 親子 match_id 不一致（IDOR 対策で 404）。 */
    MATCH_003("MATCH_003", "対象の出場記録が見つかりません", Severity.WARN),

    /** 操作権限なし（記録モード / 自チーム分のみ / 作成者・記録係制約など・403）。 */
    MATCH_010("MATCH_010", "この試合に対する操作権限がありません", Severity.WARN),

    /** event_type が当該競技のカタログ外（400・03 §C.4b (1)）。 */
    MATCH_020("MATCH_020", "この競技で利用できないイベント種別です", Severity.WARN),

    /** card_reason_code がカタログ列挙外 / event_type と不整合 / 非対象イベントへの付与（400・03 §C.4b）。 */
    MATCH_021("MATCH_021", "カードの理由コードが不正です", Severity.WARN),

    /** linked_event_id が同一 match に属さない（越境・親子不一致は 404 で統一・03 §C.4a）。 */
    MATCH_022("MATCH_022", "連鎖先のイベントが見つかりません", Severity.WARN),

    /** COMPLETED 遷移時に duration_minutes が未設定（連続時間制の必須化・400・02 §E.3）。 */
    MATCH_023("MATCH_023", "試合を終了するには試合時間（分）の設定が必要です", Severity.WARN),

    /** 入力値が業務範囲外 / 制約違反（400）。 */
    MATCH_024("MATCH_024", "入力内容に不備があります", Severity.WARN),

    /**
     * COMPLETED 遷移時にセット制（バレー）のセット結果が確定していない（400・01 §D.6 / sports/04_volleyball.md §4.3）。
     *
     * <p>セット制は獲得セット数（home/away_score）が両方確定し、かつ勝者がセット先取で決着している
     * （引分けなし）必要がある。未確定/同数のまま COMPLETED にはできない（症状を隠さない）。</p>
     */
    MATCH_026("MATCH_026", "試合を終了するには全セットの結果確定が必要です", Severity.WARN),

    /**
     * COMPLETED 遷移時にターン制（将棋/囲碁）の勝敗が確定していない（400・01 §D.6 / §B.1.2）。
     *
     * <p>ターン制は勝敗（home/away_score=1-0/0-1/0-0）が確定している必要がある。
     * 勝ち（1-0/0-1）または引分（0-0・千日手/持将棋/持碁）のいずれかでスコアが揃っていなければ終了できない。
     * 勝ち方（win_method）は引分以外で別途保持する（責務分離・§D.7）。</p>
     */
    MATCH_027("MATCH_027", "試合を終了するには勝敗の確定が必要です", Severity.WARN),

    /**
     * team_side と recorded_by_team_id の不整合（自サイド以外を自名義で記録できない・403・03 §C.4a）。
     *
     * <p>共同記録で相手サイドのイベントを自チーム名義（{@code recorded_by_team_id}=自チーム）で
     * 捏造することを防ぐドメイン不変条件違反。`recorded_by_team_id` はサーバー導出済みである前提の二重防御。</p>
     */
    MATCH_025("MATCH_025", "記録名義と対象サイドが一致しません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
