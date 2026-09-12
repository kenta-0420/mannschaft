package com.mannschaft.app.shiftbudget.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.event.ShiftPublishedEvent;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.event.HourlyRateMissingEvent;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetConsumptionService;
import com.mannschaft.app.shiftbudget.service.ThresholdAlertEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftBudgetConsumptionRecordListener} 単体テスト（Phase 9-β）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>フィーチャーフラグ OFF → early return（消化記録なし）</li>
 *   <li>当月 allocation 不在 → WARN+no-op (Q3 御裁可)</li>
 *   <li>正常系: 全 (slot,user) を recordSingleConsumption で処理</li>
 *   <li>個別例外 (CONFIRMED 既存) → スキップして残りを継続</li>
 *   <li>致命的エラー → 例外を握りつぶして監査ログ</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftBudgetConsumptionRecordListener 単体テスト")
class ShiftBudgetPublishedEventListenerTest {

    private static final Long SCHEDULE_ID = 5L;
    private static final Long TEAM_ID = 12L;
    private static final Long ORG_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long ALLOCATION_ID = 42L;

    @Mock
    private ShiftBudgetFeatureService featureService;
    @Mock
    private ShiftBudgetAllocationRepository allocationRepository;
    @Mock
    private ShiftBudgetRateQueryRepository rateQueryRepository;
    @Mock
    private ShiftBudgetConsumptionService consumptionService;
    @Mock
    private ShiftSlotRepository slotRepository;
    @Mock
    private ShiftHourlyRateRepository hourlyRateRepository;
    @Mock
    private AuditLogService auditLogService;
    /** Phase 9-δ で追加された閾値判定 hook（テスト中は no-op で十分） */
    @Mock
    private ThresholdAlertEvaluationService thresholdAlertEvaluationService;
    /** Phase 10-β で追加された失敗イベント記録（テスト中は no-op で十分） */
    @Mock
    private com.mannschaft.app.shiftbudget.service.ShiftBudgetFailedEventService failedEventService;
    /** CMP-260910-1555: 時給未設定通知をコミット後へ渡すイベント発行口。 */
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ShiftBudgetConsumptionRecordListener listener;

    @BeforeEach
    void setUp() {
        listener = new ShiftBudgetConsumptionRecordListener(
                featureService, allocationRepository, rateQueryRepository,
                consumptionService, slotRepository, hourlyRateRepository,
                auditLogService, objectMapper, thresholdAlertEvaluationService, failedEventService,
                applicationEventPublisher);
    }

    private ShiftSlotEntity sampleSlotWithUser(Long slotId, Long userId) {
        try {
            String userIdsJson = objectMapper.writeValueAsString(List.of(userId));
            ShiftSlotEntity slot = ShiftSlotEntity.builder()
                    .scheduleId(SCHEDULE_ID)
                    .slotDate(LocalDate.of(2026, 6, 15))
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(13, 0))
                    .requiredCount(1)
                    .assignedUserIds(userIdsJson)
                    .build();
            // BaseEntity#id は通常 DB 採番なので、テストでは reflection で注入
            java.lang.reflect.Field idField = slot.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(slot, slotId);
            return slot;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ShiftBudgetAllocationEntity sampleAllocation() {
        return ShiftBudgetAllocationEntity.builder()
                .organizationId(ORG_ID)
                .teamId(TEAM_ID)
                .fiscalYearId(3L)
                .budgetCategoryId(17L)
                .periodStart(LocalDate.of(2026, 6, 1))
                .periodEnd(LocalDate.of(2026, 6, 30))
                .allocatedAmount(new BigDecimal("300000"))
                .consumedAmount(BigDecimal.ZERO)
                .confirmedAmount(BigDecimal.ZERO)
                .currency("JPY")
                .createdBy(1L)
                .version(0L)
                .build();
    }

    @Test
    @DisplayName("フィーチャーフラグ OFF → 何もしない (Listener early return)")
    void フラグOFF_no_op() {
        given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_ID)).willReturn(Optional.of(ORG_ID));
        given(featureService.isEnabled(ORG_ID)).willReturn(false);

