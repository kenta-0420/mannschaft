package com.mannschaft.app.shift;

import com.mannschaft.app.shift.dto.CreateHourlyRateRequest;
import com.mannschaft.app.shift.dto.HourlyRateResponse;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import com.mannschaft.app.shift.service.ShiftHourlyRateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftHourlyRateService} の単体テスト。
 * 時給の設定・取得・削除を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftHourlyRateService 単体テスト")
class ShiftHourlyRateServiceTest {

    @Mock
    private ShiftHourlyRateRepository hourlyRateRepository;

    @Mock
    private ShiftMapper shiftMapper;

    @InjectMocks
    private ShiftHourlyRateService shiftHourlyRateService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 10L;
    private static final Long TEAM_ID = 1L;
    private static final Long RATE_ID = 500L;

    private ShiftHourlyRateEntity createRateEntity() {
        return ShiftHourlyRateEntity.builder()
                .userId(USER_ID)
                .teamId(TEAM_ID)
                .hourlyRate(new BigDecimal("1200.00"))
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .build();
    }

    private HourlyRateResponse createRateResponse() {
        return new HourlyRateResponse(
                RATE_ID, USER_ID, TEAM_ID,
                new BigDecimal("1200.00"), LocalDate.of(2026, 4, 1),
                LocalDateTime.now());
    }

    // ========================================
    // listHourlyRates
    // ========================================

    @Nested
    @DisplayName("listHourlyRates")
    class ListHourlyRates {

        @Test
        @DisplayName("時給履歴一覧取得_正常_リスト返却")
        void 時給履歴一覧取得_正常_リスト返却() {
            // Given
            ShiftHourlyRateEntity entity = createRateEntity();
            HourlyRateResponse response = createRateResponse();
            given(hourlyRateRepository.findByUserIdAndTeamIdOrderByEffectiveFromDesc(USER_ID, TEAM_ID))
                    .willReturn(List.of(entity));
            given(shiftMapper.toHourlyRateResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<HourlyRateResponse> result = shiftHourlyRateService.listHourlyRates(USER_ID, TEAM_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getHourlyRate()).isEqualByComparingTo("1200.00");
        }
    }

    // ========================================
    // getEffectiveRate
    // ========================================

    @Nested
    @DisplayName("getEffectiveRate")
    class GetEffectiveRate {

        @Test
        @DisplayName("有効時給取得_正常_レスポンス返却")
        void 有効時給取得_正常_レスポンス返却() {
            // Given
            ShiftHourlyRateEntity entity = createRateEntity();
            HourlyRateResponse response = createRateResponse();
            LocalDate date = LocalDate.of(2026, 4, 15);
            given(hourlyRateRepository.findEffectiveRate(USER_ID, TEAM_ID, date))
                    .willReturn(Optional.of(entity));
            given(shiftMapper.toHourlyRateResponse(entity)).willReturn(response);

            // When
            HourlyRateResponse result = shiftHourlyRateService.getEffectiveRate(USER_ID, TEAM_ID, date);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getHourlyRate()).isEqualByComparingTo("1200.00");
        }

        @Test
        @DisplayName("有効時給取得_存在しない_null返却")
        void 有効時給取得_存在しない_null返却() {
            // Given
            LocalDate date = LocalDate.of(2026, 1, 1);
            given(hourlyRateRepository.findEffectiveRate(USER_ID, TEAM_ID, date))
                    .willReturn(Optional.empty());

            // When
            HourlyRateResponse result = shiftHourlyRateService.getEffectiveRate(USER_ID, TEAM_ID, date);

            // Then
            assertThat(result).isNull();
        }
    }

    // ========================================
    // createHourlyRate
    // ========================================

    @Nested
    @DisplayName("createHourlyRate")
    class CreateHourlyRate {

        @Test
        @DisplayName("時給設定_正常_レスポンス返却")
        void 時給設定_正常_レスポンス返却() {
            // Given
            CreateHourlyRateRequest req = new CreateHourlyRateRequest(
                    USER_ID, new BigDecimal("1500.00"), LocalDate.of(2026, 5, 1));
            ShiftHourlyRateEntity savedEntity = createRateEntity();
            HourlyRateResponse response = createRateResponse();
            given(hourlyRateRepository.save(any(ShiftHourlyRateEntity.class))).willReturn(savedEntity);
            given(shiftMapper.toHourlyRateResponse(savedEntity)).willReturn(response);

            // When
            HourlyRateResponse result = shiftHourlyRateService.createHourlyRate(TEAM_ID, req);

            // Then
            assertThat(result).isNotNull();
            verify(hourlyRateRepository).save(any(ShiftHourlyRateEntity.class));
        }
    }

    // ========================================
    // deleteHourlyRate
    // ========================================

    @Nested
    @DisplayName("deleteHourlyRate")
    class DeleteHourlyRate {

        @Test
        @DisplayName("時給設定削除_正常_deleteByIdが呼ばれる")
        void 時給設定削除_正常_deleteByIdが呼ばれる() {
            // When
            shiftHourlyRateService.deleteHourlyRate(RATE_ID);

            // Then
            verify(hourlyRateRepository).deleteById(RATE_ID);
        }
    }
}
