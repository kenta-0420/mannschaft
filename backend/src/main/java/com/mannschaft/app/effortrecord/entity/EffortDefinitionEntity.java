package com.mannschaft.app.effortrecord.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.effortrecord.enums.EffortSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/**
 * F06.4.1 頑張り記録の定義（標準プリセットまたはチーム定義の不変 snapshot 識別用）。
 *
 * <p>PRESET は共通参照データで固定 UUID。TEAM は team 側（team_id 基準。親組織 0 件・複数件のチームがあり得るため
 * organization_id では束ねない。2026-11-18 マスター裁定）。個人所有ではない。
 * 時刻は UTC の {@link Instant}（データ設計 §2）。</p>
 */
@Entity
@Table(name = "effort_definitions",
        uniqueConstraints = @UniqueConstraint(name = "uq_effort_definition_source", columnNames = {"source_type", "source_key"}))
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class EffortDefinitionEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 10)
    private EffortSourceType sourceType;

    /** PRESET なら固定コード、TEAM なら {@code team:{teamId}:template:{templateId}}。 */
    @Column(name = "source_key", nullable = false, length = 80)
    private String sourceKey;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "team_template_id")
    private Long teamTemplateId;

    @Column(name = "current_revision", nullable = false)
    private Integer currentRevision;

    @Column(name = "retired_at")
    private Instant retiredAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
