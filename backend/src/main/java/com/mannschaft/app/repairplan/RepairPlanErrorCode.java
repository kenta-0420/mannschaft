package com.mannschaft.app.repairplan;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 修繕計画機能のエラーコード（F08.8）。
 *
 * <p>足軽3 (CSV インポート) で利用する範囲のみを先行定義する。他フェーズで利用する
 * コードは別途追加してよい（番号衝突に注意）。</p>
 */
@Getter
@RequiredArgsConstructor
public enum RepairPlanErrorCode implements ErrorCode {

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
