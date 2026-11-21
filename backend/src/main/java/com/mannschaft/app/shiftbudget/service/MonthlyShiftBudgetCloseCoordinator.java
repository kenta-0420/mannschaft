package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.budget.dto.ShiftBudgetMonthlyPostCommand;
import com.mannschaft.app.budget.dto.ShiftBudgetMonthlyPostResult;
import com.mannschaft.app.budget.service.ShiftBudgetMonthlyJournalService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.transaction.NonTransactionalEntryGuard;
import com.mannschaft.app.shiftbudget.ShiftBudgetCloseActorKind;
import com.mannschaft.app.shiftbudget.ShiftBudgetCloseEventState;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.UuidBytes;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetFailedEventRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetMonthlyCloseEventRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRecordDeferralRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * 3段締めの段取り役（@Transactional なし。CMP-260909-2152 確定設計 r12 §3・§7）。
 *
 * <p>全公開メソッドの最初の文で {@link NonTransactionalEntryGuard#assertNoActiveTransaction()} を呼ぶ。認可は持たない（Facade の責務）。
 * 割当×期間ごとに Prepare（shiftbudget TX）→ Post（budget TX）→ Commit／Abort（shiftbudget TX）を順に実行する。
 * shiftbudget と budget のロックを同一 TX で取る経路は無く、機能フラグ・監査・失敗イベントは TX の外で扱う。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyShiftBudgetCloseCoordinator {

    /** 日次バッチが1回の実行で組織あたり処理する期間キーの上限（残りは翌日継続）。 */
    static final int MAX_KEYS_PER_ORGANIZATION = 36;
    /** 遅延記録の回収で1回に処理する最大件数。 */
    private static final int RECOVER_LIMIT = 200;

    private final ShiftBudgetAllocationRepository allocationRepository;
    private final ShiftBudgetMonthlyCloseEventRepository eventRepository;
    private final ShiftBudgetRecordDeferralRepository deferralRepository;
    private final ShiftBudgetFailedEventRepository failedEventRepository;
    private final ShiftBudgetClosePeriodResolver periodResolver;
    private final ShiftBudgetCloseStageService stage;
    private final ShiftBudgetMonthlyJournalService journalService;
    private final ShiftBudgetRecordDeferralService deferralService;
    private final ShiftBudgetFailedEventService failedEventService;
    private final ShiftBudgetFeatureService featureService;
    private final ShiftBudgetBusinessDateProvider dateProvider;
    private final ShiftBudgetLockStepHooks hooks;
    private final AuditLogService auditLogService;

    /**
     * 手動締めの本体（認可済み前提）。指定の期間キー M を締め、各割当の M 以前の未締め終了済み期間を古い順に先に締める（追いつき）。
     *
     * @throws BusinessException 期間が未終了（SHIFT_BUDGET_036）
     */
    public CloseResult close(Long organizationId, YearMonth periodKey) {
        NonTransactionalEntryGuard.assertNoActiveTransaction();
        ShiftBudgetCloseActorContext.set(ShiftBudgetCloseActorKind.MANUAL, SecurityUtils.getCurrentUserIdOrNull());
        try {
            return process(organizationId, periodKey, true);
        } finally {
            ShiftBudgetCloseActorContext.clear();
        }
    }

    /** バッチ経路の1組織×1期間の締め（追いつきなし。機能フラグ OFF の組織は何もしない）。 */
    public CloseResult closeFromBatch(Long organizationId, YearMonth periodKey) {
        NonTransactionalEntryGuard.assertNoActiveTransaction();
        if (!featureService.isEnabled(organizationId)) {
            log.debug("F08.7 期間締め: フィーチャーフラグ OFF の組織のためスキップ: orgId={}", organizationId);
            return new CloseResult(0, 0, 0, 0);
        }
        ShiftBudgetCloseActorContext.set(ShiftBudgetCloseActorKind.BATCH, null);
        CloseResult result;
        try {
            result = process(organizationId, periodKey, false);
        } finally {
            ShiftBudgetCloseActorContext.clear();
        }
        // バッチ経路の監査（手動 close() の監査は Facade が記録するので、ここでは記録しない）
        if (result.closedAllocations() > 0) {
            auditBatchClose(organizationId, periodResolver.resolve(organizationId, periodKey), result.closedAllocations(),
                    result.failedAllocations(), result.closedConsumptions());
        }
        return result;
    }

    /**
     * 日次締めバッチ本体。businessDate の前日までに終了した期間を、組織あたり最大36キー、組織ID昇順・期間キー古い順・割当ID昇順で締める。
     * 候補割当は1クエリで抽出し、対象外の組織には文を発行しない。冪等。
     */
    public DailyCloseResult closeAll(LocalDate businessDate) {
        NonTransactionalEntryGuard.assertNoActiveTransaction();
        LocalDate yesterday = businessDate.minusDays(1);
        List<Long> candidateIds = eventRepository.findDailyCandidateAllocationIds(yesterday);
        if (candidateIds.isEmpty()) {
            return new DailyCloseResult(List.of(), 0, 0, 0);
        }
        Map<Long, List<ShiftBudgetAllocationEntity>> byOrganization = new LinkedHashMap<>();
        for (ShiftBudgetAllocationEntity a : allocationRepository.findAllById(candidateIds)) {
            byOrganization.computeIfAbsent(a.getOrganizationId(), k -> new ArrayList<>()).add(a);
        }
        List<Long> organizationIds = new ArrayList<>(byOrganization.keySet());
        organizationIds.sort(Long::compare);

        List<Long> processed = new ArrayList<>();
        int closedKeys = 0;
        int closedAllocations = 0;
        int failedAllocations = 0;
        ShiftBudgetCloseActorContext.set(ShiftBudgetCloseActorKind.BATCH, null);
        try {
            for (Long organizationId : organizationIds) {
                if (!featureService.isEnabled(organizationId)) {
                    continue;
                }
                try {
                    OrganizationBatch result = processOrganizationBatch(organizationId, byOrganization.get(organizationId),
                            yesterday);
                    if (result.keys() > 0) {
                        processed.add(organizationId);
                    }
                    closedKeys += result.keys();
                    closedAllocations += result.closed();
                    failedAllocations += result.failed();
                } catch (Exception e) {
                    // 1組織の失敗で全体を止めない
                    failedAllocations++;
                    log.error("F08.7 期間締め 日次バッチ: 組織単位失敗（続行）: orgId={}", organizationId, e);
                    recordFailure(organizationId, null, "CLOSE_FAILED", null, null, e.getClass().getSimpleName() + ": "
                            + e.getMessage());
                }
            }
        } finally {
            ShiftBudgetCloseActorContext.clear();
        }
        log.info("F08.7 期間締め 日次バッチ完了: businessDate={}, orgs={}, keys={}, closed={}, failed={}",
                businessDate, processed.size(), closedKeys, closedAllocations, failedAllocations);
        return new DailyCloseResult(processed, closedKeys, closedAllocations, failedAllocations);
    }

    /** 遅延記録の回収（イベントが PREPARED でなくなった PENDING・リース切れ PENDING）。戻り値は処理（確保）できた件数。 */
    public int recoverDeferrals() {
        NonTransactionalEntryGuard.assertNoActiveTransaction();
        int handled = 0;
        for (byte[] raw : deferralRepository.findRecoverableIds(RECOVER_LIMIT)) {
            ShiftBudgetRecordDeferralService.ReplayOutcome outcome = deferralService
                    .replay(UuidBytes.fromBytes(raw), UUID.randomUUID().toString());
            if (outcome != ShiftBudgetRecordDeferralService.ReplayOutcome.NOT_CLAIMED) {
                handled++;
            }
        }
        return handled;
    }

    // ───────────────────────── 手動・単一期間 ─────────────────────────

    private CloseResult process(Long organizationId, YearMonth periodKey, boolean catchUp) {
        LocalDate today = dateProvider.today();
        ShiftBudgetClosePeriodResolver.ResolvedPeriod target = periodResolver.resolve(organizationId, periodKey);
        if (target.end().isAfter(today)) {
            throw new BusinessException(ShiftBudgetErrorCode.CLOSE_PERIOD_NOT_ENDED);
        }
        List<ShiftBudgetAllocationEntity> allocations =
                allocationRepository.findLiveByOrgStartingOnOrBefore(organizationId, target.end());
        if (allocations.isEmpty()) {
            return new CloseResult(0, 0, 0, 0);
        }
        Function<YearMonth, Integer> closingDay = periodResolver.closingDayFunction(organizationId);
        Map<Long, Map<String, ShiftBudgetCloseEventState>> events = activeEventStates(allocations);

        TreeMap<YearMonth, List<ShiftBudgetAllocationEntity>> byKey = new TreeMap<>();
        int already = 0;
        for (ShiftBudgetAllocationEntity a : allocations) {
            YearMonth first = ShiftBudgetClosePeriodCalculator.periodKeyOf(a.getPeriodStart(), closingDay);
            YearMonth lastOfAllocation = ShiftBudgetClosePeriodCalculator.periodKeyOf(a.getPeriodEnd(), closingDay);
            // 締め済みの割当×期間は二重計上回避のため処理対象から除外する（addIfOpen）が、指定期間 M が締め済みの割当は
            // 結果の already に数える（CMP-260909-2152 Codex 検分3 P2-3。数えないと再実行が「締め済み」と判別できない）。
            // 追いつき対象の M より前の締め済み期間は数えない（再実行のたびに過去期間の数だけ膨らませない）。
            if (!first.isAfter(periodKey) && !lastOfAllocation.isBefore(periodKey) && isClosed(events, a, periodKey)) {
                already++;
            }
            if (!catchUp) {
                if (!first.isAfter(periodKey) && !lastOfAllocation.isBefore(periodKey)) {
                    addIfOpen(byKey, events, a, periodKey);
                }
                continue;
            }
            YearMonth last = lastOfAllocation.isAfter(periodKey) ? periodKey : lastOfAllocation;
            for (YearMonth k = first; !k.isAfter(last); k = k.plusMonths(1)) {
                addIfOpen(byKey, events, a, k);
            }
        }

        int closed = 0;
        int failed = 0;
        int consumptions = 0;
        Set<Long> interrupted = new HashSet<>();
        for (Map.Entry<YearMonth, List<ShiftBudgetAllocationEntity>> entry : byKey.entrySet()) {
            List<ShiftBudgetAllocationEntity> targets = continuing(entry.getValue(), interrupted);
            if (targets.isEmpty()) {
                continue;
            }
            ShiftBudgetClosePeriodResolver.ResolvedPeriod period = ensureFixed(organizationId, entry.getKey(), today);
            for (ShiftBudgetAllocationEntity allocation : targets) {
                AllocationResult r = closeOne(allocation, entry.getKey(), period);
                markIfInterrupted(interrupted, allocation, r);
                switch (r.kind()) {
                    case CLOSED -> {
                        closed++;
                        consumptions += r.lines();
                    }
                    case ALREADY_CLOSED -> already++;
                    case FAILED -> failed++;
                    case SKIPPED -> {
                    }
                }
            }
        }
        log.info("F08.7 期間締め完了: orgId={}, key={}, closed={}, alreadyClosed={}, failed={}, consumptions={}",
                organizationId, periodKey, closed, already, failed, consumptions);
        return new CloseResult(closed, already, failed, consumptions);
    }

    /**
     * 割当ごとの締めは先頭の期間から途切れない（CMP-260909-2152 再検分 P2-2）。途中の期間が締まらなかった割当は、同じ実行で
     * 後続の期間へ進まない。進むと最終期間だけが CLOSED になり、日次バッチの候補（最終期間の締めの有無で判定）から外れて
     * 途中の期間の PLANNED が自動で回収されなくなる。
     */
    private static List<ShiftBudgetAllocationEntity> continuing(List<ShiftBudgetAllocationEntity> allocations,
                                                                Set<Long> interrupted) {
        if (interrupted.isEmpty()) {
            return allocations;
        }
        return allocations.stream().filter(a -> !interrupted.contains(a.getId())).toList();
    }

    /** CLOSED／既に締め済み以外（失敗・中止・割当消滅）なら、その割当の後続期間を止める。 */
    private static void markIfInterrupted(Set<Long> interrupted, ShiftBudgetAllocationEntity allocation, AllocationResult r) {
        if (r.kind() != AllocationResult.Kind.CLOSED && r.kind() != AllocationResult.Kind.ALREADY_CLOSED) {
            interrupted.add(allocation.getId());
        }
    }

    private static boolean isClosed(Map<Long, Map<String, ShiftBudgetCloseEventState>> events,
                                    ShiftBudgetAllocationEntity allocation, YearMonth key) {
        return events.getOrDefault(allocation.getId(), Map.of()).get(key.toString()) == ShiftBudgetCloseEventState.CLOSED;
    }

    private static void addIfOpen(TreeMap<YearMonth, List<ShiftBudgetAllocationEntity>> byKey,
                                  Map<Long, Map<String, ShiftBudgetCloseEventState>> events,
                                  ShiftBudgetAllocationEntity allocation, YearMonth key) {
        if (isClosed(events, allocation, key)) {
            return;
        }
        byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(allocation);
    }

    private Map<Long, Map<String, ShiftBudgetCloseEventState>> activeEventStates(List<ShiftBudgetAllocationEntity> allocations) {
        Map<Long, Map<String, ShiftBudgetCloseEventState>> map = new HashMap<>();
        List<Long> ids = allocations.stream().map(ShiftBudgetAllocationEntity::getId).toList();
        for (ShiftBudgetMonthlyCloseEventRepository.EventKeyRow row : eventRepository.findActiveKeys(ids)) {
            map.computeIfAbsent(row.getAllocationId(), k -> new HashMap<>()).put(row.getTargetMonth(), row.getState());
        }
        return map;
    }

    // ───────────────────────── 日次バッチ（1組織） ─────────────────────────

    private record OrganizationBatch(int keys, int closed, int failed) {
    }

    private OrganizationBatch processOrganizationBatch(Long organizationId, List<ShiftBudgetAllocationEntity> allocations,
                                                       LocalDate yesterday) {
        Function<YearMonth, Integer> closingDay = periodResolver.closingDayFunction(organizationId);
        // 終了済みの最新期間キー（期間終了日が昨日以前）
        YearMonth lastEnded = ShiftBudgetClosePeriodCalculator.periodKeyOf(yesterday, closingDay);
        if (ShiftBudgetClosePeriodCalculator.period(lastEnded, closingDay).end().isAfter(yesterday)) {
            lastEnded = lastEnded.minusMonths(1);
        }
        Map<Long, Map<String, ShiftBudgetCloseEventState>> events = activeEventStates(allocations);
        Set<String> conflicts = journalConflictKeys(allocations);

        TreeMap<YearMonth, List<ShiftBudgetAllocationEntity>> byKey = new TreeMap<>();
        for (ShiftBudgetAllocationEntity a : allocations) {
            YearMonth first = ShiftBudgetClosePeriodCalculator.periodKeyOf(a.getPeriodStart(), closingDay);
            YearMonth lastOfAllocation = ShiftBudgetClosePeriodCalculator.periodKeyOf(a.getPeriodEnd(), closingDay);
            YearMonth last = lastOfAllocation.isAfter(lastEnded) ? lastEnded : lastOfAllocation;
            for (YearMonth k = first; !k.isAfter(last); k = k.plusMonths(1)) {
                if (conflicts.contains(a.getId() + ":" + k)
                        && events.getOrDefault(a.getId(), Map.of()).get(k.toString()) != ShiftBudgetCloseEventState.CLOSED) {
                    // 仕訳衝突の期間は自動で締め直さない。締めは先頭から途切れないので、後続の期間へも進まない（手動で解消後に再開）
                    break;
                }
                addIfOpen(byKey, events, a, k);
            }
        }
        int keys = 0;
        int closed = 0;
        int failed = 0;
        Set<Long> interrupted = new HashSet<>();
        for (Map.Entry<YearMonth, List<ShiftBudgetAllocationEntity>> entry : byKey.entrySet()) {
            if (keys >= MAX_KEYS_PER_ORGANIZATION) {
                break;
            }
            List<ShiftBudgetAllocationEntity> targets = continuing(entry.getValue(), interrupted);
            if (targets.isEmpty()) {
                continue;
            }
            keys++;
            ShiftBudgetClosePeriodResolver.ResolvedPeriod period = ensureFixed(organizationId, entry.getKey(), yesterday);
            int keyClosed = 0;
            int keyFailed = 0;
            int keyConsumptions = 0;
            for (ShiftBudgetAllocationEntity allocation : targets) {
                AllocationResult r = closeOne(allocation, entry.getKey(), period);
                markIfInterrupted(interrupted, allocation, r);
                if (r.kind() == AllocationResult.Kind.CLOSED) {
                    keyClosed++;
                    keyConsumptions += r.lines();
                } else if (r.kind() == AllocationResult.Kind.FAILED) {
                    keyFailed++;
                }
            }
            closed += keyClosed;
            failed += keyFailed;
            if (keyClosed > 0) {
                auditBatchClose(organizationId, period, keyClosed, keyFailed, keyConsumptions);
            }
        }
        return new OrganizationBatch(keys, closed, failed);
    }

    /** 仕訳衝突（JOURNAL_CONFLICT）が起きた割当×期間キー（"割当ID:期間キー"）。日次バッチは自動で締め直さない。 */
    private Set<String> journalConflictKeys(List<ShiftBudgetAllocationEntity> allocations) {
        Set<String> keys = new HashSet<>();
        List<Long> ids = allocations.stream().map(ShiftBudgetAllocationEntity::getId).toList();
        for (Object[] row : failedEventRepository.findJournalConflictKeys(ids)) {
            keys.add(((Number) row[0]).longValue() + ":" + row[1]);
        }
        return keys;
    }

    // ───────────────────────── 割当×期間の3段締め ─────────────────────────

    private record AllocationResult(Kind kind, int lines) {
        enum Kind {
            CLOSED,
            ALREADY_CLOSED,
            FAILED,
            SKIPPED
        }
    }

    private AllocationResult closeOne(ShiftBudgetAllocationEntity allocation, YearMonth key,
                                      ShiftBudgetClosePeriodResolver.ResolvedPeriod period) {
        Long allocationId = allocation.getId();
        UUID eventId = null;
        try {
            // 段1 Prepare
            ShiftBudgetCloseStageService.PrepareResult prepared = ShiftBudgetDeadlockRetry.run("prepare",
                    () -> stage.prepare(allocationId, key));
            if (prepared == null) {
                return new AllocationResult(AllocationResult.Kind.SKIPPED, 0);
            }
            eventId = prepared.closeEventId();
            hooks.afterPrepared(allocationId, eventId);

            // 段2 Post（budget TX）。年度・判定行の保存済み判定を受け取る
            ShiftBudgetMonthlyPostCommand command = postCommand(allocation, prepared, period);
            ShiftBudgetMonthlyPostResult post = ShiftBudgetDeadlockRetry.run("post", () -> journalService.post(command));
            hooks.afterPosted(allocationId, eventId, post.outcome().name());

            if (post.outcome() == ShiftBudgetMonthlyPostResult.Outcome.POSTED) {
                // 段3a Commit
                final UUID committing = eventId;
                ShiftBudgetDeadlockRetry.runVoid("commit", () -> stage.commit(committing, post.journalTransactionId()));
                replayDeferrals(eventId);
                return new AllocationResult(AllocationResult.Kind.CLOSED, prepared.lineCount());
            }
            // 段3b Abort（判定 ABORTED のみ）
            final UUID aborting = eventId;
            ShiftBudgetDeadlockRetry.runVoid("abort", () -> stage.abort(aborting, post.reason()));
            String operation = "FY_CLOSED".equals(post.reason()) ? "POST_FY_CLOSED" : "JOURNAL_CONFLICT";
            recordFailure(allocation.getOrganizationId(), allocationId, operation, key, eventId,
                    "締めを中止しました: reason=" + post.reason());
            replayDeferrals(eventId);
            return new AllocationResult(AllocationResult.Kind.FAILED, 0);
        } catch (BusinessException e) {
            if (ShiftBudgetErrorCode.MONTHLY_ALREADY_CLOSED.getCode().equals(e.getErrorCode().getCode())) {
                log.info("F08.7 期間締め: 既に締め済みのためスキップ: allocId={}, key={}", allocationId, key);
                return new AllocationResult(AllocationResult.Kind.ALREADY_CLOSED, 0);
            }
            recordFailure(allocation.getOrganizationId(), allocationId, "CLOSE_FAILED", key, eventId,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return new AllocationResult(AllocationResult.Kind.FAILED, 0);
        } catch (ShiftBudgetCloseMismatchException e) {
            log.error("F08.7 期間締め: Commit の件数・合計が一致しません（Commit をロールバック。手作業で復旧）: allocId={}, key={}",
                    allocationId, key, e);
            recordFailure(allocation.getOrganizationId(), allocationId, "COMMIT_MISMATCH", key, eventId, e.getMessage());
            return new AllocationResult(AllocationResult.Kind.FAILED, 0);
        } catch (Exception e) {
            log.error("F08.7 期間締め: 割当の締めに失敗（他の割当は続行）: allocId={}, key={}", allocationId, key, e);
            recordFailure(allocation.getOrganizationId(), allocationId, "CLOSE_FAILED", key, eventId,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return new AllocationResult(AllocationResult.Kind.FAILED, 0);
        }
    }

    /** 期間行の確定（独立 TX）。デッドロックの犠牲は TX の外で再実行する。 */
    private ShiftBudgetClosePeriodResolver.ResolvedPeriod ensureFixed(Long organizationId, YearMonth key,
                                                                      LocalDate latestEnd) {
        hooks.beforeEnsureFixed(organizationId, key);
        // 終了判定の後に締め日変更が割り込みうるので、確定 TX の先頭行ロックの下で終了日を再検証する（未終了なら 036）
        return ShiftBudgetDeadlockRetry.run("ensureFixed",
                () -> periodResolver.ensureFixed(organizationId, key, latestEnd));
    }

    private ShiftBudgetMonthlyPostCommand postCommand(ShiftBudgetAllocationEntity allocation,
                                                      ShiftBudgetCloseStageService.PrepareResult prepared,
                                                      ShiftBudgetClosePeriodResolver.ResolvedPeriod period) {
        String scopeType;
        Long scopeId;
        if (allocation.getTeamId() != null) {
            scopeType = "TEAM";
            scopeId = allocation.getTeamId();
        } else {
            scopeType = "ORGANIZATION";
            scopeId = allocation.getOrganizationId();
        }
        Long recordedBy = ShiftBudgetCloseActorContext.current().userId();
        if (recordedBy == null) {
            recordedBy = allocation.getCreatedBy();
        }
        return new ShiftBudgetMonthlyPostCommand(prepared.closeEventId(), allocation.getId(), allocation.getFiscalYearId(),
                allocation.getBudgetCategoryId(), scopeType, scopeId, prepared.plannedTotal(), prepared.journalDate(),
                period.start(), period.end(), recordedBy);
    }

    /** Commit／Abort 後に、そのイベントを理由に遅延していたシフトを古い順に1回ずつ再実行する（TX の外）。 */
    private void replayDeferrals(UUID eventId) {
        try {
            for (byte[] raw : deferralRepository.findPendingIdsByEvent(UuidBytes.toBytes(eventId))) {
                deferralService.replay(UuidBytes.fromBytes(raw), UUID.randomUUID().toString());
            }
        } catch (Exception e) {
            log.error("F08.7 期間締め: 遅延記録の再実行に失敗（回収バッチに任せる）: eventId={}", eventId, e);
        }
    }

    /**
     * バッチ経路で成功した締め（1件以上 CLOSED にした組織×期間）を監査ログ {@code SHIFT_BUDGET_MONTHLY_CLOSED} に記録する
     * （CMP-260909-2152 α 検分 P2-2。F08.7 §9.1「月次締めバッチ完了時」）。段取り役は TX を持たないので業務 TX の外で記録される。
     * 手動締め（API #11）は Facade が記録するため、手動入口からは呼ばない（二重記録しない）。監査の失敗で締めの結果を変えない。
     */
    private void auditBatchClose(Long organizationId, ShiftBudgetClosePeriodResolver.ResolvedPeriod period, int closed,
                                 int failed, int consumptions) {
        try {
            auditLogService.record("SHIFT_BUDGET_MONTHLY_CLOSED", null, null, null, organizationId, null, null, null,
                    String.format("{\"trigger\":\"BATCH\",\"period_key\":\"%s\",\"period_start\":\"%s\",\"period_end\":\"%s\","
                                    + "\"closed_allocations\":%d,\"failed_allocations\":%d,\"closed_consumption_count\":%d}",
                            period.periodKey(), period.start(), period.end(), closed, failed, consumptions));
        } catch (Exception e) {
            log.error("F08.7 期間締め: 監査ログの記録に失敗（締めは確定済み）: orgId={}, key={}", organizationId,
                    period.periodKey(), e);
        }
    }

    /** 失敗イベント（MONTHLY_CLOSE。payload.operation で種別を表す。自動再試行の対象外）を例外安全に残す。 */
    private void recordFailure(Long organizationId, Long allocationId, String operation, YearMonth key, UUID eventId,
                               String message) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("operation", operation);
            if (allocationId != null) {
                payload.put("allocation_id", allocationId);
            }
            if (key != null) {
                payload.put("period_key", key.toString());
                payload.put("year_month", key.toString());
            }
            if (eventId != null) {
                payload.put("close_event_id", eventId.toString());
            }
            failedEventService.recordFailure(organizationId, ShiftBudgetFailedEventType.MONTHLY_CLOSE, allocationId, payload,
                    message);
        } catch (Exception e) {
            log.error("F08.7 期間締め: 失敗イベントの記録に失敗: orgId={}, operation={}", organizationId, operation, e);
        }
    }

    /**
     * 1組織の締め結果。
     *
     * @param closedAllocations        今回 CLOSED にした割当×期間の件数
     * @param alreadyClosedAllocations 指定期間キーが既に締め済みだった割当の件数（処理対象からは除外した分）
     * @param failedAllocations        失敗（ABORTED・例外）件数
     * @param closedConsumptions       CONFIRMED にした消化件数
     */
    public record CloseResult(int closedAllocations, int alreadyClosedAllocations, int failedAllocations,
                              int closedConsumptions) {
    }

    /**
     * 日次バッチの結果。
     *
     * @param processedOrganizationIds 締めを行った組織
     * @param closedPeriodKeys         締めた期間キー数（組織合算）
     * @param closedAllocations        CLOSED にした割当×期間の件数
     * @param failedAllocations        失敗件数
     */
    public record DailyCloseResult(List<Long> processedOrganizationIds, int closedPeriodKeys,
                                   int closedAllocations, int failedAllocations) {
    }
}
