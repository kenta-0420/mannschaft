package com.mannschaft.app.shiftbudget.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.event.ShiftPublishedEvent;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetConsumptionService;
import com.mannschaft.app.shiftbudget.service.ThresholdAlertEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * F08.7 シフト公開→消化記録 hook（Phase 9-β / 設計書 §11.1）。
 *
 * <p>{@link ShiftPublishedEvent} を {@code AFTER_COMMIT} で購読し、
 * 当該シフトの全 (slot × assigned_user_ids) 組み合わせに対して
 * {@code shift_budget_consumptions} を PLANNED で記録する。</p>
 *
 * <p><strong>方針</strong>:</p>
 * <ul>
 *   <li>フィーチャーフラグ OFF 時: early return（既存シフト機能を阻害しない）</li>
 *   <li>当月 allocation 不在時: WARN ログ + no-op（マスター御裁可 Q3、UC-2 は事前作成運用）</li>
 *   <li>個別 (slot,user) で例外発生時: 当該分のみスキップして残りは継続
 *       （hook 失敗が main トランザクションを巻き戻さないよう
 *        {@link com.mannschaft.app.shiftbudget.service.ShiftBudgetConsumptionService#recordSingleConsumption}
 *        が {@code REQUIRES_NEW} で動く）</li>
 *   <li>致命的エラー: ERROR ログ + 監査ログ {@code SHIFT_BUDGET_CONSUMPTION_FAILED}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftBudgetConsumptionRecordListener {

    private final ShiftBudgetFeatureService featureService;
    private final ShiftBudgetAllocationRepository allocationRepository;
    private final ShiftBudgetRateQueryRepository rateQueryRepository;
    private final ShiftBudgetConsumptionService consumptionService;
    private final ShiftSlotRepository slotRepository;
    private final ShiftHourlyRateRepository hourlyRateRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    /** Phase 9-δ で追加: 閾値判定 hook */
    private final ThresholdAlertEvaluationService thresholdAlertEvaluationService;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShiftPublished(ShiftPublishedEvent event) {
        Long scheduleId = event.getScheduleId();
        Long teamId = event.getTeamId();

        try {
            // team_id → organization_id 解決
            Optional<Long> orgIdOpt = rateQueryRepository.findOrganizationIdByTeamId(teamId);
            if (orgIdOpt.isEmpty()) {
                log.warn("F08.7 hook: organization_id を解決できないためスキップ: scheduleId={}, teamId={}",
                        scheduleId, teamId);
                return;
            }
            Long organizationId = orgIdOpt.get();

            // フィーチャーフラグ判定（OFF なら何もせず終了。既存シフト機能を阻害しない）
            if (!featureService.isEnabled(organizationId)) {
                log.debug("F08.7 hook: フィーチャーフラグ OFF のためスキップ: organizationId={}",
                        organizationId);
                return;
            }

            int recordedCount = 0;
            int skippedCount = 0;

            List<ShiftSlotEntity> slots =
                    slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(scheduleId);

            for (ShiftSlotEntity slot : slots) {
                // 当月 allocation を解決（teamId / NULL の両系で検索）
                Optional<ShiftBudgetAllocationEntity> allocOpt = resolveAllocationForSlot(
                        organizationId, teamId, slot);
                if (allocOpt.isEmpty()) {
                    log.warn("F08.7 hook: 該当 allocation 不在（事前作成漏れの可能性）: "
                                    + "organizationId={}, teamId={}, slotDate={}, slotId={}",
                            organizationId, teamId, slot.getSlotDate(), slot.getId());
                    skippedCount++;
                    continue;
                }
                ShiftBudgetAllocationEntity allocation = allocOpt.get();

                List<Long> assignedUserIds = parseAssignedUserIds(slot.getAssignedUserIds());
                if (assignedUserIds.isEmpty()) {
                    continue;  // 未割当スロットは消化記録しない
                }

                BigDecimal hours = ShiftBudgetConsumptionService.calculateHours(
                        slot.getStartTime(), slot.getEndTime());

                for (Long userId : assignedUserIds) {
                    try {
                        BigDecimal hourlyRate = hourlyRateRepository
                                .findEffectiveRate(userId, teamId, slot.getSlotDate())
                                .map(r -> r.getHourlyRate())
                                .orElse(BigDecimal.ZERO);

                        consumptionService.recordSingleConsumption(
                                allocation.getId(),
                                scheduleId,
                                slot.getId(),
                                userId,
                                hourlyRate,
                                hours);
                        recordedCount++;

                        // Phase 9-δ 追加: 各 (slot,user) PLANNED INSERT 後に閾値判定を呼ぶ。
                        // 例外は飲み込む（既存パターン踏襲: hook 失敗が main トランザクションを巻き戻さないため）
                        try {
                            thresholdAlertEvaluationService.evaluateAndTrigger(allocation.getId());
                        } catch (Exception thresholdEx) {
                            log.error("F08.7 hook: 閾値判定失敗（処理継続）: allocId={}, slot={}, user={}",
                                    allocation.getId(), slot.getId(), userId, thresholdEx);
                        }
                    } catch (IllegalStateException e) {
                        // CONFIRMED 既存 → スキップして他継続（個別エラーで全体を止めない）
                        log.warn("F08.7 hook: CONFIRMED 既存のため skip: slot={}, user={}, msg={}",
                                slot.getId(), userId, e.getMessage());
                        skippedCount++;
                    } catch (Exception e) {
                        log.error("F08.7 hook: 個別消化記録失敗 (skip): slot={}, user={}",
                                slot.getId(), userId, e);
                        skippedCount++;
                    }
                }
            }

            log.info("F08.7 hook: シフト公開→消化記録完了: scheduleId={}, recorded={}, skipped={}",
                    scheduleId, recordedCount, skippedCount);

            auditLogService.record(
                    "SHIFT_BUDGET_CONSUMPTION_RECORDED",
                    event.getTriggeredByUserId(), null,
                    teamId, organizationId,
                    null, null, null,
                    String.format("{\"shift_schedule_id\":%d,\"recorded_count\":%d,\"skipped_count\":%d}",
                            scheduleId, recordedCount, skippedCount));

        } catch (Exception e) {
            // hook 全体の致命的失敗を握りつぶす（main トランザクション保護）。
            // ただし監査ログ + ERROR ログでフォレンジック可能性は残す。
            log.error("F08.7 hook: シフト公開消化記録の致命的失敗: scheduleId={}, teamId={}",
                    scheduleId, teamId, e);
            try {
                auditLogService.record(
                        "SHIFT_BUDGET_CONSUMPTION_FAILED",
                        event.getTriggeredByUserId(), null,
                        teamId, null,
                        null, null, null,
                        String.format("{\"shift_schedule_id\":%d,\"error\":\"%s\"}",
                                scheduleId, escapeJson(e.getMessage())));
            } catch (Exception ignore) {
                // 監査ログ書き込みも失敗した場合は諦める（メイン処理は既に完了済み）
            }
        }
    }

    /**
     * スロット日付に該当する allocation を解決する。
     * <p>1. teamId スコープ → 2. 組織全体 (teamId=NULL) スコープ の順で探索。</p>
     */
    private Optional<ShiftBudgetAllocationEntity> resolveAllocationForSlot(
            Long organizationId, Long teamId, ShiftSlotEntity slot) {
        Optional<ShiftBudgetAllocationEntity> teamScope = allocationRepository.findContainingPeriod(
                organizationId, teamId, slot.getSlotDate());
        if (teamScope.isPresent()) {
            return teamScope;
        }
        // チーム個別が無ければ組織全体の枠にフォールバック
        return allocationRepository.findContainingPeriod(
                organizationId, null, slot.getSlotDate());
    }

    /**
     * {@code shift_slots.assigned_user_ids} (JSON 文字列) を List&lt;Long&gt; にデシリアライズする。
     */
    private List<Long> parseAssignedUserIds(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("F08.7 hook: assigned_user_ids デシリアライズ失敗: json={}", json, e);
            return Collections.emptyList();
        }
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
