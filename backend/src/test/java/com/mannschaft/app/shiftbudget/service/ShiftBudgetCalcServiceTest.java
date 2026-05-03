package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.PositionRequiredCount;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsRequest;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsRequest.RateMode;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsResponse;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftBudgetCalcService} の単体テスト。
 *
 * <p>設計書 F08.7 §4.1 / §6.2.2 / §13 / §14.1 のテストケースをカバー:</p>
 * <ul>
 *   <li>逆算ロジック: floor(budget / (rate × hours))</li>
 *   <li>境界ケース: budget=0 / rate=0 / 通常値</li>
 *   <li>フィーチャーフラグ OFF → FEATURE_DISABLED</li>
 *   <li>権限チェック: MANAGE_SHIFTS が無い → 403 (BusinessException COMMON_002)</li>
 *   <li>多テナント: team_id の組織所属が解決できない → TEAM_NOT_FOUND</li>
 *   <li>バリデーション: slot_hours 範囲外, budget 負数</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftBudgetCalcService 単体テスト")
class ShiftBudgetCalcServiceTest {

    private static final Long USER_ID = 100L;
    private static final Long TEAM_ID = 12L;
    private static final Long ORG_ID = 1L;

    @Mock
    private ShiftBudgetFeatureService featureService;

    @Mock
    private ShiftBudgetRateQueryRepository rateQueryRepository;

    @Mock
    private HourlyRateAggregationService aggregationService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private ShiftBudgetCalcService calcService;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ────────────────────────────────────────────────────────
    // 逆算ロジック (設計書 §14.1)
    // ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("逆算ロジック")
    class CalcLogic {

