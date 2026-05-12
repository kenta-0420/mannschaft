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
            Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
