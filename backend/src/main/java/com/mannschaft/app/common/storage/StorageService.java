package com.mannschaft.app.common.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * S3互換オブジェクトストレージの操作インターフェース。
 * Pre-signed URL生成、アップロード、ダウンロード、削除を提供する。
 */
public interface StorageService {

    /**
     * クライアントサイドアップロード用の Pre-signed PUT URL を生成する。
     *
     * @param s3Key       オブジェクトキー
     * @param contentType Content-Type
     * @param ttl         有効期限
     * @return 署名付きURLとメタ情報
     */
    PresignedUploadResult generateUploadUrl(String s3Key, String contentType, Duration ttl);

    /**
     * ダウンロード用の Pre-signed GET URL を生成する。
     *
     * @param s3Key オブジェクトキー
     * @param ttl   有効期限
     * @return 署名付きダウンロードURL
     */
    String generateDownloadUrl(String s3Key, Duration ttl);

    /**
     * サーバーサイドアップロード（byte配列）。
     *
     * @param s3Key       オブジェクトキー
     * @param data        ファイルデータ
     * @param contentType Content-Type
     */
    void upload(String s3Key, byte[] data, String contentType);

    /**
     * サーバーサイドアップロード（InputStream）。
     *
     * @param s3Key         オブジェクトキー
     * @param data          入力ストリーム
     * @param contentLength データサイズ（バイト）
     * @param contentType   Content-Type
     */
    void upload(String s3Key, InputStream data, long contentLength, String contentType);

    /**
     * S3オブジェクトを削除する。
     *
     * @param s3Key オブジェクトキー
     */
    void delete(String s3Key);

    /**
     * 複数のS3オブジェクトを一括削除する。
     *
     * @param s3Keys オブジェクトキーのリスト
     */
    void deleteAll(List<String> s3Keys);

    /**
     * S3オブジェクトをbyte配列としてダウンロードする。
     *
     * @param s3Key オブジェクトキー
     * @return ファイルデータ
     */
    byte[] download(String s3Key);
}
