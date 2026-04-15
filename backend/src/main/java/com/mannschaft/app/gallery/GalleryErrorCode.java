package com.mannschaft.app.gallery;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F06.2 ギャラリーのエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum GalleryErrorCode implements ErrorCode {

    /** アルバムが見つからない */
    ALBUM_NOT_FOUND("GALLERY_001", "アルバムが見つかりません", Severity.WARN),

    /** 写真が見つからない */
    PHOTO_NOT_FOUND("GALLERY_002", "写真が見つかりません", Severity.WARN),

    /** 権限不足 */
    INSUFFICIENT_PERMISSION("GALLERY_003", "この操作に必要な権限がありません", Severity.WARN),

    /** ダウンロード不許可 */
    DOWNLOAD_NOT_ALLOWED("GALLERY_004", "このアルバムのダウンロードは許可されていません", Severity.WARN),

    /** ファイルサイズ超過 */
    FILE_SIZE_EXCEEDED("GALLERY_005", "ファイルサイズが上限（20MB）を超えています", Severity.WARN),

    /** 非対応形式 */
    UNSUPPORTED_FORMAT("GALLERY_006", "対応していないファイル形式です", Severity.WARN),

    /** アップロード不許可 */
    UPLOAD_NOT_ALLOWED("GALLERY_007", "このアルバムへの写真アップロードは許可されていません", Severity.WARN),

    /** 写真枚数上限超過 */
    PHOTO_LIMIT_EXCEEDED("GALLERY_008", "写真枚数の上限（5,000枚）を超えています", Severity.WARN),

    /** ストレージ容量上限超過 */
    STORAGE_LIMIT_EXCEEDED("GALLERY_009", "ストレージ容量の上限（10GB）を超えています", Severity.WARN),

    /** 一括アップロード枚数上限超過 */
    BATCH_UPLOAD_LIMIT_EXCEEDED("GALLERY_010", "1リクエストあたり最大20枚です", Severity.WARN),

    /** ギャラリーモジュール未有効化 */
    MODULE_NOT_ENABLED("GALLERY_011", "ギャラリーモジュールが有効化されていません", Severity.WARN),

    /** 非対応コンテンツタイプ */
    UNSUPPORTED_CONTENT_TYPE("GALLERY_012", "対応していないコンテンツタイプです", Severity.WARN),

    /** ストレージにオブジェクトが存在しない */
    MEDIA_NOT_FOUND_IN_STORAGE("GALLERY_013", "指定されたメディアがストレージに存在しません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
