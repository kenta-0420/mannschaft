package com.mannschaft.app.shiftbudget.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.event.ShiftPublishedEvent;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetConsumptionService;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetLockSetChangedException;
import com.mannschaft.app.shiftbudget.service.ShiftRecordOutcome;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetFailedEventService;
import com.mannschaft.app.shiftbudget.event.ShiftBudgetHourlyRateMissingEvent;
import com.mannschaft.app.shiftbudget.service.ThresholdAlertEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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
    /** Phase 10-β で追加: 失敗イベントの永続化（リトライバッチ + 管理 API の入口） */
    private final ShiftBudgetFailedEventService failedEventService;
    /**
     * CMP-260910-1555 で追加: 時給未設定により消化記録をスキップしたことの管理者通知。
     *
     * <p>ここでは {@link ShiftBudgetHourlyRateMissingEvent} を publish するだけで、実配送は
     * {@code ShiftBudgetHourlyRateMissingNotificationListener} が AFTER_COMMIT 境界の後に行う
     * （CMP-056 / Issue #2990 の正規形。配送 Runner を業務側から直接呼ばない）。</p>
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 通常の非同期入口（公開イベントの AFTER_COMMIT）。同期本体 {@link #onShiftPublished} を呼ぶだけ
     * （CMP-260909-2152: 遅延再実行が同じ記録を同期で実行できるよう、非同期の注釈と本体を分けた）。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "殿の裁定: 記録と取消は対であり、片方だけ止まれば予算残高が壊れる。対になっているものを別々に扱ってはならず、いずれも常時実行とする")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleShiftPublished(ShiftPublishedEvent event) {
        onShiftPublished(event);
    }

    /**
     * 同期本体。各 (slot, user) の結果を {@link ShiftRecordOutcome} に集計して返す（遅延再実行が REPLAYED／REDEFERRED／失敗を決める）。
     * 計上先ロック集合の不一致は計上先の解決からやり直し（最大3回）、尽きたら失敗イベント CONSUMPTION_RECORD を残す。
     */
    public ShiftRecordOutcome onShiftPublished(ShiftPublishedEvent event) {
        Long scheduleId = event.getScheduleId();
        Long teamId = event.getTeamId();
        int outRecorded = 0;
        int outSkipped = 0;
        int outDeferred = 0;
        List<ShiftRecordOutcome.SlotUser> outFailures = new java.util.ArrayList<>();
        boolean fatalFailure = false;

        // 計上先の組織（F01.2.1 §9.2 #14）。チームは複数の組織に加盟しうるため、チームから組織を
        // 推測せず、スロットごとに「そのチーム・日付に予算割当を持つ組織」を割当から解決する。
        // 監査ログ・時給未設定の通知・失敗イベントは、計上した組織ごとに分けて出す
        // （組織 Y の利用者の情報を組織 X の予算管理者へ流さない）。
        Map<Long, OrgTally> tallies = new java.util.LinkedHashMap<>();
        try {
            Map<Long, Boolean> featureEnabledByOrg = new java.util.HashMap<>();

            // 計上先の組織が確定できなかったスロット数（組織に帰属させられないので組織なしで監査する）
            int unresolvedSkipped = 0;

            List<ShiftSlotEntity> slots =
                    slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(scheduleId);

            for (ShiftSlotEntity slot : slots) {
                // 当日の allocation を解決（割当を持つ組織 → その組織の allocation）
                Optional<ShiftBudgetAllocationEntity> allocOpt = resolveAllocationForSlot(teamId, slot.getSlotDate());
                if (allocOpt.isEmpty()) {
                    log.warn("F08.7 hook: 該当 allocation 不在または計上先の組織を一意に決められない"
                                    + "（事前作成漏れの可能性）: teamId={}, slotDate={}, slotId={}",
                            teamId, slot.getSlotDate(), slot.getId());
                    unresolvedSkipped++;
                    outSkipped++;
                    continue;
                }
                ShiftBudgetAllocationEntity allocation = allocOpt.get();
                Long organizationId = allocation.getOrganizationId();

                // フィーチャーフラグ判定（計上先の組織で判定。OFF なら何もせず既存シフト機能を阻害しない）
                if (!featureEnabledByOrg.computeIfAbsent(organizationId, featureService::isEnabled)) {
                    log.debug("F08.7 hook: フィーチャーフラグ OFF のためスキップ: organizationId={}",
                            organizationId);
                    continue;
                }
                List<Long> assignedUserIds = parseAssignedUserIds(slot.getAssignedUserIds());
                if (assignedUserIds.isEmpty()) {
                    tallies.computeIfAbsent(organizationId, k -> new OrgTally());
                    continue;  // 未割当スロットは消化記録しない
                }

                BigDecimal hours = ShiftBudgetConsumptionService.calculateHours(
                        slot.getStartTime(), slot.getEndTime());

                // CMP-261115-1821: 枠の値は一度だけ読む（他ドメイン Entity への参照箇所を増やさない。D-1 凍結）
                Long slotId = slot.getId();
                java.time.LocalDate slotDate = slot.getSlotDate();
                for (Long userId : assignedUserIds) {
                    // CMP-261115-1821: 個別失敗の failed_event に実計上入力を残すため、採った時給を外で保持する
                    BigDecimal adoptedRate = null;
                    // CMP-260909-2152 再検分 P2-1: 再試行中に勤務日が動いて計上先の組織が変わったら、集計・監査・失敗イベントの
                    // 帰属も再解決後の組織へ切り替える（当初の組織に残さない）。個別失敗の catch でも参照するため try の外に置く
                    ShiftBudgetAllocationEntity attemptAllocation = allocation;
                    java.time.LocalDate attemptDate = slotDate;
                    try {
                        // 計上先ロック集合の不一致は、勤務枠の現在日付を読み直して計上先の解決からやり直す（最大3回）。
                        // 時給も試行ごとの勤務日（再解決後の日付）で取り直し、正数チェックもやり直す
                        // （時給の切替日をまたいで枠が移動していても、元の勤務日の時給で記録しない）。
                        // 時給の参照箇所は増やさない（ShiftHourlyRateRepository の呼出箇所は1つのまま）
                        ShiftBudgetConsumptionService.RecordResult result;
                        int attempt = 0;
                        boolean rateRejected = false;
                        boolean featureDisabled = false;
                        while (true) {
                            Long attemptOrgId = attemptAllocation.getOrganizationId();
                            // CMP-260910-1555: 是正前はここで .orElse(BigDecimal.ZERO) としており、
                            // 時給未登録のメンバーを「単価 0 円で記録成功」として扱っていた。
                            // 消化額は 0 のまま増えず、消化率も 0% なので 80/100/120% の閾値警告も
                            // 永久に発火せず、ログは recorded=N, skipped=0 と成功を報告する——
                            // すなわち予算管理が丸ごと無効なのに誰にも分からない状態を作っていた。
                            // 0 円という誤った金額を台帳に残すより記録しないほうが正しいので skip し、
                            // 対象ユーザーを集めて hook の最後に予算管理者へ通知する（黙らせない）。
                            Optional<BigDecimal> hourlyRateOpt = hourlyRateRepository
                                    .findEffectiveRate(userId, teamId, attemptDate)
                                    .map(r -> r.getHourlyRate());
                            // CMP-261115-1821: 存在する 0・負数も未設定と同じく計上しない（0 円・負額で台帳を汚さない）。
                            // 公開時の検証後に時給が削除・訂正された場合もここで止まる。黙らせず、既存の通知集合に
                            // 含めたうえで、組織が解決済みなので当該組織の failed_event にも理由を残す（0 へ置換しない）。
                            String rateProblem = hourlyRateOpt.isEmpty() ? RATE_MISSING
                                    : (hourlyRateOpt.get().signum() <= 0 ? RATE_NON_POSITIVE : null);
                            if (rateProblem != null) {
                                log.warn("F08.7 hook: 時給が未設定または正数でないため消化記録をスキップ: "
                                                + "teamId={}, userId={}, slotId={}, slotDate={}, reason={}",
                                        teamId, userId, slotId, attemptDate, rateProblem);
                                OrgTally attemptTally = tallies.computeIfAbsent(attemptOrgId, k -> new OrgTally());
                                attemptTally.missingRateUserIds.add(userId);
                                attemptTally.skipped++;
                                outSkipped++;
                                recordFailureSafe(attemptOrgId, ShiftBudgetFailedEventType.CONSUMPTION_RECORD,
                                        scheduleId,
                                        consumptionFailurePayload(scheduleId, teamId, slotId, attemptDate, userId, rateProblem,
                                                attemptAllocation.getId(), attemptOrgId, null, null),
                                        "hourly rate " + rateProblem + ": slot=" + slotId + ", user=" + userId);
                                rateRejected = true;
                                result = null;
                                break;
                            }
                            BigDecimal hourlyRate = hourlyRateOpt.get();
                            adoptedRate = hourlyRate;
                            try {
                                result = consumptionService.recordSingleConsumption(
                                        attemptAllocation.getId(),
                                        scheduleId,
                                        slotId,
                                        userId,
                                        hourlyRate,
                                        hours,
                                        attemptDate);
                                break;
                            } catch (ShiftBudgetLockSetChangedException lockChanged) {
                                if (++attempt >= ShiftBudgetConsumptionService.MAX_LOCK_ATTEMPTS) {
                                    throw lockChanged;
                                }
                                Optional<java.time.LocalDate> fresh = rateQueryRepository.findSlotDate(slotId);
                                Optional<ShiftBudgetAllocationEntity> reResolved = fresh.isEmpty()
                                        ? Optional.empty() : resolveAllocationForSlot(teamId, fresh.get());
                                if (reResolved.isEmpty()) {
                                    result = null;
                                    break;
                                }
                                // 計上先の組織が変わったら、その組織の機能フラグで判定し直す（OFF なら入口と同じく何もしない）
                                Long reResolvedOrgId = reResolved.get().getOrganizationId();
                                if (!featureEnabledByOrg.computeIfAbsent(reResolvedOrgId, featureService::isEnabled)) {
                                    log.debug("F08.7 hook: 再解決後の計上先組織がフィーチャーフラグ OFF のためスキップ: "
                                            + "organizationId={}, slotId={}", reResolvedOrgId, slotId);
                                    featureDisabled = true;
                                    result = null;
                                    break;
                                }
                                attemptDate = fresh.get();
                                attemptAllocation = reResolved.get();
                            }
                        }
                        if (rateRejected || featureDisabled) {
                            continue;
                        }
                        OrgTally tally = tallies.computeIfAbsent(attemptAllocation.getOrganizationId(), k -> new OrgTally());
                        if (result == ShiftBudgetConsumptionService.RecordResult.DEFERRED) {
                            outDeferred++;
                            continue;
                        }
                        if (result != ShiftBudgetConsumptionService.RecordResult.RECORDED) {
                            // 締め済み・計上済み・非公開・枠消滅は正常スキップ（記録しない）
                            tally.skipped++;
                            outSkipped++;
                            continue;
                        }
                        tally.recorded++;
                        outRecorded++;

                        // Phase 9-δ 追加: 各 (slot,user) PLANNED INSERT 後に閾値判定を呼ぶ。
                        // 例外は飲み込む（既存パターン踏襲: hook 失敗が main トランザクションを巻き戻さないため）
                        try {
                            thresholdAlertEvaluationService.evaluateAndTrigger(attemptAllocation.getId());
                        } catch (Exception thresholdEx) {
                            log.error("F08.7 hook: 閾値判定失敗（処理継続）: allocId={}, slot={}, user={}",
                                    attemptAllocation.getId(), slotId, userId, thresholdEx);
                            // Phase 10-β: 失敗イベントとして永続化 → リトライバッチ + 管理 API で根治
                            recordFailureSafe(attemptAllocation.getOrganizationId(),
                                    ShiftBudgetFailedEventType.THRESHOLD_ALERT,
                                    attemptAllocation.getId(),
                                    Map.of(
                                            "allocation_id", attemptAllocation.getId(),
                                            "shift_schedule_id", scheduleId,
                                            "slot_id", slotId,
                                            "user_id", userId
                                    ),
                                    thresholdEx);
                        }
                    } catch (Exception e) {
                        log.error("F08.7 hook: 個別消化記録失敗 (skip): slot={}, user={}",
                                slotId, userId, e);
                        Long failedOrgId = attemptAllocation.getOrganizationId();
                        tallies.computeIfAbsent(failedOrgId, k -> new OrgTally()).skipped++;
                        outFailures.add(new ShiftRecordOutcome.SlotUser(slotId, userId));
                        // CMP-261115-1821: 個別失敗もログだけで終わらせず、当該組織の failed_event に残す。
                        // 全体 catch へ投げると他の (slot,user) まで止まるため、ここで個別に記録して残りを続ける。
                        recordFailureSafe(failedOrgId, ShiftBudgetFailedEventType.CONSUMPTION_RECORD,
                                scheduleId,
                                consumptionFailurePayload(scheduleId, teamId, slotId, attemptDate, userId, REASON_RECORD_FAILED,
                                        attemptAllocation.getId(), failedOrgId, adoptedRate, hours),
                                e);
                    }
                }
            }

            if (tallies.isEmpty() && unresolvedSkipped == 0) {
                // 計上先の組織が 1 つも確定せず、スキップもしていない（全スロットがフラグ OFF の組織、
                // またはスロット無し）。既存シフト機能を阻害しないよう何も記録せず終える。
                return new ShiftRecordOutcome(outRecorded, outSkipped, outDeferred, outFailures.size(), outFailures);
            }

            if (unresolvedSkipped > 0) {
                log.warn("F08.7 hook: 計上先を決められないスロットあり: scheduleId={}, teamId={}, skipped={}",
                        scheduleId, teamId, unresolvedSkipped);
                auditLogService.record(
                        "SHIFT_BUDGET_CONSUMPTION_RECORDED",
                        event.getTriggeredByUserId(), null,
                        teamId, null,
                        null, null, null,
                        String.format("{\"shift_schedule_id\":%d,\"recorded_count\":0,\"skipped_count\":%d,"
                                        + "\"missing_hourly_rate_count\":0}",
                                scheduleId, unresolvedSkipped));
            }

            for (Map.Entry<Long, OrgTally> entry : tallies.entrySet()) {
                Long organizationId = entry.getKey();
                OrgTally tally = entry.getValue();
                log.info("F08.7 hook: シフト公開→消化記録完了: scheduleId={}, organizationId={}, recorded={}, "
                                + "skipped={}, missingHourlyRateUsers={}",
                        scheduleId, organizationId, tally.recorded, tally.skipped,
                        tally.missingRateUserIds.size());

                auditLogService.record(
                        "SHIFT_BUDGET_CONSUMPTION_RECORDED",
                        event.getTriggeredByUserId(), null,
                        teamId, organizationId,
                        null, null, null,
                        String.format("{\"shift_schedule_id\":%d,\"recorded_count\":%d,\"skipped_count\":%d,"
                                        + "\"missing_hourly_rate_count\":%d}",
                                scheduleId, tally.recorded, tally.skipped, tally.missingRateUserIds.size()));

                // CMP-260910-1555: 時給未設定は「黙って 0 円」ではなく、その予算を持つ組織の管理者へ届ける。
                // publish の失敗が消化記録の成功を無かったことにしてはならないので、ここでも個別に握る
                // （配送側は失敗した受信者を NOTIFICATION_SEND の failed event に残して再送経路へ載せる）。
                if (!tally.missingRateUserIds.isEmpty()) {
                    try {
                        eventPublisher.publishEvent(new ShiftBudgetHourlyRateMissingEvent(
                                organizationId, teamId,
                                rateQueryRepository.findTeamSlugByTeamId(teamId).orElse(null),
                                scheduleId, List.copyOf(tally.missingRateUserIds)));
                    } catch (Exception notifyEx) {
                        log.error("F08.7 hook: 時給未設定警告イベントの publish に失敗（消化記録自体は完了済）: "
                                        + "scheduleId={}, teamId={}, organizationId={}, missingUsers={}",
                                scheduleId, teamId, organizationId, tally.missingRateUserIds.size(), notifyEx);
                    }
                }
            }

        } catch (Exception e) {
            fatalFailure = true;
            // hook 全体の致命的失敗を握りつぶす（main トランザクション保護）。
            // ただし監査ログ + ERROR ログ + Phase 10-β failed_events でフォレンジック可能性を残す。
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
            // Phase 10-β: 計上先の組織が確定していれば failed_events にも記録。
            // 確定前（hook 入口で例外）は組織が無いため記録不可（チームから組織を推測して記録しない）。
            try {
                for (Long failedOrgId : tallies.keySet()) {
                    recordFailureSafe(failedOrgId,
                            ShiftBudgetFailedEventType.CONSUMPTION_RECORD,
                            scheduleId,
                            Map.of(
                                    "shift_schedule_id", scheduleId,
                                    "team_id", teamId,
                                    "triggered_by_user_id",
                                    event.getTriggeredByUserId() == null ? -1L : event.getTriggeredByUserId()
                            ),
                            e);
                }
            } catch (Exception ignore) {
                // failed_events 記録自体の失敗も諦める（ERROR ログは既に出ている）
            }
        }
        return new ShiftRecordOutcome(outRecorded, outSkipped, outDeferred,
                outFailures.size() + (fatalFailure ? 1 : 0), outFailures);
    }

    /** 組織ごとの計上集計（監査ログ・通知を組織単位で出すため）。 */
    private static final class OrgTally {
        private int recorded;
        private int skipped;
        /** 時給未設定でスキップしたユーザー（重複なし・昇順）。 */
        private final Set<Long> missingRateUserIds = new TreeSet<>();
    }

    /** CMP-261115-1821: 個別計上失敗の payload の版。 */
    static final int CONSUMPTION_FAILURE_PAYLOAD_SCHEMA_VERSION = 1;
    /** 時給行が無い。 */
    static final String RATE_MISSING = "MISSING";
    /** 時給が 0 以下（または null）。 */
    static final String RATE_NON_POSITIVE = "NON_POSITIVE";
    /** 正数の時給を採った後の記録処理（INSERT・予算加算など）が失敗した。 */
    static final String REASON_RECORD_FAILED = "RECORD_FAILED";

    /**
     * CMP-261115-1821: 個別 (slot,user) の計上失敗を当該組織の failed_event に残す payload。
     *
     * <p>欠測値を 0 へ置換しない（時給・時間が無いときは項目自体を入れない）。他組織の金銭情報は混ぜない
     * （呼び出し側は計上先の組織の failed_event にだけ記録する）。</p>
     */
    private static Map<String, Object> consumptionFailurePayload(Long scheduleId, Long teamId, Long slotId,
                                                                 java.time.LocalDate slotDate, Long userId, String reason, Long allocationId,
                                                                 Long organizationId, BigDecimal hourlyRate,
                                                                 BigDecimal hours) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("schema_version", CONSUMPTION_FAILURE_PAYLOAD_SCHEMA_VERSION);
        payload.put("shift_schedule_id", scheduleId);
        payload.put("team_id", teamId);
        payload.put("slot_id", slotId);
        payload.put("user_id", userId);
        payload.put("slot_date", String.valueOf(slotDate));
        payload.put("reason", reason);
        payload.put("allocation_id", allocationId);
        payload.put("organization_id", organizationId);
        if (hourlyRate != null) {
            payload.put("hourly_rate", hourlyRate.toPlainString());
        }
        if (hourlyRate != null && hours != null) {
            payload.put("hours", hours.toPlainString());
        }
        return payload;
    }

    /**
     * Phase 10-β: 失敗イベント記録を例外安全に呼ぶ。
     * recordFailure 自体が例外を投げても hook の他処理に影響しないよう catch する。
     */
    private void recordFailureSafe(Long organizationId, ShiftBudgetFailedEventType eventType,
                                   Long sourceId, Map<String, Object> payload, Throwable cause) {
        recordFailureSafe(organizationId, eventType, sourceId, payload,
                cause.getClass().getSimpleName() + ": " + cause.getMessage());
    }

    /** 例外を伴わない失敗（時給の欠測など）を例外安全に記録する。 */
    private void recordFailureSafe(Long organizationId, ShiftBudgetFailedEventType eventType,
                                   Long sourceId, Map<String, Object> payload, String errorMsg) {
        try {
            failedEventService.recordFailure(organizationId, eventType, sourceId, payload, errorMsg);
        } catch (Exception recEx) {
            log.error("F08.7 hook: failed_events 記録自体も失敗（諦め）: orgId={}, eventType={}",
                    organizationId, eventType, recEx);
        }
    }

    /**
     * スロット日付に該当する allocation を、計上先の組織から解決する（F01.2.1 §9.2 #14）。
     *
     * <ol>
     *   <li>チーム個別の割当（{@code team_id = T}）を持ち、T が ACTIVE 加盟している組織。作成時に
     *       重なる期間は 1 組織に限られるので通常 1 件。複数なら決められないのでスキップ</li>
     *   <li>無ければ、T が ACTIVE 加盟している組織のうち、組織全体割当（{@code team_id IS NULL}）を持つ組織。
     *       ちょうど 1 件のときだけ採用する。複数の親が組織全体割当を持つ場合は計上先を決められないため、
     *       任意の 1 件に計上せずスキップする</li>
     * </ol>
     */
    private Optional<ShiftBudgetAllocationEntity> resolveAllocationForSlot(Long teamId, java.time.LocalDate slotDate) {
        List<Long> teamScopeOrgIds = allocationRepository
                .findOrganizationIdsWithTeamAllocationContaining(teamId, slotDate);
        if (teamScopeOrgIds.size() > 1) {
            log.error("F08.7 hook: 同じチーム・日付に複数の組織が割当を持つ（重複禁止の破れ）。計上しない: "
                    + "teamId={}, slotDate={}, organizationIds={}", teamId, slotDate, teamScopeOrgIds);
            return Optional.empty();
        }
        if (teamScopeOrgIds.size() == 1) {
            return allocationRepository.findContainingPeriod(
                    teamScopeOrgIds.get(0), teamId, slotDate);
        }
        List<Long> orgWideOrgIds = allocationRepository
                .findOrganizationIdsWithOrgWideAllocationContaining(teamId, slotDate);
        if (orgWideOrgIds.size() == 1) {
            return allocationRepository.findContainingPeriod(orgWideOrgIds.get(0), null, slotDate);
        }
        return Optional.empty();
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