        listener.onShiftPublished(new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, USER_ID, java.time.LocalDateTime.now()));

        verify(slotRepository, never()).findByScheduleIdOrderBySlotDateAscStartTimeAsc(anyLong());
        verify(consumptionService, never()).recordSingleConsumption(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("organization_id 解決不可 → 何もしない")
    void org解決不可_no_op() {
        given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_ID)).willReturn(Optional.empty());

        listener.onShiftPublished(new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, USER_ID, java.time.LocalDateTime.now()));

        verify(featureService, never()).isEnabled(any());
        verify(consumptionService, never()).recordSingleConsumption(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("当月 allocation 不在 → WARN+no-op (Q3 御裁可)")
    void allocation不在_no_op() {
        given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_ID)).willReturn(Optional.of(ORG_ID));
        given(featureService.isEnabled(ORG_ID)).willReturn(true);
        ShiftSlotEntity slot = sampleSlotWithUser(7L, USER_ID);
        given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(SCHEDULE_ID))
                .willReturn(List.of(slot));
        given(allocationRepository.findContainingPeriod(eq(ORG_ID), eq(TEAM_ID), any()))
                .willReturn(Optional.empty());
        given(allocationRepository.findContainingPeriod(eq(ORG_ID), eq(null), any()))
                .willReturn(Optional.empty());

        listener.onShiftPublished(new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, USER_ID, java.time.LocalDateTime.now()));

        // 消化記録は呼ばない（no-op）
        verify(consumptionService, never()).recordSingleConsumption(any(), any(), any(), any(), any(), any());
        // 監査ログには 0/1 件として記録される
        verify(auditLogService).record(eq("SHIFT_BUDGET_CONSUMPTION_RECORDED"),
                eq(USER_ID), eq(null), eq(TEAM_ID), eq(ORG_ID),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("正常系: 各 (slot,user) で recordSingleConsumption を呼ぶ")
    void 正常系_記録呼出() {
        given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_ID)).willReturn(Optional.of(ORG_ID));
        given(featureService.isEnabled(ORG_ID)).willReturn(true);
        ShiftSlotEntity slot = sampleSlotWithUser(7L, USER_ID);
        given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(SCHEDULE_ID))
                .willReturn(List.of(slot));
        ShiftBudgetAllocationEntity alloc = sampleAllocation();
        // findContainingPeriod は entity に id が無いとアトミック呼び出しで NPE するため reflection で id 設定
        try {
            java.lang.reflect.Field idField = alloc.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(alloc, ALLOCATION_ID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        given(allocationRepository.findContainingPeriod(eq(ORG_ID), eq(TEAM_ID), any()))
                .willReturn(Optional.of(alloc));

        ShiftHourlyRateEntity rate = ShiftHourlyRateEntity.builder()
                .userId(USER_ID).teamId(TEAM_ID)
                .hourlyRate(new BigDecimal("1200"))
                .effectiveFrom(LocalDate.of(2026, 1, 1)).build();
        given(hourlyRateRepository.findEffectiveRate(eq(USER_ID), eq(TEAM_ID), any()))
                .willReturn(Optional.of(rate));

        listener.onShiftPublished(new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, USER_ID, java.time.LocalDateTime.now()));

        verify(consumptionService, times(1)).recordSingleConsumption(
                eq(ALLOCATION_ID), eq(SCHEDULE_ID), eq(7L), eq(USER_ID),
                eq(new BigDecimal("1200")), any());
        verify(auditLogService).record(eq("SHIFT_BUDGET_CONSUMPTION_RECORDED"),
                eq(USER_ID), eq(null), eq(TEAM_ID), eq(ORG_ID),
                any(), any(), any(), any());
        // Phase 9-δ 追加: 各 (slot,user) PLANNED INSERT 後に閾値判定が呼ばれる
        verify(thresholdAlertEvaluationService, times(1)).evaluateAndTrigger(ALLOCATION_ID);
    }

    @Test
    @DisplayName("個別 (slot,user) が IllegalStateException → 残り継続")
    void 個別エラー_継続() {
        given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_ID)).willReturn(Optional.of(ORG_ID));
        given(featureService.isEnabled(ORG_ID)).willReturn(true);
        ShiftSlotEntity slot = sampleSlotWithUser(7L, USER_ID);
        given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(SCHEDULE_ID))
                .willReturn(List.of(slot));
        ShiftBudgetAllocationEntity alloc = sampleAllocation();
        try {
            java.lang.reflect.Field idField = alloc.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(alloc, ALLOCATION_ID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        given(allocationRepository.findContainingPeriod(eq(ORG_ID), eq(TEAM_ID), any()))
                .willReturn(Optional.of(alloc));
        // CMP-260910-1555: 時給未設定は消化記録の手前で skip されるようになったため、
        // 「記録処理そのものが例外を投げても残りを継続する」ことを試すには時給が引ける必要がある。
        given(hourlyRateRepository.findEffectiveRate(any(), any(), any()))
                .willReturn(Optional.of(ShiftHourlyRateEntity.builder()
                        .userId(USER_ID).teamId(TEAM_ID)
                        .hourlyRate(new BigDecimal("1000"))
                        .effectiveFrom(LocalDate.of(2026, 1, 1)).build()));

        org.mockito.BDDMockito.willThrow(new IllegalStateException("CONFIRMED record exists"))
                .given(consumptionService).recordSingleConsumption(
                        any(), any(), any(), any(), any(), any());

        // 例外を握って監査ログ完走することを確認
        listener.onShiftPublished(new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, USER_ID, java.time.LocalDateTime.now()));

        verify(auditLogService).record(eq("SHIFT_BUDGET_CONSUMPTION_RECORDED"),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** id を設定済みの allocation を返す（findContainingPeriod のスタブ用）。 */
    private ShiftBudgetAllocationEntity allocationWithId() {
        ShiftBudgetAllocationEntity alloc = sampleAllocation();
        try {
            java.lang.reflect.Field idField = alloc.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(alloc, ALLOCATION_ID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return alloc;
    }

    /** 時給未設定シナリオの共通スタブ（org 解決 → フラグ ON → slot 1 件 → allocation あり → 時給なし）。 */
    private void givenMissingHourlyRate() {
        given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_ID)).willReturn(Optional.of(ORG_ID));
        given(featureService.isEnabled(ORG_ID)).willReturn(true);
        given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(SCHEDULE_ID))
                .willReturn(List.of(sampleSlotWithUser(7L, USER_ID)));
        given(allocationRepository.findContainingPeriod(eq(ORG_ID), eq(TEAM_ID), any()))
                .willReturn(Optional.of(allocationWithId()));
        given(hourlyRateRepository.findEffectiveRate(eq(USER_ID), eq(TEAM_ID), any()))
                .willReturn(Optional.empty());
    }

    @Test
    @DisplayName("CMP-260910-1555: 時給未設定 → 0 円で記録せず、消化記録をスキップする")
    void 時給未設定_0円で記録しない() {
        givenMissingHourlyRate();
        given(rateQueryRepository.findTeamSlugByTeamId(TEAM_ID)).willReturn(Optional.of("team-alpha"));

        listener.onShiftPublished(new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, USER_ID, java.time.LocalDateTime.now()));

        // 是正前は hourlyRate=0 で記録が「成功」していた。0 円の消化行を作ってはならない。
        verify(consumptionService, never()).recordSingleConsumption(
                any(), any(), any(), any(), any(), any());
        // 記録していない以上、閾値判定を呼ぶ意味も無い（0% のまま再評価しても何も起きない）
        verify(thresholdAlertEvaluationService, never()).evaluateAndTrigger(any());
    }

    @Test
    @DisplayName("CMP-260910-1555: 時給未設定 → 予算管理者へ警告通知が出る（黙って成功扱いにしない）")
    void 時給未設定_管理者へ通知() {
        givenMissingHourlyRate();
        given(rateQueryRepository.findTeamSlugByTeamId(TEAM_ID)).willReturn(Optional.of("team-alpha"));

        listener.onShiftPublished(new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, USER_ID, java.time.LocalDateTime.now()));

        verify(applicationEventPublisher).publishEvent(new HourlyRateMissingEvent(
                ORG_ID, TEAM_ID, "team-alpha", SCHEDULE_ID, List.of(USER_ID)));
    }

    @Test
    @DisplayName("CMP-260910-1555: 時給が引けたときは通知を出さない（誤報を出さない）")
    void 時給設定済_通知しない() {
        given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_ID)).willReturn(Optional.of(ORG_ID));
        given(featureService.isEnabled(ORG_ID)).willReturn(true);
        given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(SCHEDULE_ID))
                .willReturn(List.of(sampleSlotWithUser(7L, USER_ID)));
        given(allocationRepository.findContainingPeriod(eq(ORG_ID), eq(TEAM_ID), any()))
                .willReturn(Optional.of(allocationWithId()));
        given(hourlyRateRepository.findEffectiveRate(eq(USER_ID), eq(TEAM_ID), any()))
                .willReturn(Optional.of(ShiftHourlyRateEntity.builder()
                        .userId(USER_ID).teamId(TEAM_ID)
                        .hourlyRate(new BigDecimal("1200"))
                        .effectiveFrom(LocalDate.of(2026, 1, 1)).build()));

        listener.onShiftPublished(new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, USER_ID, java.time.LocalDateTime.now()));

        verify(applicationEventPublisher, never()).publishEvent(any(HourlyRateMissingEvent.class));
    }

    @Test
    @DisplayName("CMP-260910-1555: 通知が失敗しても監査ログは残り hook は完走する")
    void 通知失敗でも完走() {
        givenMissingHourlyRate();
        given(rateQueryRepository.findTeamSlugByTeamId(TEAM_ID)).willReturn(Optional.empty());
        org.mockito.BDDMockito.willThrow(new RuntimeException("notification down"))
                .given(applicationEventPublisher).publishEvent(any(HourlyRateMissingEvent.class));

        listener.onShiftPublished(new ShiftPublishedEvent(SCHEDULE_ID, TEAM_ID, USER_ID, java.time.LocalDateTime.now()));

        // 通知の失敗を hook 全体の致命的失敗に昇格させない（消化記録の成否とは別問題）
        verify(auditLogService).record(eq("SHIFT_BUDGET_CONSUMPTION_RECORDED"),
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(auditLogService, never()).record(eq("SHIFT_BUDGET_CONSUMPTION_FAILED"),
                any(), any(), any(), any(), any(), any(), any(), any());
    }
}
