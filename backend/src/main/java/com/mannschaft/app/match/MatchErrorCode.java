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
    MATCH_025("MATCH_025", "記録名義と対象サイドが一致しません", Severity.WARN),

    /**
     * win_method が当該競技の勝ち方カタログ外 / 球技への付与（400・01 §D.7 / 03 §C.4b）。
     *
     * <p>ターン制（将棋/囲碁）の勝ち方は {@code ShogiWinMethod}/{@code GoWinMethod} の列挙値のみ許容し、
     * 競技間の流用（将棋の千日手を囲碁へ等）・球技への {@code win_method} 付与を弾く（症状を隠さず根治）。</p>
     */
    MATCH_028("MATCH_028", "勝ち方の指定が不正です", Severity.WARN),

    /**
     * ターン制（将棋/囲碁）以外への対局結果記録 / 団体戦操作の競技不一致（400・sports/05・06）。
     *
     * <p>対局結果記録（勝者・勝ち方）はターン制競技のみ。団体戦の親子ボード操作もターン制を前提とする。
     * 球技 match への適用は競技不一致として弾く（症状を隠さない）。</p>
     */
    MATCH_029("MATCH_029", "この競技ではこの操作は利用できません", Severity.WARN),

    /**
     * 団体戦の親子ボードの帰属不一致 / 親が団体戦でない / 親子テナント不整合（IDOR・404・03 §C.4）。
     *
     * <p>子ボードの {@code parent_match_id} が指定の親と一致しない、親が団体戦の親（parent_match_id=NULL）でない、
     * 親子のテナントが不一致、のいずれも存在を漏らさず 404 で統一する（推測 ID による越境を遮断・01 §B.6 / §C.4）。</p>
     */
    MATCH_030("MATCH_030", "対象のボードが見つかりません", Severity.WARN),

    /** 局面写真添付が見つからない / 親子 match_id 不一致（IDOR 対策で 404・03 §C.7a）。 */
    MATCH_031("MATCH_031", "対象の局面写真が見つかりません", Severity.WARN),

    /** 局面写真の MIME がホワイトリスト外（SVG 等を除外・400・03 §C.7a）。 */
    MATCH_032("MATCH_032", "添付できないファイル形式です", Severity.WARN),

    /** 局面写真のサイズ上限超過（10MB・400・03 §C.7a）。 */
    MATCH_033("MATCH_033", "ファイルサイズが上限を超えています", Severity.WARN),

    /** 局面写真の添付件数上限超過（400・03 §C.7a）。 */
    MATCH_034("MATCH_034", "添付できる件数の上限を超えています", Severity.WARN),

    /**
     * COMPLETED 遷移時に採点競技（フィギュア/体操）の合計点が確定していない（400・01 §D.8 / sports/07_scored.md §4）。
     *
     * <p>採点競技（SCORED）は合計点（{@code home_score}/{@code away_score}・整数スケール×1000）が
     * 両方確定している必要がある。未確定（スコア NULL）のまま COMPLETED にはできない（症状を隠さない）。
     * 同点（整数スケール同値）は引分（DRAW）として COMPLETED 可（§6）。</p>
     */
    MATCH_035("MATCH_035", "試合を終了するには採点（合計点）の確定が必要です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
