package com.mannschaft.app.succession.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 入居時誓約レスポンス DTO（F09.15 §6）。
 *
 * <p>機密性の観点から PDF バイト本体は返さず、S3 キーと SHA-256・内部署名トークンを返す。
 * 実際の PDF ダウンロードは別エンドポイント（pre-signed URL 経由）で行う。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SuccessionCovenantResponse {

    /** UUIDv7 主キー（{@code succession_covenants.id}）。 */
    private UUID id;

    private Long organizationId;

    private Long dwellingUnitId;

    private Long residentRegistryId;

    private Long signerUserId;

    /** SUCCESSION_PRE_REGISTRATION / PRIVACY_CONSENT / MONITORING_CONSENT。 */
    private String covenantType;

    private String covenantVersion;

    /** PDF の保存先 S3 キー。 */
    private String pdfS3Key;

    /** PDF の SHA-256（hex 小文字 64 桁）。改ざん検知用。 */
    private String pdfSha256;

    /** 内部署名トークン（Base64URL 形式の HMAC-SHA256 + epochMs）。 */
    private String internalSignatureToken;

    private LocalDateTime signedAt;

    /** 撤回時刻（撤回されていない場合は {@code null}）。 */
    private LocalDateTime revokedAt;

    private LocalDateTime createdAt;
}
