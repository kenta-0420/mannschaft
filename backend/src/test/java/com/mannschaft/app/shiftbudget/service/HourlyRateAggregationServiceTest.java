package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.dto.PositionRequiredCount;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsRequest;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsRequest.RateMode;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link HourlyRateAggregationService} の単体テスト。
 *
 * <p>設計書 F08.7 §4.1 / §14.1 のテストケースをカバー:</p>
 * <ul>
 *   <li>3 モード (MEMBER_AVG / POSITION_AVG / EXPLICIT) の正常系</li>
 *   <li>境界ケース warning (AVG_RATE_ZERO / POSITION_NO_RATE_DATA / INSUFFICIENT_RATE_DATA)</li>
 *   <li>退職メンバー除外 / ポジション未定除外 / 時給論理削除除外（リポジトリ層でテスト済の前提）</li>
 *   <li>POSITION_AVG の重複検出・空配列・required_count バリデーション</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HourlyRateAggregationService 単体テスト")
class HourlyRateAggregationServiceTest {

    @Mock
    private ShiftBudgetRateQueryRepository rateQueryRepository;

    @InjectMocks
    private HourlyRateAggregationService service;

    private static final Long TEAM_ID = 12L;

    // ─────────────────────────────────────────────────────
    // EXPLICIT モード
    // ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("EXPLICIT モード")
    class Explicit {

        @Test
        @DisplayName("EXPLICIT_時給指定_指定値そのまま返却")
        void EXPLICIT_時給指定_指定値そのまま返却() {
            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    null, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.EXPLICIT, new BigDecimal("1500"), null);

            HourlyRateAggregationService.AggregationResult result = service.aggregate(req);

            assertThat(result.avgHourlyRate()).isEqualByComparingTo("1500");
            assertThat(result.warnings()).isEmpty();
            assertThat(result.positionBreakdown()).isNull();
        }

        @Test
        @DisplayName("EXPLICIT_時給0_AVG_RATE_ZERO警告を含む")
        void EXPLICIT_時給0_AVG_RATE_ZERO警告を含む() {
            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    null, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.EXPLICIT, BigDecimal.ZERO, null);

            HourlyRateAggregationService.AggregationResult result = service.aggregate(req);

