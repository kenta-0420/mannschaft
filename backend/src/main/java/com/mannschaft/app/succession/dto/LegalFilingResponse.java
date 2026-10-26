package com.mannschaft.app.succession.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mannschaft.app.succession.entity.LegalFilingEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 法的手続きレスポンス DTO（F09.15 S6-B）。
 *
 * <p>申立書テンプレート PDF と区分所有法 8 条 証拠 ZIP の S3 キーおよび
 * 改ざん検知用 SHA-256 ハッシュを含む。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LegalFilingResponse {

    /** 法的手続きレコード ID（UUIDv7）。 */
    private UUID id;

    /** テナント組織 ID。 */
    private Long organizationId;

    /** 居室 ID。 */
    private Long dwellingUnitId;

    /** 居住者台帳 ID。 */
    private Long residentRegistryId;

    /** 申立種別（ABSENTEE_PROPERTY_MANAGER / INHERITANCE_LIQUIDATOR）。 */
    private String filingType;

    /** 申立書テンプレート PDF の S3 キー。 */
    private String templatePdfS3Key;

    /** 区分所有法 8 条 証拠 ZIP の S3 キー（未生成の場合 null）。 */
    private String evidencePackageS3Key;

    /** 証拠 ZIP 生成日時（未生成の場合 null）。 */
    private LocalDateTime evidenceBuiltAt;

    /** 証拠 ZIP の SHA-256 ハッシュ（未生成の場合 null）。 */
    private String evidenceSha256;

    /** 外部（家庭裁判所等）への提出日時（未提出の場合 null）。 */
    private LocalDateTime filedExternallyAt;

    /** 外部受理番号（未受理の場合 null）。 */
    private String externalCaseNumber;

    /** 備考。 */
    private String note;

    /** レコード作成日時。 */
    private LocalDateTime createdAt;

    /** レコード更新日時。 */
    private LocalDateTime updatedAt;

    /**
     * {@link LegalFilingEntity} から DTO を生成する static ファクトリメソッド。
     *
     * @param entity 法的手続きエンティティ
     * @return DTO インスタンス
     */
    public static LegalFilingResponse fromEntity(LegalFilingEntity entity) {
        return LegalFilingResponse.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .dwellingUnitId(entity.getDwellingUnitId())
                .residentRegistryId(entity.getResidentRegistryId())
                .filingType(entity.getFilingType())
                .templatePdfS3Key(entity.getTemplatePdfS3Key())
                .evidencePackageS3Key(entity.getEvidencePackageS3Key())
                .evidenceBuiltAt(entity.getEvidenceBuiltAt())
                .evidenceSha256(entity.getEvidenceSha256())
                .filedExternallyAt(entity.getFiledExternallyAt())
                .externalCaseNumber(entity.getExternalCaseNumber())
                .note(entity.getNote())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
