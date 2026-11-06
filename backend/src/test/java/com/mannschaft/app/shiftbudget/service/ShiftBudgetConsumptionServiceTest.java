package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.shiftbudget.ShiftBudgetCancelReason;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftBudgetConsumptionService} 単体テスト（Phase 9-β / 設計書 §11.1）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>新規 INSERT: PLANNED 行作成 + アトミック加算</li>
 *   <li>既存 PLANNED → CANCELLED 遷移 + 減算 → 新規 INSERT + 加算 (再公開シナリオ)</li>
 *   <li>CONFIRMED 残存 → IllegalStateException</li>
 *   <li>シフトアーカイブ時の一括 CANCEL</li>
 *   <li>深夜跨ぎ時間計算</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftBudgetConsumptionService 単体テスト")
class ShiftBudgetConsumptionServiceTest {

    private static final Clock FIXED_WALL_CLOCK = Clock.fixed(
            Instant.parse("2026-11-04T00:00:00Z"), UserZoneLocalDateTimeParser.SERVER_ZONE);

    private static final Long ALLOCATION_ID = 42L;
    private static final Long SHIFT_ID = 5L;
    private static final Long SLOT_ID = 7L;
    private static final Long USER_ID = 100L;

    @Mock
    private ShiftBudgetConsumptionRepository consumptionRepository;
    @Mock
    private ShiftBudgetAllocationRepository allocationRepository;

    @Mock
    private com.mannschaft.app.shiftbudget.port.ShiftBudgetPublicationPort publicationPort;

    private ShiftBudgetConsumptionService service;

    @BeforeEach
    void setUp() {
        service = new ShiftBudgetConsumptionService(
                consumptionRepository, allocationRepository, publicationPort, FIXED_WALL_CLOCK);
    }

    @Nested
    @DisplayName("recordSingleConsumption (§11.1 擬似コード)")
    class RecordSingleConsumption {

        @BeforeEach
        void 公開世代0が有効() {
            given(publicationPort.lockPublication(SHIFT_ID)).willReturn(Optional.of(
                    new com.mannschaft.app.shiftbudget.port.ShiftBudgetPublicationPort.PublicationState(0L, true)));
        }

        @Test
        @DisplayName("既存なし → 新規 PLANNED INSERT + アトミック加算")
        void 新規_INSERT() {
            given(allocationRepository.incrementConsumedAmount(any(), any())).willReturn(1);
            given(consumptionRepository.findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                    SLOT_ID, USER_ID, ShiftBudgetConsumptionStatus.PLANNED))
                    .willReturn(Optional.empty());
            given(consumptionRepository.findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                    SLOT_ID, USER_ID, ShiftBudgetConsumptionStatus.CONFIRMED))
                    .willReturn(Optional.empty());

            service.recordSingleConsumption(ALLOCATION_ID, SHIFT_ID, SLOT_ID, USER_ID,
                    new BigDecimal("1200"), new BigDecimal("4.0"), 0L);

            ArgumentCaptor<ShiftBudgetConsumptionEntity> capt =
                    ArgumentCaptor.forClass(ShiftBudgetConsumptionEntity.class);
            verify(consumptionRepository).save(capt.capture());
            assertThat(capt.getValue().getStatus()).isEqualTo(ShiftBudgetConsumptionStatus.PLANNED);
            assertThat(capt.getValue().getAmount()).isEqualByComparingTo("4800.00");

