package com.mannschaft.app.shiftbudget.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * F08.7 シフト予算 閾値超過警告エンティティ。
 *
 * <p>1 レコード = (allocation, 閾値) ペアの「警告発火履歴」。
 * 同一割当で複数閾値（80% / 100% / 120%）に達した場合は閾値ごとに 1 件記録される。
 * UNIQUE (allocation_id, threshold_percent) により同じ閾値での重複検知を防ぐ。</p>
 *
 * <p>確認応答（acknowledge）は {@link #acknowledgedAt} / {@link #acknowledgedBy} の更新で表現する。
 * 物理削除は禁止（監査履歴保持）。</p>
 *
 * <p>設計書 F08.7 (v1.2) §5.5 / §6.2.5 に準拠。</p>
 */
@Entity
@Table(
        name = "budget_threshold_alerts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_bta_allocation_threshold",
                        columnNames = {"allocation_id", "threshold_percent"}
                )
        },
        indexes = {
                @Index(name = "idx_bta_triggered", columnList = "triggered_at"),
                @Index(name = "idx_bta_unack", columnList = "acknowledged_at, allocation_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BudgetThresholdAlertEntity extends BaseEntity {

    /** FK → shift_budget_allocations。ON DELETE CASCADE */
    @Column(name = "allocation_id", nullable = false)
    private Long allocationId;

    /** 閾値 (80 / 100 / 120 の 3 値のみ。CHECK chk_bta_threshold で強制) */
    @Column(name = "threshold_percent", nullable = false)
    private Integer thresholdPercent;

    /** 検知時刻 */
    @Column(name = "triggered_at", nullable = false)
    private LocalDateTime triggeredAt;

    /** 検知時点の消化額（事後分析用、計算結果のスナップショット） */
    @Column(name = "consumed_amount_at_trigger", nullable = false, precision = 12, scale = 0)
    private BigDecimal consumedAmountAtTrigger;

    /**
     * 通知先ユーザー ID 配列（JSON 文字列）。
     * <p>BUDGET_ADMIN 保有者など、警告発火時点で通知対象となったユーザーのスナップショットを保持する。
     * 後から権限剥奪されたユーザーが含まれていても監査履歴として残す。</p>
     * <p>プロジェクト慣例（{@code AuditLogEntity#metadata} 等）に従い JSON カラムは生 String で保持し、
     * Service 層で Jackson によりシリアライズ／デシリアライズする。</p>
     */
    @Column(name = "notified_user_ids", nullable = false, columnDefinition = "JSON")
    private String notifiedUserIds;

    /**
     * F05.6 起動時の workflow_requests.id（100% 到達時のみセット）。
     * <p>設計書 §8.4: 組織が {@code budget_configs.over_limit_workflow_id} を未設定の場合は NULL。</p>
     */
    @Column(name = "workflow_request_id")
    private Long workflowRequestId;

    /** 予算管理者が確認応答した時刻。NULL = 未承認 */
    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    /** FK → users。ON DELETE SET NULL */
    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;

    /**
     * 承認応答を記録する。
     * <p>設計書 §6.2.5: BUDGET_ADMIN 保有者のみ呼出可。多重 acknowledge は冪等扱い（最後の応答で上書き）。</p>
     *
     * @param userId 応答ユーザー ID
     */
    public void acknowledge(Long userId) {
        this.acknowledgedAt = LocalDateTime.now();
        this.acknowledgedBy = userId;
    }

    /**
     * ワークフロー起動結果を記録する。
     * <p>F05.6 連携で 100% 到達時に workflow_requests.id をセットする。</p>
     */
    public void linkWorkflowRequest(Long workflowRequestId) {
        this.workflowRequestId = workflowRequestId;
    }
}
