package com.mannschaft.app.repairplan;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 修繕計画機能のエラーコード（F08.8）。
 *
 * <p>REPAIR_PLAN_001〜003: 案5 修繕計画項目 CRUD 用。
 * REPAIR_PLAN_CSV_001〜004: CSV インポート用。</p>
 */
@Getter
@RequiredArgsConstructor
public enum RepairPlanErrorCode implements ErrorCode {

    /** 計画項目が見つからない（テナント／スコープ不一致を含む。IDOR 対策で 404）。 */
    ITEM_NOT_FOUND("REPAIR_PLAN_001", "計画項目が見つかりません", Severity.WARN),

    /** 楽観ロック競合（If-Match の version 不一致）。 */
    ITEM_VERSION_CONFLICT("REPAIR_PLAN_002", "計画項目が他のユーザーにより更新されています", Severity.WARN),

    /** スコープ種別が不正（TEAM / ORGANIZATION 以外）。 */
    INVALID_SCOPE("REPAIR_PLAN_003", "スコープ種別が不正です", Severity.WARN),

    /** CSV プレビューが見つからない／TTL 切れ */
    REPAIR_PLAN_CSV_001("REPAIR_PLAN_CSV_001",
            "CSV プレビューが見つかりません。プレビューの有効期限が切れている可能性があります",
            Severity.WARN),

    /** CSV ファイルサイズ上限超過 */
    REPAIR_PLAN_CSV_002("REPAIR_PLAN_CSV_002",
            "CSV ファイルサイズが上限を超えています（5MB まで）",
            Severity.WARN),

    /** CSV 形式不正 */
    REPAIR_PLAN_CSV_003("REPAIR_PLAN_CSV_003",
            "CSV ファイルの形式が不正です",
            Severity.WARN),

    /** バリデーションエラー行が含まれているため確定できない */
    REPAIR_PLAN_CSV_004("REPAIR_PLAN_CSV_004",
            "バリデーションエラー行が含まれているため取り込みを確定できません",
            Severity.WARN),

    /** シナリオ保存上限（1スコープ50件）超過。 */
    SCENARIO_LIMIT_EXCEEDED("REPAIR_PLAN_005", "シナリオの保存上限（50件）に達しています", Severity.WARN),

    /** ロック済みシナリオへの更新／削除試図（locked_at != null）。 */
    SCENARIO_ALREADY_LOCKED("REPAIR_PLAN_006", "このシナリオは議案変換済みのため変更できません", Severity.WARN),

    /** engine_version ミスマッチ（保存済みシナリオのエンジンバージョンが一致しない）。 */
    ENGINE_VERSION_MISMATCH("REPAIR_PLAN_007", "計算エンジンバージョンが一致しません。シナリオを再計算してください", Severity.WARN),

    /** simulate レートリミット超過。 */
    RATE_LIMIT_EXCEEDED("REPAIR_PLAN_009", "リクエスト頻度が上限を超えています。しばらく待ってから再試行してください", Severity.WARN),

    /** baseline_at から30日以上経過（再計算を推奨）。 */
    SIMULATION_BASELINE_STALE("REPAIR_PLAN_012", "シミュレーションの基準日から30日以上経過しています。再計算を推奨します", Severity.WARN),

    // ─── F08.8 Phase 4: 相見積もりカンバン ───────────────────────────────

    /** 反社チェックの有効期限が切れているため、カードに追加できない。 */
    COMPLIANCE_EXPIRED("REPAIR_PLAN_015", "反社チェックの有効期限が切れています", Severity.WARN),

    /** ステージ遷移が規則に違反している（後戻り、終端ステージからの遷移など）。 */
    INVALID_STAGE_TRANSITION("REPAIR_PLAN_016", "無効なステージ遷移です", Severity.WARN),

    /** 指定されたカンバンが見つからない（テナント／スコープ不一致を含む。IDOR 対策で 404）。 */
    KANBAN_NOT_FOUND("REPAIR_PLAN_017", "カンバンが見つかりません", Severity.WARN),

    /** 指定されたカードが見つからない（テナント／スコープ不一致を含む。IDOR 対策で 404）。 */
    CARD_NOT_FOUND("REPAIR_PLAN_018", "カードが見つかりません", Severity.WARN),

    // ─── F08.8 Phase 5: 申し送りパック ───────────────────────────────────

    /** 指定された申し送りパックが見つからない（IDOR 対策で 404）。 */
    PACK_NOT_FOUND("REPAIR_PLAN_020", "申し送りパックが見つかりません", Severity.WARN),

    /** 指定された任期が見つからない（IDOR 対策で 404）。 */
    TERM_NOT_FOUND("REPAIR_PLAN_021", "理事任期が見つかりません", Severity.WARN),

    /** PDF 生成失敗（R2 アップロード失敗など）。 */
    PACK_GENERATION_FAILED("REPAIR_PLAN_022", "申し送りパックの生成に失敗しました", Severity.ERROR),

    /** 申し送りパックがまだ生成中（GENERATING 状態）のためダウンロード不可。 */
    PACK_NOT_READY("REPAIR_PLAN_023", "申し送りパックはまだ生成中です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
