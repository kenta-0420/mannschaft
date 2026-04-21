package com.mannschaft.app.jobmatching.exception;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F13.1 求人マッチング機能のエラーコード定義。
 *
 * <p>Phase 13.1.1 MVP の範囲で発生し得るエラーを網羅する。
 * 各コードの HTTP ステータスは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} にて
 * 個別にマッピングされる（404/409/403 等、Severity デフォルトの 400 と異なるもの）。</p>
 */
@Getter
@RequiredArgsConstructor
public enum JobmatchingErrorCode implements ErrorCode {

    /** 求人が見つからない（論理削除済み・ID不一致を含む） */
    JOB_NOT_FOUND("JOB_NOT_FOUND", "求人が見つかりません", Severity.WARN),

    /** 求人が公開状態でない（DRAFT/CLOSED/CANCELLED 等） */
    JOB_NOT_OPEN("JOB_NOT_OPEN", "求人が公開状態ではありません", Severity.WARN),

    /** 定員充足により応募不可 */
    JOB_CAPACITY_FULL("JOB_CAPACITY_FULL", "求人の定員に達しています", Severity.WARN),

    /** 応募締切を過ぎている */
    JOB_DEADLINE_PASSED("JOB_DEADLINE_PASSED", "応募締切を過ぎています", Severity.WARN),

    /** 既に同じ求人へ応募済み */
    JOB_ALREADY_APPLIED("JOB_ALREADY_APPLIED", "既にこの求人へ応募済みです", Severity.WARN),

    /** 応募レコードが見つからない */
    JOB_APPLICATION_NOT_FOUND("JOB_APPLICATION_NOT_FOUND", "応募が見つかりません", Severity.WARN),

    /** 自分自身が投稿した求人への応募は不可 */
    JOB_CANNOT_APPLY_SELF("JOB_CANNOT_APPLY_SELF", "自分が投稿した求人へは応募できません", Severity.WARN),

    /** 応募が既に処理済み（採用/不採用/取り下げ） */
    JOB_APPLICATION_NOT_PENDING("JOB_APPLICATION_NOT_PENDING", "応募は既に処理済みです", Severity.WARN),

    /** 契約レコードが見つからない */
    JOB_CONTRACT_NOT_FOUND("JOB_CONTRACT_NOT_FOUND", "契約が見つかりません", Severity.WARN),

    /** 許可されていない状態遷移 */
    JOB_INVALID_STATE_TRANSITION("JOB_INVALID_STATE_TRANSITION", "この状態遷移は許可されていません", Severity.WARN),

    /** 操作権限がない */
    JOB_PERMISSION_DENIED("JOB_PERMISSION_DENIED", "この操作を行う権限がありません", Severity.WARN),

    /** 指定された公開範囲は MVP 未対応 */
    JOB_VIS_NOT_SUPPORTED("JOB_VIS_NOT_SUPPORTED", "指定された公開範囲は現在サポートされていません", Severity.WARN),

    /** 報酬額が許容範囲外 */
    JOB_REWARD_OUT_OF_RANGE("JOB_REWARD_OUT_OF_RANGE", "報酬額が許容範囲外です", Severity.WARN),

    /** 差し戻し回数の上限を超過 */
    JOB_REJECTION_LIMIT_EXCEEDED("JOB_REJECTION_LIMIT_EXCEEDED", "差し戻し回数の上限を超過しました", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