            assertThat(result.avgHourlyRate()).isEqualByComparingTo("0");
            assertThat(result.warnings()).contains("AVG_RATE_ZERO");
        }

        @Test
        @DisplayName("EXPLICIT_時給null_MISSING_EXPLICIT_RATE例外")
        void EXPLICIT_時給null_MISSING_EXPLICIT_RATE例外() {
            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    null, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.EXPLICIT, null, null);

            assertThatThrownBy(() -> service.aggregate(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ShiftBudgetErrorCode.MISSING_EXPLICIT_RATE);
        }
    }

    // ─────────────────────────────────────────────────────
    // MEMBER_AVG モード
    // ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("MEMBER_AVG モード")
    class MemberAvg {

        @Test
        @DisplayName("MEMBER_AVG_全員時給設定済_平均時給を返す")
        void MEMBER_AVG_全員時給設定済_平均時給を返す() {
            // 平均 1200, 5 人
            given(rateQueryRepository.findTeamAverageRate(eq(TEAM_ID), any(LocalDate.class)))
                    .willReturn(java.util.Collections.<Object[]>singletonList(
                            new Object[]{new BigDecimal("1200.00"), 5L}));

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.MEMBER_AVG, null, null);

            HourlyRateAggregationService.AggregationResult result = service.aggregate(req);

            assertThat(result.avgHourlyRate()).isEqualByComparingTo("1200.00");
            assertThat(result.warnings()).isEmpty();
        }

        @Test
        @DisplayName("MEMBER_AVG_チーム0人_AVG_RATE_ZEROとINSUFFICIENT_RATE_DATA警告")
        void MEMBER_AVG_チーム0人_AVG_RATE_ZEROとINSUFFICIENT_RATE_DATA警告() {
            given(rateQueryRepository.findTeamAverageRate(eq(TEAM_ID), any(LocalDate.class)))
                    .willReturn(java.util.Collections.<Object[]>singletonList(
                            new Object[]{null, 0L}));

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.MEMBER_AVG, null, null);

            HourlyRateAggregationService.AggregationResult result = service.aggregate(req);

            assertThat(result.avgHourlyRate()).isEqualByComparingTo("0");
            assertThat(result.warnings()).contains("AVG_RATE_ZERO", "INSUFFICIENT_RATE_DATA");
        }

        @Test
        @DisplayName("MEMBER_AVG_team_id_null_TEAM_NOT_FOUND例外")
        void MEMBER_AVG_team_id_null_TEAM_NOT_FOUND例外() {
            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    null, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.MEMBER_AVG, null, null);

            assertThatThrownBy(() -> service.aggregate(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ShiftBudgetErrorCode.TEAM_NOT_FOUND);
        }
    }

    // ─────────────────────────────────────────────────────
    // POSITION_AVG モード
    // ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("POSITION_AVG モード")
    class PositionAvg {

        @Test
        @DisplayName("POSITION_AVG_2ポジション_加重平均を返す_暫定全体共有")
        void POSITION_AVG_2ポジション_加重平均を返す_暫定全体共有() {
            // Phase 9-α 暫定: 各 position の avg は teamAvg を共用 (1200)
            // 加重平均 = (1200×5 + 1200×3) / (5+3) = 1200
            given(rateQueryRepository.countPositionInTeam(anyLong(), eq(TEAM_ID)))
                    .willReturn(1L);
            given(rateQueryRepository.averageRateForPositionFallback(eq(TEAM_ID), any(LocalDate.class)))
                    .willReturn(new BigDecimal("1200.00"));

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.POSITION_AVG, null,
                    List.of(
                            new PositionRequiredCount(1L, 5),
                            new PositionRequiredCount(2L, 3)
                    ));

            HourlyRateAggregationService.AggregationResult result = service.aggregate(req);

            assertThat(result.avgHourlyRate()).isEqualByComparingTo("1200.00");
            assertThat(result.positionBreakdown()).hasSize(2);
            assertThat(result.warnings()).isEmpty();
        }

        @Test
        @DisplayName("POSITION_AVG_メンバー0人_POSITION_NO_RATE_DATA警告")
        void POSITION_AVG_メンバー0人_POSITION_NO_RATE_DATA警告() {
            given(rateQueryRepository.countPositionInTeam(anyLong(), eq(TEAM_ID)))
                    .willReturn(1L);
            given(rateQueryRepository.averageRateForPositionFallback(eq(TEAM_ID), any(LocalDate.class)))
                    .willReturn(null);

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.POSITION_AVG, null,
                    List.of(new PositionRequiredCount(1L, 5)));

            HourlyRateAggregationService.AggregationResult result = service.aggregate(req);

            assertThat(result.warnings())
                    .contains("POSITION_NO_RATE_DATA", "AVG_RATE_ZERO");
            assertThat(result.avgHourlyRate()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("POSITION_AVG_重複position_DUPLICATE_POSITION_ID例外")
        void POSITION_AVG_重複position_DUPLICATE_POSITION_ID例外() {
            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.POSITION_AVG, null,
                    List.of(
                            new PositionRequiredCount(1L, 5),
                            new PositionRequiredCount(1L, 3)
                    ));

            assertThatThrownBy(() -> service.aggregate(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ShiftBudgetErrorCode.DUPLICATE_POSITION_ID);
        }

        @Test
        @DisplayName("POSITION_AVG_空配列_MISSING_POSITION_COUNTS例外")
        void POSITION_AVG_空配列_MISSING_POSITION_COUNTS例外() {
            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.POSITION_AVG, null,
                    List.of());

            assertThatThrownBy(() -> service.aggregate(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ShiftBudgetErrorCode.MISSING_POSITION_COUNTS);
        }

        @Test
        @DisplayName("POSITION_AVG_position不在_TEAM_NOT_FOUND例外_IDOR対策")
        void POSITION_AVG_position不在_TEAM_NOT_FOUND例外_IDOR対策() {
            given(rateQueryRepository.countPositionInTeam(anyLong(), eq(TEAM_ID)))
                    .willReturn(0L);

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.POSITION_AVG, null,
                    List.of(new PositionRequiredCount(999L, 5)));

            assertThatThrownBy(() -> service.aggregate(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ShiftBudgetErrorCode.TEAM_NOT_FOUND);
        }
    }

}
