package com.mannschaft.app.common.storage;

import java.util.Set;

/**
 * ファイル種別検証ユーティリティ。
 *
 * <p>Presigned URL 方式のため magic byte 検査は実施しない。
 * Content-Type ヘッダーによるホワイトリスト方式で検証する。
 * 全アップロードエンドポイントで本クラスを使用し、検証ロジックを一元管理する。</p>
 *
 * <p>真の magic byte 検査が必要な場合は Cloudflare Worker によるアップロード後検証を検討すること
 * （07_file_and_storage_security.md §4 参照）。</p>
 */
public final class FileTypeValidator {

    private FileTypeValidator() {}

    /**
     * 全エンドポイントで禁止する MIME タイプ（セキュリティリスク）。
     * Content-Type ヘッダーでこれらを申告したリクエストは即時拒否する。
     */
    public static final Set<String> BLOCKED_CONTENT_TYPES = Set.of(
            "image/svg+xml",           // XSS: JavaScript を埋め込み可能
            "text/html",               // XSS
            "application/xhtml+xml",   // XSS
            "application/javascript",  // コード実行
            "text/javascript",         // コード実行
            "application/x-php",       // WebShell
            "application/x-sh",        // シェルスクリプト
            "application/x-python",    // スクリプト
            "application/xml"          // XXE インジェクション
    );

    /** 画像系の許可 MIME タイプ */
    public static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/avif"
    );

    /** 動画系の許可 MIME タイプ */
    public static final Set<String> ALLOWED_VIDEO_TYPES = Set.of(
            "video/mp4", "video/webm", "video/quicktime"
    );

    /** 文書系の許可 MIME タイプ（掲示板・回覧板用） */
    public static final Set<String> ALLOWED_DOCUMENT_TYPES = Set.of(
            "application/pdf",
            "text/plain",
            "text/csv",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    /** アーカイブ系（マルチパートアップロード用） */
    public static final Set<String> ALLOWED_ARCHIVE_TYPES = Set.of(
            "application/zip", "application/x-tar", "application/gzip",
            "application/octet-stream"
    );

    /**
     * 指定した MIME タイプが明示的に禁止されているか確認する。
     *
     * @param contentType 検証する Content-Type（null/blank は false を返す）
     * @return 禁止されている場合 true
     */
    public static boolean isBlocked(String contentType) {
        if (contentType == null || contentType.isBlank()) return false;
        // パラメータ部分（"; charset=utf-8" 等）を除去して比較
        String baseType = contentType.split(";")[0].trim().toLowerCase();
        return BLOCKED_CONTENT_TYPES.contains(baseType);
    }

    /**
     * 指定した MIME タイプが許可リストに含まれるか確認する。
     *
     * @param contentType  検証する Content-Type（null/blank は false を返す）
     * @param allowedTypes 許可リスト
     * @return 許可されている場合 true
     */
    public static boolean isAllowed(String contentType, Set<String> allowedTypes) {
        if (contentType == null || contentType.isBlank()) return false;
        // パラメータ部分（"; charset=utf-8" 等）を除去して比較
        String baseType = contentType.split(";")[0].trim().toLowerCase();
        return allowedTypes.contains(baseType);
    }
}
