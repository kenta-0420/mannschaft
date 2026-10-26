package com.mannschaft.app.facility.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.facility.DayType;
import com.mannschaft.app.facility.FacilityMapper;
import com.mannschaft.app.facility.dto.TimeRateEntry;
import com.mannschaft.app.facility.dto.TimeRateResponse;
import com.mannschaft.app.facility.dto.UpdateTimeRatesRequest;
import com.mannschaft.app.facility.dto.UpdateUsageRuleRequest;
import com.mannschaft.app.facility.dto.UsageRuleResponse;
import com.mannschaft.app.facility.entity.FacilityTimeRateEntity;
import com.mannschaft.app.facility.entity.FacilityUsageRuleEntity;
import com.mannschaft.app.facility.repository.FacilityTimeRateRepository;
import com.mannschaft.app.facility.repository.FacilityUsageRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link FacilityRuleService} の単体テスト。
 * 利用ルールCRUD・時間帯別料金の取得/一括置換を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FacilityRuleService 単体テスト")
class FacilityRuleServiceTest {

    @Mock
    private FacilityUsageRuleRepository usageRuleRepository;

    @Mock
    private FacilityTimeRateRepository timeRateRepository;

    @Mock
    private FacilityMapper facilityMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private FacilityRuleService ruleService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long FACILITY_ID = 1L;

    private FacilityUsageRuleEntity createUsageRule() {
        return FacilityUsageRuleEntity.builder()
                .facilityId(FACILITY_ID)
                .maxHoursPerBooking(new BigDecimal("4.0"))
                .minHoursPerBooking(new BigDecimal("0.5"))
                .maxBookingsPerMonthPerUser(4)
                .maxConsecutiveSlots(8)
                .minAdvanceHours(1)
                .maxAdvanceDays(30)
                .availableTimeFrom(LocalTime.of(9, 0))
                .availableTimeTo(LocalTime.of(22, 0))
                .availableDaysOfWeek("[0,1,2,3,4,5,6]")
                .build();
    }

    // ========================================
    // getUsageRule
    // ========================================

    @Nested
    @DisplayName("getUsageRule")
    class GetUsageRule {

