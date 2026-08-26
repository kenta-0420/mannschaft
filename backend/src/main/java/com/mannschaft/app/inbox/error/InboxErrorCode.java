package com.mannschaft.app.inbox.error;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.11 統合通知インボックスのエラーコード定義。
 *
 * <p>HTTP ステータスの個別マッピングは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} で行う
 * （404/409/422 等。400 は Severity.WARN 既定）。設計書: 02_api_design.md §3.6。</p>
 */
@Getter
@RequiredArgsConstructor
public enum InboxErrorCode implements ErrorCode {

    /** 未知の sourceType（400） */
    INBOX_INVALID_SOURCE_TYPE("INBOX_INVALID_SOURCE_TYPE", "不正な通知ソース種別です", Severity.WARN),

    /** snoozedUntil が過去 or 未指定（400） */
    INBOX_INVALID_SNOOZE_TIME("INBOX_INVALID_SNOOZE_TIME", "スヌーズ解除時刻は未来の時刻を指定してください", Severity.WARN),

    /** ラベル不存在 or 他人のラベル（404・IDOR 秘匿） */
    INBOX_LABEL_NOT_FOUND("INBOX_LABEL_NOT_FOUND", "指定されたラベルが存在しません", Severity.WARN),

    /** 同名ラベルが現役で存在（409） */
    INBOX_LABEL_NAME_DUPLICATE("INBOX_LABEL_NAME_DUPLICATE", "同じ名前のラベルが既に存在します", Severity.WARN),

    /** ラベル 20 件上限超過（422） */
    INBOX_LABEL_LIMIT_EXCEEDED("INBOX_LABEL_LIMIT_EXCEEDED", "ラベルの作成上限（20件）に達しています", Severity.WARN),

    /** 1 通知 10 ラベル上限超過（422） */
    INBOX_LABEL_PER_ITEM_EXCEEDED("INBOX_LABEL_PER_ITEM_EXCEEDED", "1件の通知に付与できるラベルは10件までです", Severity.WARN),

    /** triage 対象通知が存在しない / 本人宛てでない（404） */
    INBOX_SOURCE_NOT_FOUND("INBOX_SOURCE_NOT_FOUND", "指定された通知が存在しません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
