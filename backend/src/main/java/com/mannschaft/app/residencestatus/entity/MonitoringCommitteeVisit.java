package com.mannschaft.app.residencestatus.entity;

import com.mannschaft.app.common.EncryptedStringConverter;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F09.16 見守り委員による訪問記録。
 *
 * <p>F04.10 committee_members.role=WATCHER の委員が訪問結果を入力する。
 * MONITORING_CONSENT 同意者のみが対象（{@code consent_covenant_id} で F09.15 succession_covenants と紐付け）。</p>
 *
 * <p>{@code considerationMemoEncrypted} は {@link EncryptedStringConverter} により AES-256-GCM で透過暗号化される。</p>
 *
 * <p>すべての他ドメイン参照は INDEX のみで FK なし（CLAUDE.md DB設計原則 1 準拠）。</p>
 */
@Entity
@Table(name = "monitoring_committee_visits")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class MonitoringCommitteeVisit extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** F09.1 dwelling_units.id（クロスドメイン弱参照・FK なし） */
    @Column(name = "dwelling_unit_id", nullable = false)
    private Long dwellingUnitId;

    /** F09.1 resident_registry.id（クロスドメイン弱参照・FK なし） */
    @Column(name = "resident_registry_id", nullable = false)
    private Long residentRegistryId;

    /** 訪問対象ユーザー（クロスドメイン弱参照・FK なし） */
    @Column(name = "subject_user_id", nullable = false)
    private Long subjectUserId;

    /** F04.10 committees.id（クロスドメイン弱参照・FK なし） */
    @Column(name = "committee_id", nullable = false)
    private Long committeeId;

    /** 訪問者（WATCHER）user_id（クロスドメイン弱参照・FK なし） */
    @Column(name = "visitor_user_id", nullable = false)
    private Long visitorUserId;

    @Column(name = "visited_at", nullable = false)
    private LocalDateTime visitedAt;

    /** 訪問結果 enum: MET / NO_RESPONSE / MAILBOX_ABNORMAL / METER_ABNORMAL / NEIGHBOR_INFO / REFUSED / OTHER */
    @Column(name = "contact_result", nullable = false, length = 20)
    private String contactResult;

    /** 配慮事項メモ（AES-256-GCM 暗号化）。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "consideration_memo_encrypted", columnDefinition = "TEXT")
    private String considerationMemoEncrypted;

    @Column(name = "next_visit_recommended_at")
    private LocalDate nextVisitRecommendedAt;

    /** F09.15 succession_covenants.id（MONITORING_CONSENT 誓約・クロスドメイン弱参照・FK なし） */
    @Column(name = "consent_covenant_id")
    private UUID consentCovenantId;

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
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