        @Test
        @DisplayName("正常系: 利用ルールが返る")
        void 利用ルール取得_正常_ルールが返る() {
            // Given
            FacilityUsageRuleEntity rule = createUsageRule();
            given(usageRuleRepository.findByFacilityId(FACILITY_ID)).willReturn(Optional.of(rule));
            given(facilityMapper.toUsageRuleResponse(rule)).willReturn(mock(UsageRuleResponse.class));

            // When
            UsageRuleResponse result = ruleService.getUsageRule(FACILITY_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: ルールが存在しないでFACILITY_005例外")
        void 利用ルール取得_存在しない_FACILITY005例外() {
            // Given
            given(usageRuleRepository.findByFacilityId(FACILITY_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> ruleService.getUsageRule(FACILITY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_005"));
        }
    }

    // ========================================
    // updateUsageRule
    // ========================================

    @Nested
    @DisplayName("updateUsageRule")
    class UpdateUsageRule {

        @Test
        @DisplayName("正常系: 利用ルールが更新される")
        void 利用ルール更新_正常_更新される() throws JsonProcessingException {
            // Given
            FacilityUsageRuleEntity rule = createUsageRule();
            UpdateUsageRuleRequest request = new UpdateUsageRuleRequest(
                    new BigDecimal("8.0"), new BigDecimal("1.0"),
                    6, 16, 2, 60, 3, 48,
                    LocalTime.of(8, 0), LocalTime.of(23, 0),
                    List.of(1, 2, 3, 4, 5), null, "更新メモ"
            );

            given(usageRuleRepository.findByFacilityId(FACILITY_ID)).willReturn(Optional.of(rule));
            given(objectMapper.writeValueAsString(List.of(1, 2, 3, 4, 5))).willReturn("[1,2,3,4,5]");
            given(facilityMapper.toUsageRuleResponse(rule)).willReturn(mock(UsageRuleResponse.class));

            // When
            UsageRuleResponse result = ruleService.updateUsageRule(FACILITY_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: ルールが存在しないでFACILITY_005例外")
        void 利用ルール更新_存在しない_FACILITY005例外() {
            // Given
            UpdateUsageRuleRequest request = new UpdateUsageRuleRequest(
                    null, null, null, null, null, null, null, null,
                    null, null, null, null, null
            );
            given(usageRuleRepository.findByFacilityId(FACILITY_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> ruleService.updateUsageRule(FACILITY_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FACILITY_005"));
        }

        @Test
        @DisplayName("正常系: availableDaysOfWeekがnullの場合は既存値が維持される")
        void 利用ルール更新_曜日null_既存値維持() {
            // Given
            FacilityUsageRuleEntity rule = createUsageRule();
            UpdateUsageRuleRequest request = new UpdateUsageRuleRequest(
                    new BigDecimal("4.0"), new BigDecimal("0.5"),
                    4, 8, 1, 30, 0, null,
                    LocalTime.of(9, 0), LocalTime.of(22, 0),
                    null, null, null
            );

            given(usageRuleRepository.findByFacilityId(FACILITY_ID)).willReturn(Optional.of(rule));
            given(facilityMapper.toUsageRuleResponse(rule)).willReturn(mock(UsageRuleResponse.class));

            // When
            UsageRuleResponse result = ruleService.updateUsageRule(FACILITY_ID, request);

            // Then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // getTimeRates
    // ========================================

    @Nested
    @DisplayName("getTimeRates")
    class GetTimeRates {

        @Test
        @DisplayName("正常系: 時間帯別料金が返る")
        void 時間帯別料金取得_正常_リストが返る() {
            // Given
            FacilityTimeRateEntity rate = FacilityTimeRateEntity.builder()
                    .facilityId(FACILITY_ID)
                    .dayType(DayType.WEEKDAY)
                    .timeFrom(LocalTime.of(9, 0))
                    .timeTo(LocalTime.of(12, 0))
                    .ratePerSlot(BigDecimal.valueOf(500))
                    .build();
            given(timeRateRepository.findByFacilityIdOrderByDayTypeAscTimeFromAsc(FACILITY_ID))
                    .willReturn(List.of(rate));
            given(facilityMapper.toTimeRateResponseList(any())).willReturn(List.of(mock(TimeRateResponse.class)));

            // When
            List<TimeRateResponse> result = ruleService.getTimeRates(FACILITY_ID);

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: 料金が設定されていない場合は空リストが返る")
        void 時間帯別料金取得_未設定_空リストが返る() {
            // Given
            given(timeRateRepository.findByFacilityIdOrderByDayTypeAscTimeFromAsc(FACILITY_ID))
                    .willReturn(List.of());
            given(facilityMapper.toTimeRateResponseList(any())).willReturn(List.of());

            // When
            List<TimeRateResponse> result = ruleService.getTimeRates(FACILITY_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // replaceTimeRates
    // ========================================

    @Nested
    @DisplayName("replaceTimeRates")
    class ReplaceTimeRates {

        @Test
        @DisplayName("正常系: 時間帯別料金が一括置換される")
        void 時間帯別料金置換_正常_置換される() {
            // Given
            TimeRateEntry entry1 = new TimeRateEntry(
                    "WEEKDAY", LocalTime.of(9, 0), LocalTime.of(12, 0), BigDecimal.valueOf(500));
            TimeRateEntry entry2 = new TimeRateEntry(
                    "WEEKEND", LocalTime.of(9, 0), LocalTime.of(12, 0), BigDecimal.valueOf(800));
            UpdateTimeRatesRequest request = new UpdateTimeRatesRequest(List.of(entry1, entry2));

            given(timeRateRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
            given(facilityMapper.toTimeRateResponseList(any()))
                    .willReturn(List.of(mock(TimeRateResponse.class), mock(TimeRateResponse.class)));

            // When
            List<TimeRateResponse> result = ruleService.replaceTimeRates(FACILITY_ID, request);

            // Then
            assertThat(result).hasSize(2);
            verify(timeRateRepository).deleteByFacilityId(FACILITY_ID);
            verify(timeRateRepository).saveAll(any());
        }
    }
}
