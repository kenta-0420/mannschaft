package com.mannschaft.app.bulletin;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F05.1 掲示板のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum BulletinErrorCode implements ErrorCode {

    /** カテゴリが見つからない */
    CATEGORY_NOT_FOUND("BULLETIN_001", "カテゴリが見つかりません", Severity.WARN),

    /** スレッドが見つからない */
    THREAD_NOT_FOUND("BULLETIN_002", "スレッドが見つかりません", Severity.WARN),

    /** 返信が見つからない */
    REPLY_NOT_FOUND("BULLETIN_003", "返信が見つかりません", Severity.WARN),

    /** スレッドがロックされている */
    THREAD_LOCKED("BULLETIN_004", "このスレッドはロックされています", Severity.WARN),

    /** スレッドがアーカイブされている */
    THREAD_ARCHIVED("BULLETIN_005", "このスレッドはアーカイブされています", Severity.WARN),

    /** 投稿権限不足 */
    INSUFFICIENT_POST_ROLE("BULLETIN_006", "この操作に必要な権限がありません", Severity.WARN),

    /** 添付ファイルが見つからない */
    ATTACHMENT_NOT_FOUND("BULLETIN_007", "添付ファイルが見つかりません", Severity.WARN),

    /** リアクションが見つからない */
    REACTION_NOT_FOUND("BULLETIN_008", "リアクションが見つかりません", Severity.WARN),

    /** リアクション重複 */
    DUPLICATE_REACTION("BULLETIN_009", "既に同じリアクションが存在します", Severity.WARN),

    /** カテゴリ名重複 */
    DUPLICATE_CATEGORY_NAME("BULLETIN_010", "同じスコープ内に同名のカテゴリが存在します", Severity.WARN),

    /** 自身の投稿でない */
    NOT_AUTHOR("BULLETIN_011", "自分の投稿のみ編集できます", Severity.WARN),

    /** 親返信が異なるスレッドに属している */
    PARENT_REPLY_MISMATCH("BULLETIN_012", "親返信が異なるスレッドに属しています", Severity.WARN),

    /** 許可されていない絵文字（プリセット以外のリアクション） */
    INVALID_EMOJI("BULLETIN_013", "許可されていない絵文字です", Severity.WARN),

    /** 安否確認スレッドは手動削除できない（設計書 §6） */
    SAFETY_THREAD_DELETE_FORBIDDEN("BULLETIN_014", "安否確認スレッドは削除できません", Severity.WARN),

    /** 返信のネスト深さが上限（5階層）を超過した（設計書 §5） */
    REPLY_DEPTH_EXCEEDED("BULLETIN_015", "返信のネストは最大5階層までです", Severity.WARN),

    /** 保管庫フォルダが見つからない（設計書 §4） */
    ARCHIVE_FOLDER_NOT_FOUND("BULLETIN_016", "保管庫フォルダが見つかりません", Severity.WARN),

    /** 保管庫フォルダのネスト深さが上限（5階層）を超過した（設計書 §5） */
    ARCHIVE_FOLDER_DEPTH_EXCEEDED("BULLETIN_017", "保管庫フォルダのネストは最大5階層までです", Severity.WARN),

    /** 保管庫フォルダの循環参照（自分自身・子孫への移動）（設計書 §5） */
    ARCHIVE_FOLDER_CYCLE("BULLETIN_018", "自分自身または子孫フォルダへは移動できません", Severity.WARN),

    /** 保管庫フォルダ数が上限（200）に達した（設計書 §5） */
    ARCHIVE_FOLDER_LIMIT_EXCEEDED("BULLETIN_019", "保管庫フォルダ数が上限に達しています", Severity.WARN),

    /**
     * 保管庫フォルダ・スレッドの scope が一致しない（scope 越境）（設計書 §5/§6）。
     *
     * <p><b>越境の存在秘匿のため 404 固定</b>。不在（{@link #ARCHIVE_FOLDER_NOT_FOUND}）と
     * 同じステータスに揃えないと、応答差から他テナントのフォルダ UUID の実在が判別できる
     * （存在オラクル）。PARKING_020 起点の「越境は存在秘匿で 404」の流儀に従う。</p>
     */
    ARCHIVE_FOLDER_SCOPE_MISMATCH("BULLETIN_020", "保管庫フォルダのスコープが一致しません", Severity.WARN),

    /** 未アーカイブのスレッドはフォルダ振り分けできない（設計書 §4 PATCH .../folder） */
    THREAD_NOT_ARCHIVED("BULLETIN_021", "アーカイブされていないスレッドはフォルダへ振り分けできません", Severity.WARN),

    /** 添付ファイル数が上限（1ターゲット 5 件）に達した */
    ATTACHMENT_LIMIT_EXCEEDED("BULLETIN_022", "添付ファイルは1件あたり最大5個までです", Severity.WARN),

    /** 添付ファイルのサイズが上限（10MB）を超過した */
    ATTACHMENT_SIZE_EXCEEDED("BULLETIN_023", "添付ファイルのサイズが上限を超えています", Severity.WARN),

    /** 添付ファイルの MIME タイプがホワイトリスト外 */
    ATTACHMENT_INVALID_CONTENT_TYPE("BULLETIN_024", "許可されていないファイル形式です", Severity.WARN),

    /** 添付対象（スレッド/返信）が見つからない */
    ATTACHMENT_TARGET_NOT_FOUND("BULLETIN_025", "添付対象が見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
