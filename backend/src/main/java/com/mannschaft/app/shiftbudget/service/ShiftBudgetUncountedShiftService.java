package com.mannschaft.app.shiftbudget.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.budget.BudgetErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetCloseEventState;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetMonthlyCloseEventRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 割当の締め期間一覧・未計上勤務（締め済み期間で枠×人が計上されていない勤務）の読み取り（CMP-260909-2152 確定設計 r12 §3・§5.4）。
 * 認可は Facade の責務（本クラスは認可を持たない）。
 *
 * <p>未計上候補は、締め済み期間（CLOSED イベントのある期間キー）と重なる公開済みシフトの枠のうち、割当を問わず生存 PLANNED／CONFIRMED の
 * 消化行が無い (slot, user)。再公開で追加された担当者・日付が締め済み期間へ移動して取消された勤務がここに現れる。
 * 未締め（CLOSED イベントなし。PREPARED を含む）の期間キーは 409 BUDGET_024（訂正系の「未締め」と同じ扱い）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftBudgetUncountedShiftService {

    private final ShiftBudgetAllocationRepository allocationRepository;
    private final ShiftBudgetMonthlyCloseEventRepository eventRepository;
    private final ShiftBudgetConsumptionRepository consumptionRepository;
    private final ShiftBudgetRateQueryRepository rateQueryRepository;
    private final ShiftBudgetClosePeriodResolver periodResolver;
    private final ObjectMapper objectMapper;

    /**
     * 未計上の勤務。
     *
     * @param slotId     勤務枠 ID
     * @param userId     担当者
     * @param workDate   勤務日
     * @param scheduleId シフトスケジュール ID
     */
    public record UncountedShift(Long slotId, Long userId, LocalDate workDate, Long scheduleId) {
    }

    /** 割当と重なる締め期間の一覧（他組織・不在の割当は 404）。 */
    @Transactional(readOnly = true)
    public List<ShiftBudgetClosePeriodResolver.ResolvedPeriod> periodsOfAllocation(Long organizationId, Long allocationId) {
        ShiftBudgetAllocationEntity allocation = findAllocation(organizationId, allocationId);
        java.util.function.Function<YearMonth, Integer> closingDay = periodResolver.closingDayFunction(organizationId);
        YearMonth first = ShiftBudgetClosePeriodCalculator.periodKeyOf(allocation.getPeriodStart(), closingDay);
        YearMonth last = ShiftBudgetClosePeriodCalculator.periodKeyOf(allocation.getPeriodEnd(), closingDay);
        return periodResolver.list(organizationId, first, last);
    }

    /** 締め済み期間の未計上勤務。 */
    @Transactional(readOnly = true)
    public List<UncountedShift> list(Long organizationId, Long allocationId, YearMonth periodKey) {
        ShiftBudgetAllocationEntity allocation = findAllocation(organizationId, allocationId);
        boolean closed = eventRepository.findActiveKeys(List.of(allocationId)).stream()
                .anyMatch(r -> r.getTargetMonth().equals(periodKey.toString())
                        && r.getState() == ShiftBudgetCloseEventState.CLOSED);
        if (!closed) {
            throw new BusinessException(BudgetErrorCode.BUDGET_024);
        }
        ShiftBudgetClosePeriodResolver.ResolvedPeriod period = periodResolver.resolve(organizationId, periodKey);
        LocalDate from = period.start().isAfter(allocation.getPeriodStart()) ? period.start() : allocation.getPeriodStart();
        LocalDate to = period.end().isBefore(allocation.getPeriodEnd()) ? period.end() : allocation.getPeriodEnd();
        if (from.isAfter(to)) {
            return List.of();
        }

        List<Object[]> slots = allocation.getTeamId() != null
                ? rateQueryRepository.findPublishedSlotsForTeam(allocation.getTeamId(), from, to)
                : rateQueryRepository.findPublishedSlotsForOrganization(allocation.getOrganizationId(), from, to);
        List<Long> slotIds = slots.stream().map(r -> ((Number) r[0]).longValue()).toList();
        Set<String> counted = new HashSet<>();
        if (!slotIds.isEmpty()) {
            for (ShiftBudgetConsumptionRepository.SlotUserRow row : consumptionRepository.findLiveSlotUsers(slotIds)) {
                counted.add(row.getSlotId() + ":" + row.getUserId());
            }
        }
        List<UncountedShift> items = new ArrayList<>();
        for (Object[] r : slots) {
            Long slotId = ((Number) r[0]).longValue();
            LocalDate workDate = ShiftBudgetCloseStageService.toLocalDate(r[1]);
            Long scheduleId = ((Number) r[3]).longValue();
            for (Long userId : parseUsers(r[2])) {
                if (!counted.contains(slotId + ":" + userId)) {
                    items.add(new UncountedShift(slotId, userId, workDate, scheduleId));
                }
            }
        }
        items.sort(Comparator.comparing(UncountedShift::workDate).thenComparing(UncountedShift::slotId)
                .thenComparing(UncountedShift::userId));
        return items;
    }

    private ShiftBudgetAllocationEntity findAllocation(Long organizationId, Long allocationId) {
        return allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(allocationId, organizationId)
                .orElseThrow(() -> new BusinessException(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND));
    }

    private List<Long> parseUsers(Object json) {
        if (json == null || String.valueOf(json).isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(String.valueOf(json), new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            log.warn("F08.7 未計上勤務: assigned_user_ids デシリアライズ失敗: json={}", json, e);
            return List.of();
        }
    }
}
