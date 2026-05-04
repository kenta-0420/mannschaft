package com.mannschaft.app.shiftbudget.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.budget.entity.BudgetConfigEntity;
import com.mannschaft.app.budget.repository.BudgetConfigRepository;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.shiftbudget.entity.BudgetThresholdAlertEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.BudgetThresholdAlertRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F08.7 シフト予算 閾値超過警告 評価サービス（Phase 9-δ 第2段）。
 *
 * <p>設計書 F08.7 (v1.2) §3 UC-5 / §4.5 / §5.5 / §9.1 に準拠。</p>
 *
 * <p>消化記録 ({@code shift_budget_consumptions}) の INSERT/CANCEL の度に
 * {@link com.mannschaft.app.shiftbudget.listener.ShiftBudgetConsumptionRecordListener}
 * 等の hook から呼ばれ、当該割当の現在消化率を再計算して
 * 80% / 100% / 120% の閾値ごとに警告レコード ({@code budget_threshold_alerts}) を発火する。</p>
 *
 * <p>冪等性保証:</p>
 * <ul>
 *   <li>UNIQUE (allocation_id, threshold_percent) により重複発火不可（DB 層での真の防衛線）</li>
 *   <li>アプリ層でも {@link BudgetThresholdAlertRepository#findByAllocationIdAndThresholdPercent}
 *       で事前確認 → 既存ありなら skip</li>
 * </ul>
 *
 * <p>F05.6 ワークフロー連携 (100% 到達時) はマスター御裁可 Q2 により本 Phase ではスコープ外。
 * TODO コメントを残置し、監査ログ {@code WORKFLOW_NOT_CONFIGURED} を記録するのみ。</p>
 *
 * <p>本サービスは AFTER_COMMIT hook から呼ばれるが、自身も例外を握りつぶす責務は
 * 呼び出し側 (Listener) に委譲する。本サービス内の例外は呼び出し側で個別 catch される前提。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThresholdAlertEvaluationService {

    /** 評価対象の閾値（昇順）。設計書 §4.5 / §5.5 chk_bta_threshold で固定。 */
    private static final int[] THRESHOLDS = {80, 100, 120};

    /** F05.6 起動の許容上限（100% 以上のみ）。 */
    private static final int WORKFLOW_TRIGGER_THRESHOLD = 100;

    private final ShiftBudgetAllocationRepository allocationRepository;
    private final BudgetThresholdAlertRepository alertRepository;
    private final BudgetConfigRepository budgetConfigRepository;
    private final UserRoleRepository userRoleRepository;
    private final NotificationHelper notificationHelper;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * 指定 allocation について現在消化率を計算し、80/100/120% 閾値の発火判定を行う。
     *
     * <p>呼び出し側 ({@code ShiftBudgetConsumptionRecordListener}) は AFTER_COMMIT hook 内で
     * 例外を握りつぶす責務を持つ。本メソッドは例外をそのまま伝播させる
     * （個別 (slot,user) ごとに try-catch して残り処理を続行する責任は呼び出し側）。</p>
     *
     * <p>各閾値ごとに独立トランザクション ({@link Propagation#REQUIRES_NEW}) で評価することで、
     * ある閾値の発火失敗が他の閾値の評価を巻き戻さないようにする。</p>
     *
     * @param allocationId 評価対象 allocation の ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void evaluateAndTrigger(Long allocationId) {
        ShiftBudgetAllocationEntity allocation = allocationRepository.findById(allocationId)
                .orElse(null);
        if (allocation == null || allocation.getDeletedAt() != null) {
            log.debug("F08.7 閾値判定: 対象 allocation 不在/論理削除済のためスキップ: id={}", allocationId);
            return;
        }

        BigDecimal allocated = allocation.getAllocatedAmount();
        BigDecimal consumed = allocation.getConsumedAmount();
        if (allocated == null || allocated.signum() == 0) {
            // 予算ゼロ円の境界ケース: 設計書 §11 では即 100% 超過判定とするが、
            // 本実装では「割当0+消化0=0%」として WARN/EXCEEDED いずれも発火させない方針
            // （割当0+消化>0 は理論上ありえる：その場合は 100/120% 発火相当と扱う）
            if (consumed != null && consumed.signum() > 0) {
                triggerForZeroBudget(allocation, consumed);
            }
            return;
        }

        // 消化率を 4 桁小数で算出（BigDecimal で厳密計算）
        BigDecimal rate = consumed.divide(allocated, 4, RoundingMode.HALF_UP);
        BigDecimal percent = rate.multiply(BigDecimal.valueOf(100));

        for (int threshold : THRESHOLDS) {
            if (percent.compareTo(BigDecimal.valueOf(threshold)) >= 0) {
                fireAlertIfNotExists(allocation, threshold, consumed);
            }
        }
    }

    /**
     * 予算 0 円で消化発生したケースの特殊処理（設計書 §11 境界ケース）。
     */
    private void triggerForZeroBudget(ShiftBudgetAllocationEntity allocation, BigDecimal consumed) {
        // 100% / 120% 相当として扱う
        fireAlertIfNotExists(allocation, 100, consumed);
        fireAlertIfNotExists(allocation, 120, consumed);
    }

    /**
     * 指定閾値の警告レコードが既存でなければ INSERT + 通知発火する。
     *
     * <p>UNIQUE (allocation_id, threshold_percent) により真の防衛線は DB 層だが、
     * アプリ層でも事前 SELECT で重複を抑止して通知の重送を防ぐ。</p>
     */
    private void fireAlertIfNotExists(ShiftBudgetAllocationEntity allocation,
                                      int thresholdPercent,
                                      BigDecimal consumedAtTrigger) {
        if (alertRepository.findByAllocationIdAndThresholdPercent(
                allocation.getId(), thresholdPercent).isPresent()) {
            log.debug("F08.7 閾値判定: 既存 alert あり → skip: allocId={}, threshold={}%",
                    allocation.getId(), thresholdPercent);
            return;
        }

        // 受信ロール解決: 当該組織の ADMIN/DEPUTY_ADMIN + BUDGET_ADMIN 保有者の和集合
        List<Long> recipientUserIds = resolveRecipients(allocation.getOrganizationId());

        // alert レコード INSERT（UNIQUE で重複時は DataIntegrityViolationException、上位で握りつぶす）
        BudgetThresholdAlertEntity alert = BudgetThresholdAlertEntity.builder()
                .allocationId(allocation.getId())
                .thresholdPercent(thresholdPercent)
                .triggeredAt(LocalDateTime.now())
                .consumedAmountAtTrigger(consumedAtTrigger)
                .notifiedUserIds(serializeUserIds(recipientUserIds))
                .build();
        try {
            alertRepository.saveAndFlush(alert);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 並行 INSERT 競合（UNIQUE 違反）— 既に他スレッドが先に発火させた → skip
            log.debug("F08.7 閾値判定: 並行 INSERT 競合（UNIQUE 違反）でスキップ: allocId={}, threshold={}%",
                    allocation.getId(), thresholdPercent);
            return;
        }

        // 通知発火
        sendNotifications(allocation, thresholdPercent, recipientUserIds);

        // F05.6 ワークフロー起動（100% 到達時のみ）
        if (thresholdPercent >= WORKFLOW_TRIGGER_THRESHOLD) {
            tryStartWorkflow(allocation);
        }

        // 監査ログ
        auditLogService.record(
                "BUDGET_THRESHOLD_ALERT_TRIGGERED",
                null, null,
                allocation.getTeamId(), allocation.getOrganizationId(),
                null, null, null,
                String.format("{\"allocation_id\":%d,\"threshold_percent\":%d,"
                                + "\"consumed_amount\":%s,\"recipient_count\":%d}",
                        allocation.getId(), thresholdPercent,
                        consumedAtTrigger, recipientUserIds.size()));

        log.info("F08.7 閾値超過警告を発火: allocId={}, threshold={}%, recipients={}",
                allocation.getId(), thresholdPercent, recipientUserIds.size());
    }

    /**
     * 受信ロール解決: ADMIN/DEPUTY_ADMIN ∪ BUDGET_ADMIN 保有者。
     *
     * <p>設計書 §4.5 「対象ロール（ADMIN + 予算管理者）」に対応。
     * V11.034 マイグレーション後は両者がほぼ重複するが、組織が個別に
     * BUDGET_ADMIN を別ユーザーへ追加付与しているケースを取りこぼさないため両系を OR で結合する。</p>
     */
    private List<Long> resolveRecipients(Long organizationId) {
        Set<Long> uniq = new HashSet<>();
        uniq.addAll(userRoleRepository.findAdminUserIdsByOrganizationId(organizationId));
        uniq.addAll(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(
                organizationId, "BUDGET_ADMIN"));
        List<Long> sorted = new ArrayList<>(uniq);
        sorted.sort(Long::compareTo);
        return sorted;
    }

    private void sendNotifications(ShiftBudgetAllocationEntity allocation,
                                   int thresholdPercent,
                                   List<Long> recipientUserIds) {
        if (recipientUserIds.isEmpty()) {
            log.warn("F08.7 閾値超過警告: 受信ロール 0 名のため通知発火スキップ: allocId={}, threshold={}%",
                    allocation.getId(), thresholdPercent);
            return;
        }
        String title = "シフト予算 警告 (" + thresholdPercent + "%)";
        String body = bodyForThreshold(thresholdPercent);
        String actionUrl = "/shift-budget/allocations/" + allocation.getId();

        notificationHelper.notifyAll(
                recipientUserIds,
                "SHIFT_BUDGET_THRESHOLD_ALERT",
                title, body,
                "SHIFT_BUDGET_ALLOCATION",
                allocation.getId(),
                NotificationScopeType.ORGANIZATION,
                allocation.getOrganizationId(),
                actionUrl,
                null  // システム自動発火、actor なし
        );
    }

    /**
     * 閾値ごとの通知本文を返す（i18n キーを意図して固定文言で返す）。
     *
     * <p>i18n キー対応:</p>
     * <ul>
     *   <li>80% → {@code shiftBudget.threshold.warn80}（フロント側で翻訳）</li>
     *   <li>100% → {@code shiftBudget.threshold.exceeded}</li>
     *   <li>120% → {@code shiftBudget.threshold.severeExceeded120}</li>
     * </ul>
     */
    private String bodyForThreshold(int thresholdPercent) {
        return switch (thresholdPercent) {
            case 80 -> "予算 80% に到達しました";
            case 100 -> "予算を超過しました";
            case 120 -> "予算 120% を超過しました（重大）";
            default -> "シフト予算が閾値 " + thresholdPercent + "% に到達しました";
        };
    }

    /**
     * F05.6 ワークフロー起動（100% 到達時）。
     *
     * <p><strong>マスター御裁可 Q2</strong>: Phase 9-δ では F05.6 連携は実装せず、
     * 監査ログ {@code WORKFLOW_NOT_CONFIGURED} の記録のみ行う。実際の起動は Phase 10 へ持越し。</p>
     */
    private void tryStartWorkflow(ShiftBudgetAllocationEntity allocation) {
        // TODO(Phase 10): F05.6 WorkflowRequestService 起動 (over_limit_workflow_id)
        Long workflowId = budgetConfigRepository
                .findByScopeTypeAndScopeId("ORGANIZATION", allocation.getOrganizationId())
                .map(BudgetConfigEntity::getOverLimitWorkflowId)
                .orElse(null);
        if (workflowId == null) {
            auditLogService.record(
                    "WORKFLOW_NOT_CONFIGURED",
                    null, null,
                    allocation.getTeamId(), allocation.getOrganizationId(),
                    null, null, null,
                    String.format("{\"allocation_id\":%d,\"reason\":\"over_limit_workflow_id is null\"}",
                            allocation.getId()));
            log.info("F08.7 100% 到達: 組織が over_limit_workflow_id 未設定のためワークフロー起動スキップ: "
                    + "allocId={}, orgId={}", allocation.getId(), allocation.getOrganizationId());
        } else {
            // 設定はされているが Phase 10 で起動コードを実装予定
            log.info("F08.7 100% 到達: ワークフロー起動は Phase 10 で実装予定 (TODO): "
                    + "allocId={}, workflowId={}", allocation.getId(), workflowId);
        }
    }

    private String serializeUserIds(List<Long> userIds) {
        try {
            return objectMapper.writeValueAsString(userIds);
        } catch (JsonProcessingException e) {
            log.warn("F08.7 通知先ユーザーIDのシリアライズ失敗: {}", userIds, e);
            return "[]";
        }
    }
}
