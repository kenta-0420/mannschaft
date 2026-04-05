package com.mannschaft.app.budget;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予算・会計機能のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum BudgetErrorCode implements ErrorCode {

    /** 年度が見つからない */
    BUDGET_001("BUDGET_001", "指定された予算年度が見つかりません", Severity.WARN),

    /** 費目が見つからない */
    BUDGET_002("BUDGET_002", "指定された予算費目が見つかりません", Severity.WARN),

    /** トランザクションが見つからない */
    BUDGET_003("BUDGET_003", "指定された予算取引が見つかりません", Severity.WARN),

    /** 年度がCLOSED状態 */
    BUDGET_004("BUDGET_004", "年度が締め切られているため操作できません", Severity.WARN),

    /** 費目種別不一致 */
    BUDGET_005("BUDGET_005", "費目の種別が取引種別と一致しません", Severity.WARN),

    /** 予算上限超過 */
    BUDGET_006("BUDGET_006", "予算配分額の上限を超過しています", Severity.WARN),

    /** 費目数上限超過 */
    BUDGET_007("BUDGET_007", "費目数が上限を超えています", Severity.WARN),

    /** 承認待ち状態 */
    BUDGET_008("BUDGET_008", "取引は承認待ち状態のため操作できません", Severity.WARN),

    /** 取消済み */
    BUDGET_009("BUDGET_009", "取引は既に取消済みです", Severity.WARN),

    /** 取消仕訳の取消不可 */
    BUDGET_010("BUDGET_010", "取消仕訳を再度取り消すことはできません", Severity.WARN),

    /** 報告書が見つからない */
    BUDGET_011("BUDGET_011", "指定された予算報告書が見つかりません", Severity.WARN),

    /** 設定が見つからない */
    BUDGET_012("BUDGET_012", "指定された予算設定が見つかりません", Severity.WARN),

    /** CSVエラー */
    BUDGET_013("BUDGET_013", "CSVファイルの処理中にエラーが発生しました", Severity.ERROR),

    /** 添付数上限超過 */
    BUDGET_014("BUDGET_014", "添付ファイル数が上限を超えています", Severity.WARN),

    /** ファイルサイズ上限超過 */
    BUDGET_015("BUDGET_015", "ファイルサイズが上限を超えています", Severity.WARN),

    /** 年度期間重複 */
    BUDGET_016("BUDGET_016", "指定された期間は既存の年度と重複しています", Severity.WARN),

    /** 年度名重複 */
    BUDGET_017("BUDGET_017", "同じ名前の年度が既に存在します", Severity.WARN),

    /** 費目名重複 */
    BUDGET_018("BUDGET_018", "同じ名前の費目が既に存在します", Severity.WARN),

    /** 階層数上限超過 */
    BUDGET_019("BUDGET_019", "費目の階層数が上限を超えています", Severity.WARN),

    /** トランザクション存在のため年度削除不可 */
    BUDGET_020("BUDGET_020", "取引が存在するため年度を削除できません", Severity.WARN),

    /** 添付ファイルが見つからない */
    BUDGET_021("BUDGET_021", "指定された添付ファイルが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
