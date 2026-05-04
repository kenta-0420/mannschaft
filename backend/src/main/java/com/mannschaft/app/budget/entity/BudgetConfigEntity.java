package com.mannschaft.app.budget.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 予算設定エンティティ。
 */
@Entity
@Table(name = "budget_configs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BudgetConfigEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(precision = 12, scale = 0)
    private BigDecimal approvalThreshold;

    private Long workflowTemplateId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean autoRecordPayments = true;

    private Long defaultIncomeCategoryId;

    @Column(nullable = false)
    @Builder.Default
    private Short budgetWarningThreshold = 80;

    @Column(nullable = false)
    @Builder.Default
    private Short budgetCriticalThreshold = 95;

    /**
     * F08.7 (Phase 9-δ) 予算超過時に起動するワークフローテンプレート ID。
     * <p>NULL の場合は警告通知のみ送信し、承認ワークフローはスキップする
     * （監査ログに {@code WORKFLOW_NOT_CONFIGURED} 記録）。</p>
     * <p>FK → {@code workflow_templates(id)} ON DELETE SET NULL（V11.034 で追加）</p>
     */
    private Long overLimitWorkflowId;

    /**
     * F08.7 (Phase 9-δ) シフト予算機能のフィーチャーフラグ（三値論理）。
     * <ul>
     *   <li>{@code null}: 組織未設定 → グローバル既定値 {@code feature.shiftBudget.enabled} を継承</li>
     *   <li>{@code Boolean.FALSE}: 明示的にオプトアウト</li>
     *   <li>{@code Boolean.TRUE}: 明示的に有効化</li>
     * </ul>
     * <p>設計書 F08.7 §13 判定ロジックを参照。</p>
     */
    private Boolean shiftBudgetEnabled;

    @Version
    private Long version;

    /**
     * 予算設定を更新する。
     * <p>F08.7 で追加された {@code overLimitWorkflowId} / {@code shiftBudgetEnabled} は本メソッドでは
     * 触らない（既存 F08.6 の更新 API は対象外）。F08.7 専用の更新 API はフェーズ別に追加する。</p>
     */
    public void update(BigDecimal approvalThreshold, Long workflowTemplateId,
                       Boolean autoRecordPayments, Long defaultIncomeCategoryId,
                       Short budgetWarningThreshold, Short budgetCriticalThreshold) {
        this.approvalThreshold = approvalThreshold;
        this.workflowTemplateId = workflowTemplateId;
        this.autoRecordPayments = autoRecordPayments;
        this.defaultIncomeCategoryId = defaultIncomeCategoryId;
        this.budgetWarningThreshold = budgetWarningThreshold;
        this.budgetCriticalThreshold = budgetCriticalThreshold;
    }
}