        @Test
        @DisplayName("逆算_budget30万_rate1200_hours4_required62枠")
        void 逆算_budget30万_rate1200_hours4_required62枠() {
            // arrange
            given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_ID))
                    .willReturn(Optional.of(ORG_ID));
            given(aggregationService.aggregate(any(RequiredSlotsRequest.class)))
                    .willReturn(new HourlyRateAggregationService.AggregationResult(
                            new BigDecimal("1200"), List.of(), null));

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.MEMBER_AVG, null, null);

            // act
            RequiredSlotsResponse resp = calcService.calculateRequiredSlots(req);

            // assert
            assertThat(resp.requiredSlots()).isEqualTo(62L);
            assertThat(resp.calculation()).contains("floor", "300000", "1200", "4", "62");
            assertThat(resp.warnings()).isEmpty();
        }

        @Test
        @DisplayName("逆算_budget0_required0_BUDGET_ZERO警告")
        void 逆算_budget0_required0_BUDGET_ZERO警告() {
            given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_ID))
                    .willReturn(Optional.of(ORG_ID));
            given(aggregationService.aggregate(any(RequiredSlotsRequest.class)))
                    .willReturn(new HourlyRateAggregationService.AggregationResult(
                            new BigDecimal("1200"), new java.util.ArrayList<>(), null));

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, BigDecimal.ZERO, new BigDecimal("4.0"),
                    RateMode.MEMBER_AVG, null, null);

            RequiredSlotsResponse resp = calcService.calculateRequiredSlots(req);

            assertThat(resp.requiredSlots()).isZero();
            assertThat(resp.warnings()).contains("BUDGET_ZERO");
        }

        @Test
        @DisplayName("逆算_rate0_required0_AVG_RATE_ZERO警告")
        void 逆算_rate0_required0_AVG_RATE_ZERO警告() {
            given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_ID))
                    .willReturn(Optional.of(ORG_ID));
            given(aggregationService.aggregate(any(RequiredSlotsRequest.class)))
                    .willReturn(new HourlyRateAggregationService.AggregationResult(
                            BigDecimal.ZERO,
                            new java.util.ArrayList<>(List.of("AVG_RATE_ZERO")),
                            null));

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.MEMBER_AVG, null, null);

            RequiredSlotsResponse resp = calcService.calculateRequiredSlots(req);

            assertThat(resp.requiredSlots()).isZero();
            assertThat(resp.warnings()).contains("AVG_RATE_ZERO");
        }

        @Test
        @DisplayName("逆算_余りあり_floor切り捨て_required62")
        void 逆算_余りあり_floor切り捨て_required62() {
            // 300100 / (1200 * 4.0) = 62.5208... → 62
            given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_ID))
                    .willReturn(Optional.of(ORG_ID));
            given(aggregationService.aggregate(any(RequiredSlotsRequest.class)))
                    .willReturn(new HourlyRateAggregationService.AggregationResult(
                            new BigDecimal("1200"), List.of(), null));

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300100"), new BigDecimal("4.0"),
                    RateMode.MEMBER_AVG, null, null);

            RequiredSlotsResponse resp = calcService.calculateRequiredSlots(req);

            assertThat(resp.requiredSlots()).isEqualTo(62L);
        }
    }

    // ────────────────────────────────────────────────────────
    // フィーチャーフラグ・権限・テナント
    // ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("フィーチャーフラグ・権限・テナント")
    class AccessControl {

        @Test
        @DisplayName("フラグOFF_FEATURE_DISABLED例外_503相当")
        void フラグOFF_FEATURE_DISABLED例外_503相当() {
            given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_ID))
                    .willReturn(Optional.of(ORG_ID));
            willThrow(new BusinessException(ShiftBudgetErrorCode.FEATURE_DISABLED))
                    .given(featureService).requireEnabled(ORG_ID);

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.MEMBER_AVG, null, null);

            assertThatThrownBy(() -> calcService.calculateRequiredSlots(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ShiftBudgetErrorCode.FEATURE_DISABLED);

            // 権限チェック・集計サービスは呼ばれない
            verify(accessControlService, never())
                    .checkPermission(anyLong(), anyLong(), any(), any());
            verify(aggregationService, never()).aggregate(any());
        }

        @Test
        @DisplayName("MANAGE_SHIFTS権限なし_BusinessException_集計なし")
        void MANAGE_SHIFTS権限なし_BusinessException_集計なし() {
            given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_ID))
                    .willReturn(Optional.of(ORG_ID));
            // featureService は通る（doNothing デフォルト）
            willThrow(new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .given(accessControlService)
                    .checkPermission(eq(USER_ID), eq(TEAM_ID), eq("TEAM"), eq("MANAGE_SHIFTS"));

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.MEMBER_AVG, null, null);

            assertThatThrownBy(() -> calcService.calculateRequiredSlots(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            com.mannschaft.app.common.CommonErrorCode.COMMON_002);

            verify(aggregationService, never()).aggregate(any());
        }

        @Test
        @DisplayName("team_id組織不在_TEAM_NOT_FOUND例外_IDOR対策404相当")
        void team_id組織不在_TEAM_NOT_FOUND例外_IDOR対策404相当() {
            given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_ID))
                    .willReturn(Optional.empty());

            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.MEMBER_AVG, null, null);

            assertThatThrownBy(() -> calcService.calculateRequiredSlots(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ShiftBudgetErrorCode.TEAM_NOT_FOUND);
        }
    }

    // ────────────────────────────────────────────────────────
    // バリデーション
    // ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("バリデーション")
    class Validation {

        @Test
        @DisplayName("slot_hours_0.1_INVALID_SLOT_HOURS例外_400相当")
        void slot_hours_0_1_INVALID_SLOT_HOURS例外_400相当() {
            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("0.1"),
                    RateMode.MEMBER_AVG, null, null);

            assertThatThrownBy(() -> calcService.calculateRequiredSlots(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ShiftBudgetErrorCode.INVALID_SLOT_HOURS);
        }

        @Test
        @DisplayName("slot_hours_25_INVALID_SLOT_HOURS例外_400相当")
        void slot_hours_25_INVALID_SLOT_HOURS例外_400相当() {
            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("25"),
                    RateMode.MEMBER_AVG, null, null);

            assertThatThrownBy(() -> calcService.calculateRequiredSlots(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ShiftBudgetErrorCode.INVALID_SLOT_HOURS);
        }

        @Test
        @DisplayName("budget負数_INVALID_BUDGET_AMOUNT例外")
        void budget負数_INVALID_BUDGET_AMOUNT例外() {
            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("-1"), new BigDecimal("4.0"),
                    RateMode.MEMBER_AVG, null, null);

            assertThatThrownBy(() -> calcService.calculateRequiredSlots(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ShiftBudgetErrorCode.INVALID_BUDGET_AMOUNT);
        }

        @Test
        @DisplayName("MEMBER_AVG_team_id_null_TEAM_NOT_FOUND例外")
        void MEMBER_AVG_team_id_null_TEAM_NOT_FOUND例外() {
            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    null, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.MEMBER_AVG, null, null);

            assertThatThrownBy(() -> calcService.calculateRequiredSlots(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ShiftBudgetErrorCode.TEAM_NOT_FOUND);
        }

        @Test
        @DisplayName("POSITION_AVG_空配列_EMPTY_POSITION_LIST例外")
        void POSITION_AVG_空配列_EMPTY_POSITION_LIST例外() {
            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.POSITION_AVG, null, List.of());

            assertThatThrownBy(() -> calcService.calculateRequiredSlots(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ShiftBudgetErrorCode.EMPTY_POSITION_LIST);
        }

        @Test
        @DisplayName("POSITION_AVG_required_count_0_INVALID_REQUIRED_COUNT例外")
        void POSITION_AVG_required_count_0_INVALID_REQUIRED_COUNT例外() {
            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    TEAM_ID, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.POSITION_AVG, null,
                    List.of(new PositionRequiredCount(1L, 0)));

            assertThatThrownBy(() -> calcService.calculateRequiredSlots(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ShiftBudgetErrorCode.INVALID_REQUIRED_COUNT);
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

            assertThatThrownBy(() -> calcService.calculateRequiredSlots(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ShiftBudgetErrorCode.DUPLICATE_POSITION_ID);
        }

        @Test
        @DisplayName("EXPLICIT_avg_hourly_rate_null_MISSING_EXPLICIT_RATE例外")
        void EXPLICIT_avg_hourly_rate_null_MISSING_EXPLICIT_RATE例外() {
            RequiredSlotsRequest req = new RequiredSlotsRequest(
                    null, new BigDecimal("300000"), new BigDecimal("4.0"),
                    RateMode.EXPLICIT, null, null);

            assertThatThrownBy(() -> calcService.calculateRequiredSlots(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ShiftBudgetErrorCode.MISSING_EXPLICIT_RATE);
        }
    }

    // ────────────────────────────────────────────────────────
    // EXPLICIT モードの team_id なしフロー
    // ────────────────────────────────────────────────────────

    @Test
    @DisplayName("EXPLICIT_team_id_null_純粋計算フロー_フラグだけ判定")
    void EXPLICIT_team_id_null_純粋計算フロー_フラグだけ判定() {
        // team_id 指定なし EXPLICIT は権限チェック不要・組織解決不要
        given(aggregationService.aggregate(any(RequiredSlotsRequest.class)))
                .willReturn(new HourlyRateAggregationService.AggregationResult(
                        new BigDecimal("1500"), List.of(), null));

        RequiredSlotsRequest req = new RequiredSlotsRequest(
                null, new BigDecimal("300000"), new BigDecimal("4.0"),
                RateMode.EXPLICIT, new BigDecimal("1500"), null);

        RequiredSlotsResponse resp = calcService.calculateRequiredSlots(req);

        // floor(300000 / (1500 * 4)) = 50
        assertThat(resp.requiredSlots()).isEqualTo(50L);
        verify(featureService).requireEnabled(null);
        verify(accessControlService, never())
                .checkPermission(anyLong(), anyLong(), any(), any());
    }
}
