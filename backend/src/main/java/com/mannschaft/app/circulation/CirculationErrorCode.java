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
    EMPTY_RECIPIENTS("CIRCULATION_012", "受信者を1名以上指定してください", Severity.WARN),

    /** 一括処理の件数超過 (Phase 11 3-A) */
    BATCH_SIZE_EXCEEDED("CIRCULATION_013", "一括処理可能な件数を超過しています（最大20件）", Severity.WARN),

    /** 一括処理対象が空 (Phase 11 3-A) */
    EMPTY_BATCH("CIRCULATION_014", "処理対象を1件以上指定してください", Severity.WARN),

    /** 訂正可能期間（押印後 24h）を超過 (Phase 11 3-B) */
    CORRECTION_WINDOW_EXPIRED("CIRCULATION_015", "押印訂正は押印後24時間以内のみ可能です", Severity.WARN),

    /** 訂正は押印済みの場合のみ (Phase 11 3-B) */
    NOT_STAMPED_CANNOT_CORRECT("CIRCULATION_016", "押印していないため訂正できません", Severity.WARN),

    /** 委任者は自分自身を代理人に指定できない (Phase 11 3-B) */
    SELF_DELEGATION_NOT_ALLOWED("CIRCULATION_017", "自分自身に委任することはできません", Severity.WARN),

    /** 既に有効な委任が存在する (Phase 11 3-B) */
    DELEGATION_ALREADY_EXISTS("CIRCULATION_018", "この文書には既に委任が登録されています", Severity.WARN),

    /** ADMIN 権限が必要 (Phase 11 3-B) */
    ADMIN_REQUIRED("CIRCULATION_019", "この操作には管理者権限が必要です", Severity.WARN),

    /** 添付削除は DRAFT のみ可能 (Phase 11 3-B) */
    ATTACHMENT_NOT_DELETABLE("CIRCULATION_020", "添付ファイルの削除は下書き状態のみ可能です", Severity.WARN),

    /** PDF エクスポートは COMPLETED 文書のみ対応 (Phase 11 4-C) */
    EXPORT_NOT_AVAILABLE_NON_COMPLETED("CIRCULATION_021",
            "押印済み証跡 PDF のエクスポートは完了済みの回覧文書のみ対応しています", Severity.WARN),

    /** エクスポートがまだリクエストされていない (Phase 11 4-C) */
    EXPORT_NOT_REQUESTED("CIRCULATION_022",
            "エクスポートはリクエストされていません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
