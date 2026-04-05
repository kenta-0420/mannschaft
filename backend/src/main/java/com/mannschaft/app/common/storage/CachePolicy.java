package com.mannschaft.app.common.storage;

/**
 * S3オブジェクトのCache-Controlポリシーを判定するユーティリティ。
 * S3キーのパスパターンからCloudFrontキャッシュ戦略を自動決定する。
 */
public final class CachePolicy {

    /** アバター・ロゴ・サムネイル等の不変コンテンツ（ハッシュ/UUID付きキー前提） */
    static final String IMMUTABLE = "public, max-age=31536000, immutable";

    /** ユーザーアップロードの原寸画像・添付ファイル（1日キャッシュ） */
    static final String LONG = "public, max-age=86400";

    /** 一時ファイル・ZIPエクスポート等（キャッシュなし） */
    static final String NO_CACHE = "no-cache, no-store";

    private CachePolicy() {
    }

    /**
     * S3キーのパスパターンからCache-Control値を決定する。
     *
     * @param s3Key S3オブジェクトキー
     * @return Cache-Controlヘッダー値
     */
    public static String resolve(String s3Key) {
        if (s3Key == null) {
            return LONG;
        }

        // 一時ファイル（tmp/、export/）はキャッシュ無効
        if (s3Key.startsWith("tmp/") || s3Key.startsWith("export/")) {
            return NO_CACHE;
        }

        // サムネイル・アバター・ロゴはUUID付きで不変
        if (s3Key.contains("/thumbs/")
                || s3Key.startsWith("avatars/")
                || s3Key.startsWith("logos/")
                || s3Key.startsWith("badges/")
                || s3Key.startsWith("icons/")) {
            return IMMUTABLE;
        }

        // 写真・添付ファイル・ドキュメント等
        return LONG;
    }
}
