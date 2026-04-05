package com.mannschaft.app.workflow;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F05.6 汎用ワークフロー・承認エンジンのエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum WorkflowErrorCode implements ErrorCode {

    /** テンプレートが見つからない */
    TEMPLATE_NOT_FOUND("WORKFLOW_001", "ワークフローテンプレートが見つかりません", Severity.WARN),

    /** 申請が見つからない */
    REQUEST_NOT_FOUND("WORKFLOW_002", "ワークフロー申請が見つかりません", Severity.WARN),

    /** ステップが見つからない */
    STEP_NOT_FOUND("WORKFLOW_003", "承認ステップが見つかりません", Severity.WARN),

    /** 承認者が見つからない */
    APPROVER_NOT_FOUND("WORKFLOW_004", "承認者が見つかりません", Severity.WARN),

    /** コメントが見つからない */
    COMMENT_NOT_FOUND("WORKFLOW_005", "コメントが見つかりません", Severity.WARN),

    /** 添付ファイルが見つからない */
    ATTACHMENT_NOT_FOUND("WORKFLOW_006", "添付ファイルが見つかりません", Severity.WARN),

    /** 無効な申請ステータス遷移 */
    INVALID_STATUS_TRANSITION("WORKFLOW_007", "この操作は現在のステータスでは実行できません", Severity.WARN),

    /** テンプレートが無効化されている */
    TEMPLATE_INACTIVE("WORKFLOW_008", "このテンプレートは無効化されています", Severity.WARN),

    /** 承認権限なし */
    NOT_APPROVER("WORKFLOW_009", "この承認ステップの承認権限がありません", Severity.WARN),

    /** 既に承認済み */
    ALREADY_DECIDED("WORKFLOW_010", "既に判断済みです", Severity.WARN),

    /** 必須フィールド未入力 */
    REQUIRED_FIELD_MISSING("WORKFLOW_011", "必須フィールドが未入力です", Severity.ERROR),

    /** フィールド値が不正 */
    INVALID_FIELD_VALUE("WORKFLOW_012", "フィールド値が不正です", Severity.ERROR),

    /** ステップ定義が不正 */
    INVALID_STEP_DEFINITION("WORKFLOW_013", "ステップ定義が不正です", Severity.ERROR),

    /** 電子印鑑が必要 */
    SEAL_REQUIRED("WORKFLOW_014", "このワークフローには電子印鑑が必要です", Severity.WARN),

    /** 楽観的排他制御エラー */
    OPTIMISTIC_LOCK_CONFLICT("WORKFLOW_015", "データが他のユーザーによって更新されています", Severity.ERROR);

    private final String code;
    private final String message;
    private final Severity severity;
}
