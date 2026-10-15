package com.mannschaft.app.residencestatus.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
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

import java.time.LocalDateTime;

/**
 * F09.16 管理組合横展開の安否確認ラッパ。
 *
 * <p>理事長が F03.6 安否確認を組織全体に発動した際のメタ情報。
 * 本テーブルは F03.6 safety_checks のセッションを「組織横展開発動」として識別するためのラッパで、
 * 実際の安否確認ロジック・回答は F03.6 側に残る。</p>
 *
 * <p>{@code safety_check_id} は F03.6 への弱参照（FK なし・CLAUDE.md DB設計原則 1 準拠）。</p>
 */
@Entity
@Table(name = "org_wide_safety_checks")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class OrgWideSafetyCheck extends UuidV7Entity {

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** F03.6 safety_checks.id（クロスドメイン弱参照・FK なし） */
    @Column(name = "safety_check_id", nullable = false)
    private Long safetyCheckId;

    /** 発動者（理事長）user_id（クロスドメイン弱参照・FK なし） */
    @Column(name = "triggered_by", nullable = false)
    private Long triggeredBy;

    @Column(name = "triggered_at", nullable = false)
    private LocalDateTime triggeredAt;

    /** 発動理由（地震・火災・組合判断等） */
    @Column(name = "trigger_reason", length = 200)
    private String triggerReason;

    /** F03.6 セッションのクローズに同期 */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

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

    /** F03.6 セッションのクローズと同期。 */
    public void close() {
        this.closedAt = LocalDateTime.now();
    }
}
