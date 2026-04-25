package com.mannschaft.app.shift;

import com.mannschaft.app.shift.dto.HourlyRateResponse;
import com.mannschaft.app.shift.dto.ShiftPositionResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.entity.MemberAvailabilityDefaultEntity;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ShiftMapper} (MapStruct生成実装) の単体テスト。
 * エンティティからDTOへの変換を検証する。
 */
@DisplayName("ShiftMapper 単体テスト")
class ShiftMapperTest {

    private final ShiftMapper mapper = new ShiftMapperImpl();

    @Nested
    @DisplayName("toScheduleResponse")
    class ToScheduleResponse {

        @Test
        @DisplayName("スケジュールエンティティ変換_正常_フィールドが正しくマップされる")
        void スケジュールエンティティ変換_正常_フィールドが正しくマップされる() {
            ShiftScheduleEntity entity = ShiftScheduleEntity.builder()
                    .teamId(10L).title("3月シフト")
                    .periodType(ShiftPeriodType.WEEKLY)
                    .startDate(LocalDate.of(2026, 3, 1)).endDate(LocalDate.of(2026, 3, 31))
                    .status(ShiftScheduleStatus.DRAFT).note("備考テスト").build();
            ShiftScheduleResponse response = mapper.toScheduleResponse(entity);
            assertThat(response).isNotNull();
            assertThat(response.getPeriodType()).isEqualTo("WEEKLY");
            assertThat(response.getStatus()).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("スケジュールエンティティ変換_null_nullを返す")
        void スケジュールエンティティ変換_null_nullを返す() {
            assertThat(mapper.toScheduleResponse(null)).isNull();
        }

        @Test
        @DisplayName("スケジュールエンティティ変換_MONTHLY_正常変換")
        void スケジュールエンティティ変換_MONTHLY_正常変換() {
            ShiftScheduleEntity entity = ShiftScheduleEntity.builder()
                    .teamId(1L).title("月次").periodType(ShiftPeriodType.MONTHLY)
                    .startDate(LocalDate.of(2026, 4, 1)).endDate(LocalDate.of(2026, 4, 30))
                    .status(ShiftScheduleStatus.PUBLISHED).build();
            assertThat(mapper.toScheduleResponse(entity).getPeriodType()).isEqualTo("MONTHLY");
        }
    }

    @Nested
    @DisplayName("toScheduleResponseList")
    class ToScheduleResponseList {

        @Test
        @DisplayName("スケジュールリスト変換_正常_全要素変換")
        void スケジュールリスト変換_正常_全要素変換() {
            ShiftScheduleEntity e1 = ShiftScheduleEntity.builder().teamId(1L).title("A")
                    .periodType(ShiftPeriodType.WEEKLY).startDate(LocalDate.of(2026, 3, 1))
                    .endDate(LocalDate.of(2026, 3, 7)).status(ShiftScheduleStatus.DRAFT).build();
            assertThat(mapper.toScheduleResponseList(List.of(e1))).hasSize(1);
        }

        @Test
        @DisplayName("スケジュールリスト変換_null_nullを返す")
        void スケジュールリスト変換_null_nullを返す() {
            assertThat(mapper.toScheduleResponseList(null)).isNull();
        }

