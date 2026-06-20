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
 * 入居時誓約エンティティ（F09.15 S1）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.3
 *
 * <p>区分所有者が入居時に同意する 3 種誓約（SUCCESSION_PRE_REGISTRATION /
 * PRIVACY_CONSENT / MONITORING_CONSENT）の同意 PDF を保存する。
 * 改ざん検知のため PDF の SHA-256 ハッシュと内部署名トークンを併せて保持する。
 *
 * <p>クロスドメイン参照: {@code dwelling_unit_id} / {@code resident_registry_id} /
 * {@code signer_user_id} は FK なし・INDEX のみ。
 */
@Entity
@Table(name = "succession_covenants")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class SuccessionCovenantEntity extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "dwelling_unit_id", nullable = false)
    private Long dwellingUnitId;

    @Column(name = "resident_registry_id", nullable = false)
    private Long residentRegistryId;

    @Column(name = "signer_user_id", nullable = false)
    private Long signerUserId;

    /** SUCCESSION_PRE_REGISTRATION / PRIVACY_CONSENT / MONITORING_CONSENT */
    @Column(name = "covenant_type", nullable = false, length = 40)
    private String covenantType;

    @Column(name = "covenant_version", nullable = false, length = 20)
    private String covenantVersion;

    @Column(name = "pdf_s3_key", nullable = false, length = 500)
    private String pdfS3Key;

    @Column(name = "pdf_sha256", nullable = false, length = 64)
    private String pdfSha256;

    @Column(name = "internal_signature_token", nullable = false, length = 500)
    private String internalSignatureToken;

    @Column(name = "signed_at", nullable = false)
    private LocalDateTime signedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

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

    /** 誓約を撤回する（本人のみ）。 */
    public void revoke() {
        this.revokedAt = LocalDateTime.now();
    }

    /** 論理削除を行う。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
