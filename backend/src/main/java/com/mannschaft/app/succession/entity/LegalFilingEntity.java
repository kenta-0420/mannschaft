package com.mannschaft.app.succession.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 法的手続き準備エンティティ（F09.15 S1）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.8
 *
 * <p>申立種別:
 * <ul>
 *   <li>{@code ABSENTEE_PROPERTY_MANAGER} — 不在者財産管理人選任申立（家事事件手続法 145 条）</li>
 *   <li>{@code INHERITANCE_LIQUIDATOR} — 相続財産清算人選任申立（民法 952 条）</li>
 * </ul>
 *
 * <p>区分所有法 8 条の先取特権実行に備えて
 * {@code evidence_package_s3_key} に証拠 ZIP を生成し、改ざん検知のため
 * SHA-256 ハッシュ ({@code evidence_sha256}) を保持する。
 */
@Entity
@Table(name = "legal_filings")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class LegalFilingEntity extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "dwelling_unit_id", nullable = false)
    private Long dwellingUnitId;

    @Column(name = "resident_registry_id", nullable = false)
    private Long residentRegistryId;

    /** ABSENTEE_PROPERTY_MANAGER / INHERITANCE_LIQUIDATOR */
    @Column(name = "filing_type", nullable = false, length = 40)
    private String filingType;

    @Column(name = "template_pdf_s3_key", length = 500)
    private String templatePdfS3Key;

    @Column(name = "evidence_package_s3_key", length = 500)
    private String evidencePackageS3Key;

    @Column(name = "evidence_built_at")
    private LocalDateTime evidenceBuiltAt;

    @Column(name = "evidence_sha256", length = 64)
    private String evidenceSha256;

    /** 外部（家庭裁判所等）への提出日時（手動入力）。 */
    @Column(name = "filed_externally_at")
    private LocalDateTime filedExternallyAt;

    /** 外部受理番号（手動入力）。 */
    @Column(name = "external_case_number", length = 100)
    private String externalCaseNumber;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 論理削除を行う。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
