package com.mannschaft.app.effortrecord.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.effortrecord.enums.EffortShareState;
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
import java.time.LocalDate;
import java.util.UUID;

/**
 * F06.4.1 個人の頑張り記録の原本。
 *
 * <p>owner_user_id だけがルーティング・認可の軸（組織で束ねない）。定義 JSON を複写保持するため
 * 定義側へ JOIN せず本人閲覧・集計できる。@SQLRestriction は通常 API から tombstone（purged）を隠す。
 * F00 専用の purged 行読取は専用 native query で行う（データ設計 §7.1）。</p>
 *
 * <p>version は @Version（CAS 用）、contentRevision は内容版、shareVersion は共有許可の版で、
 * 互いに独立（データ設計 §6）。</p>
 */
@Entity
@Table(name = "effort_records",
        uniqueConstraints = @UniqueConstraint(name = "uq_effort_record_share", columnNames = {"share_id"}))
@SQLRestriction("deleted_at IS NULL AND purged_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class EffortRecordEntity extends UuidV7Entity {

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** FK なしで参照する定義 UUID。 */
    @Column(name = "definition_id", nullable = false)
    private UUID definitionId;

    @Column(name = "definition_revision", nullable = false)
    private Integer definitionRevision;

    /** 作成時に複写した DefinitionSnapshot JSON（保存後は不変）。 */
    @Column(name = "definition_snapshot", columnDefinition = "JSON", nullable = false)
    private String definitionSnapshot;

    @Column(name = "recorded_on", nullable = false)
    private LocalDate recordedOn;

    /** IANA タイムゾーン ID。作成後は変更不可。 */
    @Column(name = "record_timezone", nullable = false, length = 64)
    private String recordTimezone;

    /** fieldKey → string/null の JSON object。 */
    @Column(name = "values_json", columnDefinition = "JSON", nullable = false)
    private String valuesJson;

    @Column(name = "private_note", columnDefinition = "TEXT")
    private String privateNote;

    /** 退会 purge 後の tombstone 化時刻。非 null なら通常 API から到達不可。 */
    @Column(name = "purged_at")
    private Instant purgedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "content_revision", nullable = false)
    private Long contentRevision;

    // ---- 以下はチーム共有（PR-3 以降で利用）。PR-1 ではカラムのみ保持する ----

    @Column(name = "share_definition_snapshot", columnDefinition = "JSON")
    private String shareDefinitionSnapshot;

    @Column(name = "share_mapping_json", columnDefinition = "JSON")
    private String shareMappingJson;

    @Column(name = "share_id")
    private UUID shareId;

    @Column(name = "share_team_id")
    private Long shareTeamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "share_state", length = 12)
    private EffortShareState shareState;

    @Column(name = "share_version", nullable = false)
    private Long shareVersion;

    /** 加入期間 ID（memberships.id）。クロスドメイン FK は張らない。 */
    @Column(name = "grant_membership_id")
    private Long grantMembershipId;

    @Column(name = "selected_field_keys", columnDefinition = "JSON")
    private String selectedFieldKeys;

    @Column(name = "desired_projection", columnDefinition = "JSON")
    private String desiredProjection;

    @Column(name = "desired_hash", length = 64, columnDefinition = "CHAR(64)")
    private String desiredHash;

    @Column(name = "synced_source_version")
    private Long syncedSourceVersion;

    @Column(name = "last_error_code", length = 32)
    private String lastErrorCode;

    @Column(name = "share_updated_at")
    private Instant shareUpdatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (contentRevision == null) {
            contentRevision = 1L;
        }
        if (shareVersion == null) {
            shareVersion = 0L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        // DATETIME(6) に合わせて µs へ丸める（応答の updatedAt と再読込を一致させる）。
        updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
