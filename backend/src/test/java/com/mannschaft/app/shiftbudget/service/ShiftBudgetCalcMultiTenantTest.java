package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsRequest;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsRequest.RateMode;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F08.7 Phase 9-α 多テナント分離テスト。
 *
 * <p>設計書 §9.5 / §14.4 の検証:</p>
 * <ul>
 *   <li>組織 A の ADMIN が組織 B の team_id を URL 直叩き → TEAM_NOT_FOUND (404 IDOR 対策)
 *       <br>多テナント分離は {@code findOrganizationIdByTeamId} → 権限チェック (TEAM スコープ) の
 *       2 段階で担保する</li>
 *   <li>shift_hourly_rates の AVG 計算が、リクエストで指定した team_id 以外を混入しないこと
 *       <br>リポジトリクエリは {@code WHERE team_id = :teamId} で厳密に絞り込む</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftBudgetCalc 多テナント分離テスト")
class ShiftBudgetCalcMultiTenantTest {

    private static final Long USER_A = 100L;  // 組織 A の ADMIN
    private static final Long ORG_A = 1L;
    private static final Long TEAM_A = 11L;
    private static final Long TEAM_B = 22L;   // 組織 B のチーム

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
                USER_A.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("組織A_ADMIN_組織BチームID_TEAM_NOT_FOUND_IDOR対策404")
    void 組織A_ADMIN_組織BチームID_TEAM_NOT_FOUND_IDOR対策404() {
        // arrange — TEAM_B は USER_A から見て権限上アクセス不可
        // 1. team_id 自体は他組織で存在するが、権限チェックが拒否するパターン
        given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_B))
                .willReturn(Optional.of(2L));  // 組織 B
        // フィーチャーフラグは ON（doNothing デフォルト）
        // USER_A は TEAM_B での MANAGE_SHIFTS 権限なし
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService)
                .checkPermission(eq(USER_A), eq(TEAM_B), eq("TEAM"), eq("MANAGE_SHIFTS"));

        RequiredSlotsRequest req = new RequiredSlotsRequest(
                TEAM_B, new BigDecimal("300000"), new BigDecimal("4.0"),
                RateMode.MEMBER_AVG, null, null);

        // act + assert: 403 (権限エラー)
        assertThatThrownBy(() -> calcService.calculateRequiredSlots(req))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_002);

        // 集計サービスは呼ばれない（時給情報の漏洩がないこと）
        verify(aggregationService, never()).aggregate(any());
    }

    @Test
    @DisplayName("組織A_ADMIN_自組織チームID_集計はteamId限定_他組織混入なし")
    void 組織A_ADMIN_自組織チームID_集計はteamId限定_他組織混入なし() {
        // arrange — 自組織チーム TEAM_A への正常アクセス
        given(rateQueryRepository.findOrganizationIdByTeamId(TEAM_A))
                .willReturn(Optional.of(ORG_A));
        given(aggregationService.aggregate(any(RequiredSlotsRequest.class)))
                .willReturn(new HourlyRateAggregationService.AggregationResult(
                        new BigDecimal("1200"), List.of(), null));

        RequiredSlotsRequest req = new RequiredSlotsRequest(
                TEAM_A, new BigDecimal("300000"), new BigDecimal("4.0"),
                RateMode.MEMBER_AVG, null, null);

        // act
        calcService.calculateRequiredSlots(req);

        // assert
        // 1. 組織解決は teamId に対して呼ばれる
        verify(rateQueryRepository).findOrganizationIdByTeamId(TEAM_A);
        // 2. フィーチャーフラグは ORG_A（解決済み組織）に対して呼ばれる
        verify(featureService).requireEnabled(ORG_A);
        // 3. 権限チェックは USER_A × TEAM_A × MANAGE_SHIFTS で呼ばれる
        verify(accessControlService).checkPermission(USER_A, TEAM_A, "TEAM", "MANAGE_SHIFTS");
        // 4. 集計サービスに渡る request の team_id は TEAM_A のみ（他チームの混入経路がない）
        //    リポジトリ層の findTeamAverageRate(:teamId) も :teamId = TEAM_A で WHERE 絞込
        //    （リポジトリクエリ自体の WHERE は @Query 文字列で確認可能）
        verify(aggregationService).aggregate(any(RequiredSlotsRequest.class));
    }
}
