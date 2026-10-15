package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.shiftbudget.ShiftBudgetCancelReason;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

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

    private static final Long ALLOCATION_ID = 42L;
    private static final Long SHIFT_ID = 5L;
    private static final Long SLOT_ID = 7L;
    private static final Long USER_ID = 100L;

    @Mock
    private ShiftBudgetConsumptionRepository consumptionRepository;
    @Mock
    private ShiftBudgetAllocationRepository allocationRepository;

    @InjectMocks
    private ShiftBudgetConsumptionService service;

    @Nested
    @DisplayName("recordSingleConsumption (§11.1 擬似コード)")
    class RecordSingleConsumption {

        @Test
        @DisplayName("既存なし → 新規 PLANNED INSERT + アトミック加算")
        void 新規_INSERT() {
            given(consumptionRepository.findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                    SLOT_ID, USER_ID, ShiftBudgetConsumptionStatus.PLANNED))
                    .willReturn(Optional.empty());
            given(consumptionRepository.findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                    SLOT_ID, USER_ID, ShiftBudgetConsumptionStatus.CONFIRMED))
                    .willReturn(Optional.empty());

            service.recordSingleConsumption(ALLOCATION_ID, SHIFT_ID, SLOT_ID, USER_ID,
                    new BigDecimal("1200"), new BigDecimal("4.0"));

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
        @DisplayName("既存 PLANNED あり → CANCELLED 遷移 + 減算 → 新規 INSERT + 加算")
        void 再公開シナリオ_既存_CANCELLED_新規INSERT() {
            ShiftBudgetConsumptionEntity oldRecord = ShiftBudgetConsumptionEntity.builder()
                    .allocationId(ALLOCATION_ID)
                    .shiftId(SHIFT_ID)
                    .slotId(SLOT_ID)
                    .userId(USER_ID)
                    .hourlyRateSnapshot(new BigDecimal("1000"))
                    .hours(new BigDecimal("4.0"))
                    .amount(new BigDecimal("4000.00"))
                    .currency("JPY")
                    .status(ShiftBudgetConsumptionStatus.PLANNED)
                    .recordedAt(java.time.LocalDateTime.now())
                    .build();

            given(consumptionRepository.findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                    SLOT_ID, USER_ID, ShiftBudgetConsumptionStatus.PLANNED))
                    .willReturn(Optional.of(oldRecord));
            given(consumptionRepository.findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                    SLOT_ID, USER_ID, ShiftBudgetConsumptionStatus.CONFIRMED))
                    .willReturn(Optional.empty());

            service.recordSingleConsumption(ALLOCATION_ID, SHIFT_ID, SLOT_ID, USER_ID,
                    new BigDecimal("1200"), new BigDecimal("4.0"));

            // 旧レコードは CANCELLED 遷移
            assertThat(oldRecord.getStatus()).isEqualTo(ShiftBudgetConsumptionStatus.CANCELLED);
            assertThat(oldRecord.getCancelReason()).isEqualTo(ShiftBudgetCancelReason.RE_PUBLISHED);

            // 減算 → 加算 の順序を assert
            verify(allocationRepository).decrementConsumedAmount(eq(ALLOCATION_ID),
                    eq(new BigDecimal("4000.00")));
            verify(allocationRepository).incrementConsumedAmount(eq(ALLOCATION_ID),
                    eq(new BigDecimal("4800.00")));
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
                    new BigDecimal("1200"), new BigDecimal("4.0")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CONFIRMED");

            verify(consumptionRepository, never()).save(any(ShiftBudgetConsumptionEntity.class));
            verify(allocationRepository, never()).incrementConsumedAmount(any(), any());
        }

        @Test
        @DisplayName("時給未設定 (rate=null) でも 0 として記録 (設計書 §11)")
        void 時給未設定_amount0で記録() {
            given(consumptionRepository.findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                    SLOT_ID, USER_ID, ShiftBudgetConsumptionStatus.PLANNED))
                    .willReturn(Optional.empty());
            given(consumptionRepository.findBySlotIdAndUserIdAndStatusAndDeletedAtIsNull(
                    SLOT_ID, USER_ID, ShiftBudgetConsumptionStatus.CONFIRMED))
                    .willReturn(Optional.empty());

            service.recordSingleConsumption(ALLOCATION_ID, SHIFT_ID, SLOT_ID, USER_ID,
                    null, new BigDecimal("4.0"));

            ArgumentCaptor<ShiftBudgetConsumptionEntity> capt =
                    ArgumentCaptor.forClass(ShiftBudgetConsumptionEntity.class);
            verify(consumptionRepository).save(capt.capture());
            assertThat(capt.getValue().getHourlyRateSnapshot()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(capt.getValue().getAmount()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("cancelAllForShift (アーカイブ時)")
    class CancelAllForShift {

        @Test
        @DisplayName("PLANNED は CANCELLED 遷移 + 減算、CONFIRMED は変更なし")
        void planned_のみ_cancel() {
            ShiftBudgetConsumptionEntity planned = ShiftBudgetConsumptionEntity.builder()
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
            given(consumptionRepository.findByShiftIdAndDeletedAtIsNull(SHIFT_ID))
                    .willReturn(List.of(planned, confirmed));

            int cancelled = service.cancelAllForShift(SHIFT_ID);

            assertThat(cancelled).isEqualTo(1);
            assertThat(planned.getStatus()).isEqualTo(ShiftBudgetConsumptionStatus.CANCELLED);
            assertThat(planned.getCancelReason()).isEqualTo(ShiftBudgetCancelReason.SHIFT_DELETED);
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
