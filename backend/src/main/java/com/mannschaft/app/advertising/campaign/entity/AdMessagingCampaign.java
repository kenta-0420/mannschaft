package com.mannschaft.app.advertising.campaign.entity;

import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.membership.domain.ScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * F09.17 メッセージ型キャンペーン本体。
 *
 * <p>広告主が DRAFT 作成し、状態遷移で配信開始まで進める。
 * TenantAware (organization_id 保持) / 論理削除あり。
 * クロスドメイン参照は ID のみ保持し FK 制約は張らない。</p>
 */
@Entity
@Table(name = "ad_messaging_campaigns")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class AdMessagingCampaign extends UuidV7Entity {

    /** F09.11 advertiser_accounts.id (クロスドメイン参照・FK なし) */
    @Column(name = "advertiser_account_id", nullable = false)
    private Long advertiserAccountId;

    /** スコープ種別 (ORGANIZATION / TEAM)。F09.17 Phase 11-d-1 で追加。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ScopeType scopeType;

    /** スコープ ID (organization_id または team_id・クロスドメイン参照・FK なし)。F09.17 Phase 11-d-1 で追加。 */
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AdCampaignStatus status;

    @Column(name = "total_budget_yen", nullable = false)
    private Long totalBudgetYen;

    @Column(name = "consumed_budget_yen", nullable = false)
    private Long consumedBudgetYen;

    /** NULL 時はデフォルト週 3 件 */
    @Column(name = "frequency_cap_override")
    private Integer frequencyCapOverride;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    @Column(name = "scheduled_timezone", nullable = false, length = 50)
    private String scheduledTimezone;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false, length = 20)
    private AdModerationStatus moderationStatus;

    @Column(name = "blocked_reason", columnDefinition = "TEXT")
    private String blockedReason;

    /** 作成者 user_id (クロスドメイン参照・FK なし) */
    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

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
        if (this.status == null) {
            this.status = AdCampaignStatus.DRAFT;
        }
        if (this.moderationStatus == null) {
            this.moderationStatus = AdModerationStatus.PENDING;
        }
        if (this.totalBudgetYen == null) {
            this.totalBudgetYen = 0L;
        }
        if (this.consumedBudgetYen == null) {
            this.consumedBudgetYen = 0L;
        }
        if (this.scheduledTimezone == null) {
            this.scheduledTimezone = "Asia/Tokyo";
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
