package com.mannschaft.app.errorreport;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F12.5 エラーレポートのエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ErrorReportErrorCode implements ErrorCode {

    /** エラーレポートが見つからない（404） */
    ERROR_REPORT_NOT_FOUND("ERROR_REPORT_001", "エラーレポートが見つかりません", Severity.WARN),

    /** このエラーレポートは既に解決済み */
    ERROR_REPORT_ALREADY_RESOLVED("ERROR_REPORT_002", "このエラーレポートは既に解決済みです", Severity.WARN),

    /** 一括更新の上限超過 */
    ERROR_REPORT_BULK_LIMIT_EXCEEDED("ERROR_REPORT_003", "一括更新の上限（100件）を超えています", Severity.WARN),

    /** 無効なステータス遷移 */
    ERROR_REPORT_INVALID_STATUS_TRANSITION("ERROR_REPORT_004", "無効なステータス遷移です", Severity.WARN),

    /** 無効なワークフロー遷移（IGNORED状態での工程更新拒否・状態競合のため409） */
    ERROR_REPORT_005("ERROR_REPORT_005", "無効なワークフロー遷移です", Severity.WARN),

    /** 担当者の権限不正 */
    ERROR_REPORT_006("ERROR_REPORT_006", "指定された担当者は SYSTEM_ADMIN 権限を持ちません", Severity.WARN),

    /** AI 分析機能が無効 */
    ERROR_REPORT_007("ERROR_REPORT_007", "AI 分析機能が無効です", Severity.WARN),

    /** AI 月次予算上限到達 */
    ERROR_REPORT_008("ERROR_REPORT_008", "AI 月次予算上限に達しました", Severity.WARN),

    /** 関連処理が既に進行中（AI 分析 / GitHub Issue 作成等の重複ロック。状態競合のため409） */
    ERROR_REPORT_009("ERROR_REPORT_009", "処理が進行中です", Severity.INFO),

    /** GitHub 連携が設定されていない */
    ERROR_REPORT_010("ERROR_REPORT_010", "GitHub 連携が設定されていません", Severity.WARN),

    /** GitHub Issue の作成に失敗した */
    ERROR_REPORT_011("ERROR_REPORT_011", "GitHub Issue の作成に失敗しました", Severity.ERROR),

    /** GitHub Issue は既に作成済み（二重作成防止・状態競合のため409） */
    ERROR_REPORT_012("ERROR_REPORT_012", "GitHub Issue は既に作成されています", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
