package com.mannschaft.app.succession.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 証拠 ZIP ダウンロード URL レスポンス DTO（F09.15 S6-B）。
 *
 * <p>区分所有法 8 条 証拠パッケージの S3 Pre-signed URL を有効期間付きで返す。
 * デフォルトの有効期間は 1 時間（3600 秒）。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EvidenceDownloadUrlResponse {

    /** S3 Pre-signed ダウンロード URL。 */
    private String downloadUrl;

    /** URL の有効期間（秒）。 */
    private Integer ttlSeconds;
}
