package com.mannschaft.app.repairplan.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 申し送りパック（F08.8 Phase 1・案7）。
 *
 * <p>任期終了時の申し送り PDF メタデータ。
 * 同一スコープ × {@code term_year} で複数バージョン許容（GDPR 再生成時など）。</p>
 */
@Entity
@Table(name = "board_handover_packs")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class BoardHandoverPack extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "term_year", nullable = false)
    private Integer termYear;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "pdf_r2_key", length = 500)
    private String pdfR2Key;

    @Column(name = "pdf_size")
    private Long pdfSize;

    @Column(name = "pdf_sha256", length = 64)
    private String pdfSha256;

    @Column(name = "pii_level", nullable = false, length = 20)
    private String piiLevel;

    @Column(name = "viewer_watermark_template", length = 500)
    private String viewerWatermarkTemplate;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "password_separately_sent", nullable = false)
    private Boolean passwordSeparatelySent;

    @Column(name = "generated_by", nullable = false)
    private Long generatedBy;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.passwordSeparatelySent == null) {
            this.passwordSeparatelySent = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