            verify(allocationRepository).incrementConsumedAmount(eq(ALLOCATION_ID),
                    eq(new BigDecimal("4800.00")));
            verify(allocationRepository, never()).decrementConsumedAmount(any(), any());
        }

        @Test
        @DisplayName("同世代の既存 PLANNED は取消・再加算せず no-op")
        void 同世代の再配送は変更しない() {
            var existing = ShiftBudgetConsumptionEntity.builder().publicationGeneration(0L).build();
            given(consumptionRepository.findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                    SLOT_ID, USER_ID, ShiftBudgetConsumptionStatus.PLANNED)).willReturn(Optional.of(existing));
            assertThat(service.recordSingleConsumption(ALLOCATION_ID, SHIFT_ID, SLOT_ID, USER_ID,
                    new BigDecimal("1200"), new BigDecimal("4.0"), 0L)).isFalse();
            verify(consumptionRepository, never()).save(any());
            verify(allocationRepository, never()).incrementConsumedAmount(any(), any());
            verify(allocationRepository, never()).decrementConsumedAmount(any(), any());
        }

        @Test
        @DisplayName("CONFIRMED 既存 → IllegalStateException (新規 INSERT も加算もしない)")
        void confirmed既存_例外() {
            ShiftBudgetConsumptionEntity confirmed = ShiftBudgetConsumptionEntity.builder()
                    .allocationId(ALLOCATION_ID)
                    .shiftId(SHIFT_ID)
                    .slotId(SLOT_ID)
                    .userId(USER_ID)
                    .hourlyRateSnapshot(new BigDecimal("1000"))
                    .hours(new BigDecimal("4.0"))
                    .amount(new BigDecimal("4000.00"))
                    .currency("JPY")
                    .status(ShiftBudgetConsumptionStatus.CONFIRMED)
                    .recordedAt(java.time.LocalDateTime.now())
                    .build();

            given(consumptionRepository.findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                    SLOT_ID, USER_ID, ShiftBudgetConsumptionStatus.PLANNED))
                    .willReturn(Optional.empty());
            given(consumptionRepository.findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                    SLOT_ID, USER_ID, ShiftBudgetConsumptionStatus.CONFIRMED))
                    .willReturn(Optional.of(confirmed));

            assertThatThrownBy(() -> service.recordSingleConsumption(
                    ALLOCATION_ID, SHIFT_ID, SLOT_ID, USER_ID,
                    new BigDecimal("1200"), new BigDecimal("4.0"), 0L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CONFIRMED");

            verify(consumptionRepository, never()).save(any(ShiftBudgetConsumptionEntity.class));
            verify(allocationRepository, never()).incrementConsumedAmount(any(), any());
        }

        @Test
        @DisplayName("時給未設定 (rate=null) でも 0 として記録 (設計書 §11)")
        void 時給未設定_amount0で記録() {
            given(allocationRepository.incrementConsumedAmount(any(), any())).willReturn(1);
            given(consumptionRepository.findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                    SLOT_ID, USER_ID, ShiftBudgetConsumptionStatus.PLANNED))
                    .willReturn(Optional.empty());
            given(consumptionRepository.findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                    SLOT_ID, USER_ID, ShiftBudgetConsumptionStatus.CONFIRMED))
                    .willReturn(Optional.empty());

            service.recordSingleConsumption(ALLOCATION_ID, SHIFT_ID, SLOT_ID, USER_ID,
                    null, new BigDecimal("4.0"), 0L);

            ArgumentCaptor<ShiftBudgetConsumptionEntity> capt =
                    ArgumentCaptor.forClass(ShiftBudgetConsumptionEntity.class);
            verify(consumptionRepository).save(capt.capture());
            assertThat(capt.getValue().getHourlyRateSnapshot()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(capt.getValue().getAmount()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("再公開前の旧世代清掃")
    class PublicationCleanup {

        @Test
        @DisplayName("AC05/10 有効orgだけをclaimしOFF組織の履歴と金額へ触れない")
        void 有効な組織だけを掃除する() {
            given(publicationPort.lockPublication(SHIFT_ID)).willReturn(Optional.of(
                    new com.mannschaft.app.shiftbudget.port.ShiftBudgetPublicationPort.PublicationState(2L, true)));
            var enabled = org.mockito.Mockito.mock(ShiftBudgetConsumptionRepository.PlannedCancellationRow.class);
            given(enabled.getOrganizationId()).willReturn(1L);
            given(enabled.getConsumptionId()).willReturn(1L);
            given(enabled.getAllocationId()).willReturn(ALLOCATION_ID);
            given(enabled.getAmount()).willReturn(new BigDecimal("4800.00"));
            var disabled = org.mockito.Mockito.mock(ShiftBudgetConsumptionRepository.PlannedCancellationRow.class);
            given(disabled.getOrganizationId()).willReturn(2L);
            org.mockito.Mockito.lenient().when(disabled.getConsumptionId()).thenReturn(9L);
            given(consumptionRepository.findPlannedCancellationRows(SHIFT_ID, 1L))
                    .willReturn(List.of(enabled, disabled));
            given(consumptionRepository.cancelPlannedById(eq(1L), eq(ShiftBudgetCancelReason.RE_PUBLISHED), any()))
                    .willReturn(1);
            given(allocationRepository.decrementConsumedAmount(ALLOCATION_ID, new BigDecimal("4800.00")))
                    .willReturn(1);

            var result = service.cancelOldPlannedForPublication(SHIFT_ID, 2L, Set.of(1L));

            assertThat(result.accepted()).isTrue();
            assertThat(result.cancellation().cancelledCount()).isEqualTo(1);
            assertThat(result.cancellation().countsByOrganization()).containsOnlyKeys(1L);
            assertThat(result.cancellation().organizationsByAllocation()).containsEntry(ALLOCATION_ID, 1L);
            verify(consumptionRepository, never()).cancelPlannedById(eq(9L), any(), any());
            var order = org.mockito.Mockito.inOrder(publicationPort, consumptionRepository);
            order.verify(publicationPort).lockPublication(SHIFT_ID);
            order.verify(consumptionRepository).findPlannedCancellationRows(SHIFT_ID, 1L);
        }

        @Test
        @DisplayName("AC05/14 有効な公開で対象0件ならacceptedを維持する")
        void 対象0件は正常な掃除完了() {
            given(publicationPort.lockPublication(SHIFT_ID)).willReturn(Optional.of(
                    new com.mannschaft.app.shiftbudget.port.ShiftBudgetPublicationPort.PublicationState(2L, true)));
            given(consumptionRepository.findPlannedCancellationRows(SHIFT_ID, 1L)).willReturn(List.of());

            var result = service.cancelOldPlannedForPublication(SHIFT_ID, 2L, Set.of(1L));

            assertThat(result.accepted()).isTrue();
            assertThat(result.cancellation().cancelledCount()).isZero();
            org.mockito.Mockito.verifyNoInteractions(allocationRepository);
        }

        @Test
        @DisplayName("AC03/14 親不在は不受理で掃除も新記録も始めない")
        void 親不在は不受理() {
            given(publicationPort.lockPublication(SHIFT_ID)).willReturn(Optional.empty());

            var result = service.cancelOldPlannedForPublication(SHIFT_ID, 2L, Set.of(1L));

            assertThat(result.accepted()).isFalse();
            assertThat(result.cancellation().cancelledCount()).isZero();
            org.mockito.Mockito.verifyNoInteractions(consumptionRepository, allocationRepository);
        }
    }

    @Nested
    @DisplayName("cancelAllForShift (アーカイブ時)")
    class CancelAllForShift {

        @Test
        @DisplayName("PLANNED は CANCELLED 遷移 + 減算、CONFIRMED は変更なし")
        void planned_のみ_cancel() {
            ShiftBudgetConsumptionEntity planned = ShiftBudgetConsumptionEntity.builder().id(1L)
                    .allocationId(ALLOCATION_ID).shiftId(SHIFT_ID).slotId(SLOT_ID).userId(USER_ID)
                    .hourlyRateSnapshot(new BigDecimal("1200")).hours(new BigDecimal("4"))
                    .amount(new BigDecimal("4800.00")).currency("JPY")
                    .status(ShiftBudgetConsumptionStatus.PLANNED)
                    .recordedAt(java.time.LocalDateTime.now())
                    .build();
            ShiftBudgetConsumptionEntity confirmed = ShiftBudgetConsumptionEntity.builder()
                    .allocationId(ALLOCATION_ID).shiftId(SHIFT_ID).slotId(8L).userId(USER_ID)
                    .hourlyRateSnapshot(new BigDecimal("1200")).hours(new BigDecimal("4"))
                    .amount(new BigDecimal("4800.00")).currency("JPY")
                    .status(ShiftBudgetConsumptionStatus.CONFIRMED)
                    .recordedAt(java.time.LocalDateTime.now())
                    .build();
            var row = org.mockito.Mockito.mock(ShiftBudgetConsumptionRepository.PlannedCancellationRow.class);
            given(row.getConsumptionId()).willReturn(1L);
            given(row.getAllocationId()).willReturn(ALLOCATION_ID);
            given(row.getOrganizationId()).willReturn(1L);
            given(row.getAmount()).willReturn(planned.getAmount());
            given(consumptionRepository.findPlannedCancellationRows(SHIFT_ID, 0L)).willReturn(List.of(row));
            given(allocationRepository.decrementConsumedAmount(any(), any())).willReturn(1);
            given(consumptionRepository.cancelPlannedById(eq(1L),
                    eq(ShiftBudgetCancelReason.SHIFT_DELETED), any(java.time.LocalDateTime.class))).willReturn(1);

            int cancelled = service.cancelAllForShift(SHIFT_ID);

            assertThat(cancelled).isEqualTo(1);
            verify(consumptionRepository).cancelPlannedById(eq(1L),
                    eq(ShiftBudgetCancelReason.SHIFT_DELETED), eq(LocalDateTime.of(2026, 11, 4, 9, 0)));
            verify(consumptionRepository, never()).save(any());
            assertThat(confirmed.getStatus()).isEqualTo(ShiftBudgetConsumptionStatus.CONFIRMED);
            verify(allocationRepository).decrementConsumedAmount(eq(ALLOCATION_ID),
                    eq(new BigDecimal("4800.00")));
        }
    }

    @Nested
    @DisplayName("calculateHours (深夜跨ぎ)")
    class CalculateHours {

        @Test
        @DisplayName("通常: 09:00-13:00 → 4.00h")
        void 通常時間() {
            BigDecimal h = ShiftBudgetConsumptionService.calculateHours(
                    LocalTime.of(9, 0), LocalTime.of(13, 0));
            assertThat(h).isEqualByComparingTo("4.00");
        }

        @Test
        @DisplayName("深夜跨ぎ: 22:00-03:00 → 5.00h (翌日扱い)")
        void 深夜跨ぎ() {
            BigDecimal h = ShiftBudgetConsumptionService.calculateHours(
                    LocalTime.of(22, 0), LocalTime.of(3, 0));
            assertThat(h).isEqualByComparingTo("5.00");
        }
    }
}
