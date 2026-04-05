package com.mannschaft.app.chart;

import java.time.LocalDateTime;

/**
 * カルテ写真のCloudFront署名付きURL生成の抽象化インターフェース。
 * 本番環境ではCloudFront署名付きURLを生成し、開発環境ではプレースホルダーURLを返す。
 */
public interface ChartPhotoUrlProvider {

    /**
     * S3キーからCloudFront署名付きURLを生成する。
     *
     * @param s3Key S3オブジェクトキー
     * @return 署名付きURL
     */
    String generateSignedUrl(String s3Key);

    /**
     * 署名付きURLの有効期限を取得する。
     *
     * @return 有効期限（15分後）
     */
    LocalDateTime getExpiresAt();
}
