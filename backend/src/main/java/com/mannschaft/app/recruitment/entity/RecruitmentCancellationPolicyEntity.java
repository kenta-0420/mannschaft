package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * F03.11 募集型予約: キャンセルポリシー本体 (Phase 5a)。
 * スナップショット方式: テンプレートからの複製時に DEEP COPY して新規レコードを生成。
 */
@Entity
@Table(name = "recruitment_cancellation_policies")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class RecruitmentCancellationPolicyEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecruitmentScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(length = 100)
    private String policyName;

    @Column(nullable = false)
    private Integer freeUntilHoursBefore;

    @Column(nullable = false)
    private Boolean isTemplatePolicy;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    /** ポリシー名・無料境界を更新する (テンプレート専用)。 */
    public void updateForTemplate(String policyName, Integer freeUntilHoursBefore) {
        if (!Boolean.TRUE.equals(this.isTemplatePolicy)) {
            throw new IllegalStateException("募集スナップショットのポリシーは更新できません");
        }
        if (policyName != null) {
            this.policyName = policyName;
        }
        if (freeUntilHoursBefore != null) {
            this.freeUntilHoursBefore = freeUntilHoursBefore;
        }
    }

    /** 論理削除を行う。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
