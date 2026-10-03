package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.schedule.ScheduleDelegationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * スケジュール代理出席委任状（F03.10）。
 *
 * <p>出席できないメンバー（委任者）が代理人を指定してスケジュール出欠を委託する。
 * UUIDv7 主キー（CLAUDE.md 原則6）。{@code delegatorId} / {@code delegateId} はクロスドメイン参照のため
 * ID のみ保持し FK は持たない（原則1）。{@code organizationId} / {@code teamId} は親スケジュールのスコープから
 * 非正規化して保持する（XOR。原則7）。</p>
 *
 * <p>アクティブ（PENDING/ACCEPTED）委任の一意性は、DDL の生成カラム {@code active_delegator_marker} +
 * {@code UNIQUE KEY uq_active_delegation} で DB レベル保証する。本 Entity は当該生成カラムをマップしない
 * （読み取り専用の DB 計算列のため）。</p>
 */
@Entity
@Table(name = "schedule_delegations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ScheduleDelegationEntity extends UuidV7Entity {

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    /** 委任者 user_id（クロスドメイン参照・FK なし）。 */
    @Column(name = "delegator_id", nullable = false)
    private Long delegatorId;

    /** 代理人 user_id（クロスドメイン参照・FK なし）。 */
    @Column(name = "delegate_id", nullable = false)
    private Long delegateId;

    /** 親スケジュールから非正規化した組織 ID（組織スコープ時。team_id と XOR）。 */
    @Column(name = "organization_id")
    private Long organizationId;

    /** 親スケジュールから非正規化したチーム ID（チームスコープ時。organization_id と XOR）。 */
    @Column(name = "team_id")
    private Long teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ScheduleDelegationStatus status = ScheduleDelegationStatus.PENDING;

    /** 委任理由（任意）。機微情報を含みうるため一覧表示は ADMIN のみ（§6）。 */
    @Column(name = "reason", length = 500)
    private String reason;

    /** 承認/拒否/取消日時。 */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * AbstractTenantAwareRepository 互換用。運用上は常に NULL。
     * 委任ライフサイクルは {@link #status} で表現し、論理削除は使わない（設計書 §3 論理削除=なし）。
     */
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

    /**
     * 代理を承認する（PENDING → ACCEPTED）。
     */
    public void accept() {
        this.status = ScheduleDelegationStatus.ACCEPTED;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * 代理を拒否する（PENDING → REJECTED）。
     */
    public void reject() {
        this.status = ScheduleDelegationStatus.REJECTED;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * 代理を取り消す（→ CANCELLED）。委任者またはシステムが実行する。
     */
    public void cancel() {
        this.status = ScheduleDelegationStatus.CANCELLED;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * アクティブ（PENDING または ACCEPTED）な委任かどうかを判定する。
     *
     * @return アクティブな場合 true
     */
    public boolean isActive() {
        return this.status == ScheduleDelegationStatus.PENDING
                || this.status == ScheduleDelegationStatus.ACCEPTED;
    }
}