        @Test
        @DisplayName("スケジュールリスト変換_空リスト_空リストを返す")
        void スケジュールリスト変換_空リスト_空リストを返す() {
            assertThat(mapper.toScheduleResponseList(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("toPositionResponse")
    class ToPositionResponse {

        @Test
        @DisplayName("ポジションエンティティ変換_正常_フィールドが正しくマップされる")
        void ポジションエンティティ変換_正常_フィールドが正しくマップされる() {
            ShiftPositionEntity entity = ShiftPositionEntity.builder()
                    .teamId(5L).name("ホール担当").displayOrder(1).isActive(true).build();
            ShiftPositionResponse response = mapper.toPositionResponse(entity);
            assertThat(response).isNotNull();
            assertThat(response.getName()).isEqualTo("ホール担当");
            assertThat(response.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("ポジションエンティティ変換_null_nullを返す")
        void ポジションエンティティ変換_null_nullを返す() {
            assertThat(mapper.toPositionResponse(null)).isNull();
        }
    }

    @Nested
    @DisplayName("toPositionResponseList")
    class ToPositionResponseList {

        @Test
        @DisplayName("ポジションリスト変換_正常_全要素変換")
        void ポジションリスト変換_正常_全要素変換() {
            ShiftPositionEntity e = ShiftPositionEntity.builder().teamId(1L).name("キッチン").build();
            assertThat(mapper.toPositionResponseList(List.of(e))).hasSize(1);
        }

        @Test
        @DisplayName("ポジションリスト変換_null_nullを返す")
        void ポジションリスト変換_null_nullを返す() {
            assertThat(mapper.toPositionResponseList(null)).isNull();
        }
    }

    @Nested
    @DisplayName("toRequestResponse")
    class ToRequestResponse {

        @Test
        @DisplayName("シフト希望エンティティ変換_正常_フィールドが正しくマップされる")
        void シフト希望エンティティ変換_正常_フィールドが正しくマップされる() {
            ShiftRequestEntity entity = ShiftRequestEntity.builder()
                    .scheduleId(100L).userId(200L).slotDate(LocalDate.of(2026, 3, 5))
                    .preference(ShiftPreference.PREFERRED).build();
            assertThat(mapper.toRequestResponse(entity).getPreference()).isEqualTo("PREFERRED");
        }

        @Test
        @DisplayName("シフト希望エンティティ変換_null_nullを返す")
        void シフト希望エンティティ変換_null_nullを返す() {
            assertThat(mapper.toRequestResponse(null)).isNull();
        }

        @Test
        @DisplayName("シフト希望エンティティ変換_STRONG_REST_正常変換")
        void シフト希望エンティティ変換_STRONG_REST_正常変換() {
            // F03.5 v2: 旧 UNAVAILABLE は STRONG_REST に移行済み（Flyway V3.137）
            ShiftRequestEntity entity = ShiftRequestEntity.builder()
                    .scheduleId(1L).userId(1L).slotDate(LocalDate.of(2026, 3, 1))
                    .preference(ShiftPreference.STRONG_REST).build();
            assertThat(mapper.toRequestResponse(entity).getPreference()).isEqualTo("STRONG_REST");
        }
    }

    @Nested
    @DisplayName("toRequestResponseList")
    class ToRequestResponseList {

        @Test
        @DisplayName("シフト希望リスト変換_null_nullを返す")
        void シフト希望リスト変換_null_nullを返す() {
            assertThat(mapper.toRequestResponseList(null)).isNull();
        }

        @Test
        @DisplayName("シフト希望リスト変換_正常_全要素変換")
        void シフト希望リスト変換_正常_全要素変換() {
            ShiftRequestEntity e = ShiftRequestEntity.builder()
                    .scheduleId(1L).userId(1L).slotDate(LocalDate.of(2026, 3, 1))
                    .preference(ShiftPreference.AVAILABLE).build();
            assertThat(mapper.toRequestResponseList(List.of(e))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("toSwapResponse")
    class ToSwapResponse {

        @Test
        @DisplayName("交代リクエストエンティティ変換_正常_フィールドが正しくマップされる")
        void 交代リクエストエンティティ変換_正常_フィールドが正しくマップされる() {
            ShiftSwapRequestEntity entity = ShiftSwapRequestEntity.builder()
                    .slotId(10L).requesterId(1L).status(SwapRequestStatus.PENDING).build();
            assertThat(mapper.toSwapResponse(entity).getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("交代リクエストエンティティ変換_null_nullを返す")
        void 交代リクエストエンティティ変換_null_nullを返す() {
            assertThat(mapper.toSwapResponse(null)).isNull();
        }

        @Test
        @DisplayName("交代リクエストエンティティ変換_APPROVED_正常変換")
        void 交代リクエストエンティティ変換_APPROVED_正常変換() {
            ShiftSwapRequestEntity entity = ShiftSwapRequestEntity.builder()
                    .slotId(1L).requesterId(1L).status(SwapRequestStatus.APPROVED).build();
            assertThat(mapper.toSwapResponse(entity).getStatus()).isEqualTo("APPROVED");
        }
    }

    @Nested
    @DisplayName("toSwapResponseList")
    class ToSwapResponseList {

        @Test
        @DisplayName("交代リクエストリスト変換_null_nullを返す")
        void 交代リクエストリスト変換_null_nullを返す() {
            assertThat(mapper.toSwapResponseList(null)).isNull();
        }

        @Test
        @DisplayName("交代リクエストリスト変換_正常_全要素変換")
        void 交代リクエストリスト変換_正常_全要素変換() {
            ShiftSwapRequestEntity e = ShiftSwapRequestEntity.builder()
                    .slotId(1L).requesterId(1L).status(SwapRequestStatus.CANCELLED).build();
            assertThat(mapper.toSwapResponseList(List.of(e))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("toAvailabilityResponse")
    class ToAvailabilityResponse {

        @Test
        @DisplayName("勤務可能時間エンティティ変換_正常_フィールドが正しくマップされる")
        void 勤務可能時間エンティティ変換_正常_フィールドが正しくマップされる() {
            MemberAvailabilityDefaultEntity entity = MemberAvailabilityDefaultEntity.builder()
                    .userId(1L).teamId(10L).dayOfWeek(1)
                    .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                    .preference(ShiftPreference.PREFERRED).build();
            assertThat(mapper.toAvailabilityResponse(entity).getPreference()).isEqualTo("PREFERRED");
        }

        @Test
        @DisplayName("勤務可能時間エンティティ変換_null_nullを返す")
        void 勤務可能時間エンティティ変換_null_nullを返す() {
            assertThat(mapper.toAvailabilityResponse(null)).isNull();
        }
    }

    @Nested
    @DisplayName("toAvailabilityResponseList")
    class ToAvailabilityResponseList {

        @Test
        @DisplayName("勤務可能時間リスト変換_null_nullを返す")
        void 勤務可能時間リスト変換_null_nullを返す() {
            assertThat(mapper.toAvailabilityResponseList(null)).isNull();
        }

        @Test
        @DisplayName("勤務可能時間リスト変換_正常_全要素変換")
        void 勤務可能時間リスト変換_正常_全要素変換() {
            MemberAvailabilityDefaultEntity e = MemberAvailabilityDefaultEntity.builder()
                    .userId(1L).teamId(1L).dayOfWeek(2)
                    .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(17, 0))
                    .preference(ShiftPreference.AVAILABLE).build();
            assertThat(mapper.toAvailabilityResponseList(List.of(e))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("toHourlyRateResponse")
    class ToHourlyRateResponse {

        @Test
        @DisplayName("時給エンティティ変換_正常_フィールドが正しくマップされる")
        void 時給エンティティ変換_正常_フィールドが正しくマップされる() {
            ShiftHourlyRateEntity entity = ShiftHourlyRateEntity.builder()
                    .userId(1L).teamId(5L).hourlyRate(new BigDecimal("1200.00"))
                    .effectiveFrom(LocalDate.of(2026, 4, 1)).build();
            HourlyRateResponse response = mapper.toHourlyRateResponse(entity);
            assertThat(response).isNotNull();
            assertThat(response.getHourlyRate()).isEqualByComparingTo("1200.00");
        }

        @Test
        @DisplayName("時給エンティティ変換_null_nullを返す")
        void 時給エンティティ変換_null_nullを返す() {
            assertThat(mapper.toHourlyRateResponse(null)).isNull();
        }
    }

    @Nested
    @DisplayName("toHourlyRateResponseList")
    class ToHourlyRateResponseList {

        @Test
        @DisplayName("時給リスト変換_null_nullを返す")
        void 時給リスト変換_null_nullを返す() {
            assertThat(mapper.toHourlyRateResponseList(null)).isNull();
        }

        @Test
        @DisplayName("時給リスト変換_正常_全要素変換")
        void 時給リスト変換_正常_全要素変換() {
            ShiftHourlyRateEntity e = ShiftHourlyRateEntity.builder()
                    .userId(1L).teamId(1L).hourlyRate(new BigDecimal("1000"))
                    .effectiveFrom(LocalDate.of(2026, 1, 1)).build();
            assertThat(mapper.toHourlyRateResponseList(List.of(e))).hasSize(1);
        }
    }
}
