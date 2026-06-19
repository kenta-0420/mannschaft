package com.mannschaft.app.repairplan.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

// TODO: EXTERNAL_AGENT_DELEGATION_GRANTED/REVOKED 監査ログ — 委任サービス実装時に追加すること
/**
 * 外部エージェント委任（F08.8 Phase 1）。
 *
 * <p>管理会社（外部エージェント）への機能別委任。
 * 区分所有法上の管理会社業務委託契約を電子化する。
 * {@code agent_user_id} / {@code granted_by} / {@code revoked_by} は users.id への
 * ID 参照（FK なし）。</p>
 */
@Entity
@Table(name = "external_agent_delegations")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ExternalAgentDelegation extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /** users.id（管理会社担当者・クロスドメイン参照・FK なし） */
    @Column(name = "agent_user_id", nullable = false)
    private Long agentUserId;

    @Column(name = "agent_company_name", nullable = false, length = 200)
    private String agentCompanyName;

    @Column(name = "delegation_type", nullable = false, length = 40)
    private String delegationType;

    /** users.id（理事長・クロスドメイン参照・FK なし） */
    @Column(name = "granted_by", nullable = false)
    private Long grantedBy;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    /** users.id（クロスドメイン参照・FK なし） */
    @Column(name = "revoked_by")
    private Long revokedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

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
        if (this.grantedAt == null) {
            this.grantedAt = now;
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
