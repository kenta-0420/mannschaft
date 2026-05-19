package com.mannschaft.app.circulation;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F05.2 回覧板のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum CirculationErrorCode implements ErrorCode {

    /** 回覧文書が見つからない */
    DOCUMENT_NOT_FOUND("CIRCULATION_001", "回覧文書が見つかりません", Severity.WARN),

    /** 受信者が見つからない */
    RECIPIENT_NOT_FOUND("CIRCULATION_002", "受信者が見つかりません", Severity.WARN),

    /** 添付ファイルが見つからない */
    ATTACHMENT_NOT_FOUND("CIRCULATION_003", "添付ファイルが見つかりません", Severity.WARN),

    /** コメントが見つからない */
    COMMENT_NOT_FOUND("CIRCULATION_004", "コメントが見つかりません", Severity.WARN),

    /** 文書ステータス不正 */
    INVALID_DOCUMENT_STATUS("CIRCULATION_005", "この操作は現在の文書ステータスでは実行できません", Severity.WARN),

    /** 受信者ステータス不正 */
    INVALID_RECIPIENT_STATUS("CIRCULATION_006", "この操作は現在の受信者ステータスでは実行できません", Severity.WARN),

    /** 受信者重複 */
    DUPLICATE_RECIPIENT("CIRCULATION_007", "この受信者は既に追加されています", Severity.WARN),

    /** 順次回覧の順序違反 */
    SEQUENTIAL_ORDER_VIOLATION("CIRCULATION_008", "順次回覧の順序に従って押印してください", Severity.WARN),

    /** 押印済み文書の変更不可 */
    DOCUMENT_ALREADY_STAMPED("CIRCULATION_009", "押印済みの文書は変更できません", Severity.WARN),

    /** コメント編集権限なし */
    COMMENT_NOT_OWNED("CIRCULATION_010", "自分のコメントのみ編集・削除できます", Severity.WARN),

    /** 期限超過 */
    DOCUMENT_OVERDUE("CIRCULATION_011", "回覧期限を超過しています", Severity.WARN),

    /** 受信者が空 */
    EMPTY_RECIPIENTS("CIRCULATION_012", "受信者を1名以上指定してください", Severity.ERROR),

    /** 一括処理の件数超過 */
    BATCH_SIZE_EXCEEDED("CIRCULATION_013", "一括処理可能な件数を超過しています（最大20件）", Severity.WARN),

    /** 一括処理対象が空 */
    EMPTY_BATCH("CIRCULATION_014", "処理対象を1件以上指定してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
