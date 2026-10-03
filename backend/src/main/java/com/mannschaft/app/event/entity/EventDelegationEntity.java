package com.mannschaft.app.event.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.event.EventDelegationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * イベント代理出席委任状（F03.10）。
 *
 * <p>{@link com.mannschaft.app.schedule.entity.ScheduleDelegationEntity} と同型 + F08.3 投票代理との
 * 任意連携カラム（{@link #proxyVoteSessionId} / {@link #proxyDelegationId}）を持つ。
 * これらはクロスドメイン参照（proxyvote ドメイン）のため ID のみ保持し FK は持たない（原則1）。</p>
 *
 * <p>UUIDv7 主キー（原則6）。{@code organizationId} / {@code teamId} は親イベントのスコープから非正規化（原則7）。
 * アクティブ委任の一意性は DDL の生成カラム {@code active_delegator_marker} + UNIQUE で DB レベル保証する。</p>
 */
@Entity
@Table(name = "event_delegations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class EventDelegationEntity extends UuidV7Entity {

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    /** 委任者 user_id（クロスドメイン参照・FK なし）。 */
    @Column(name = "delegator_id", nullable = false)
    private Long delegatorId;

    /** 代理人 user_id（クロスドメイン参照・FK なし）。 */
    @Column(name = "delegate_id", nullable = false)
    private Long delegateId;

    /** 親イベントから非正規化した組織 ID（組織スコープ時。team_id と XOR）。 */
    @Column(name = "organization_id")
    private Long organizationId;

    /** 親イベントから非正規化したチーム ID（チームスコープ時。organization_id と XOR）。 */
    @Column(name = "team_id")
    private Long teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private EventDelegationStatus status = EventDelegationStatus.PENDING;

    /** 委任理由（任意）。機微情報を含みうるため一覧表示は ADMIN のみ（§6）。 */
    @Column(name = "reason", length = 500)
    private String reason;

    /** F08.3 任意連携: 投票セッション ID（クロスドメイン参照・FK なし）。 */
    @Column(name = "proxy_vote_session_id")
    private Long proxyVoteSessionId;

    /** F08.3 任意連携: 作成された proxy_delegations.id（連携作成後に設定。クロスドメイン参照・FK なし）。 */
    @Column(name = "proxy_delegation_id")
    private Long proxyDelegationId;

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
        this.status = EventDelegationStatus.ACCEPTED;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * 代理を拒否する（PENDING → REJECTED）。
     */
    public void reject() {
        this.status = EventDelegationStatus.REJECTED;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * 代理を取り消す（→ CANCELLED）。委任者またはシステムが実行する。
     */
    public void cancel() {
        this.status = EventDelegationStatus.CANCELLED;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * F08.3 連携で作成した proxy_delegations.id を設定する。
     *
     * @param proxyDelegationId 作成された proxy_delegations.id
     */
    public void linkProxyDelegation(Long proxyDelegationId) {
        this.proxyDelegationId = proxyDelegationId;
    }

    /**
     * アクティブ（PENDING または ACCEPTED）な委任かどうかを判定する。
     *
     * @return アクティブな場合 true
     */
    public boolean isActive() {
        return this.status == EventDelegationStatus.PENDING
                || this.status == EventDelegationStatus.ACCEPTED;
    }
}
