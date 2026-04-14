package com.mannschaft.app.advertising.ranking;

import com.mannschaft.app.advertising.ranking.controller.EquipmentRankingController;
import com.mannschaft.app.advertising.ranking.dto.EquipmentTrendingItemResponse;
import com.mannschaft.app.advertising.ranking.dto.EquipmentTrendingResponse;
import com.mannschaft.app.advertising.ranking.dto.OptOutStatusResponse;
import com.mannschaft.app.advertising.ranking.entity.EquipmentRankingEntity;
import com.mannschaft.app.advertising.ranking.service.EquipmentRankingService;
import com.mannschaft.app.advertising.ranking.service.EquipmentRankingService.EquipmentTrendingResult;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * {@link EquipmentRankingController} の単体テスト。
 * ランキング取得・opt-out 操作の HTTP レスポンスコードとボディを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EquipmentRankingController 単体テスト")
class EquipmentRankingControllerTest {

    @Mock private EquipmentRankingService rankingService;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private EquipmentRankingController controller;

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 1L;

    /** テスト用 EquipmentRankingEntity を生成する */
    private EquipmentRankingEntity createRankingEntity() {
        return EquipmentRankingEntity.builder()
                .teamTemplate("soccer_youth")
                .category("ボール")
                .rank((short) 1)
                .itemName("サッカーボール")
                .normalizedName("サッカーボール")
                .amazonAsin("B08XXXXX")
                .teamCount(10)
                .totalQuantity(50)
                .consumeEventCount(5)
                .score(BigDecimal.valueOf(35.0))
                .calculatedAt(LocalDateTime.now())
                .build();
    }

    /** テスト用 EquipmentTrendingResult を生成する */
    private EquipmentTrendingResult createTrendingResult(boolean optOut) {
        return new EquipmentTrendingResult(
                "soccer_youth",
                null,
                optOut,
                List.of(createRankingEntity()),
                LocalDateTime.now(),
                "amazon-tag",
                100L);
    }

    @Nested
    @DisplayName("GET /api/v1/teams/{teamId}/equipment/trending")
    class GetTrending {

        @Test
        @DisplayName("正常系: 200 OK とランキングデータが返る")
        void 正常系_200OKとランキングデータが返る() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                willDoNothing().given(accessControlService)
                        .checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
                given(rankingService.getTrending(TEAM_ID, null, 10, false))
                        .willReturn(createTrendingResult(false));

                // When
                ResponseEntity<ApiResponse<EquipmentTrendingResponse>> result =
                        controller.getTrending(TEAM_ID, null, 10, false);

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody()).isNotNull();
                assertThat(result.getBody().getData().ranking()).hasSize(1);
                assertThat(result.getBody().getData().optOut()).isFalse();
            }
        }

        @Test
        @DisplayName("正常系: linkedOnly=true で ASIN ありのアイテムが返る")
        void 正常系_linkedOnlyはASINありのみ返る() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                willDoNothing().given(accessControlService)
                        .checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
                EquipmentTrendingResult resultWithAsin = createTrendingResult(false);
                given(rankingService.getTrending(TEAM_ID, null, 10, true))
                        .willReturn(resultWithAsin);

                // When
                ResponseEntity<ApiResponse<EquipmentTrendingResponse>> result =
                        controller.getTrending(TEAM_ID, null, 10, true);

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData().ranking())
                        .allMatch(item -> item.amazonAsin() != null);
            }
        }

        @Test
        @DisplayName("異常系: 権限なし（ADMIN/DEPUTY_ADMIN 以外）は BusinessException が発生する")
        void 異常系_権限なしはBusinessException() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                        .when(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

                // When / Then
                assertThatThrownBy(() -> controller.getTrending(TEAM_ID, null, 10, false))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                                .isEqualTo("COMMON_002"));
            }
        }

        @Test
        @DisplayName("異常系: RANKING_NOT_READY は BusinessException（ERANK_001）が発生する")
        void 異常系_RANKING_NOT_READYはBusinessException() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                willDoNothing().given(accessControlService)
                        .checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
                given(rankingService.getTrending(TEAM_ID, null, 10, false))
                        .willThrow(new BusinessException(EquipmentRankingErrorCode.RANKING_NOT_READY));

                // When / Then
                assertThatThrownBy(() -> controller.getTrending(TEAM_ID, null, 10, false))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                                .isEqualTo("ERANK_001"));
            }
        }

        @Test
        @DisplayName("正常系: limit が 20 を超える場合は 20 に切り捨てられる")
        void 正常系_limitは20を上限とする() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                willDoNothing().given(accessControlService)
                        .checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
                given(rankingService.getTrending(TEAM_ID, null, 20, false))
                        .willReturn(createTrendingResult(false));

                // When: limit=50 を渡す → 内部で 20 に切り捨てられる
                controller.getTrending(TEAM_ID, null, 50, false);

                // Then: 20 で呼ばれることを確認
                verify(rankingService).getTrending(TEAM_ID, null, 20, false);
            }
        }
    }

    @Nested
    @DisplayName("POST /api/v1/teams/{teamId}/equipment/trending/opt-out")
    class OptOut {

        @Test
        @DisplayName("正常系: 201 Created と optOut=true が返る")
        void 正常系_201CreatedとoptOutが返る() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

                // When
                ResponseEntity<ApiResponse<OptOutStatusResponse>> result =
                        controller.optOut(TEAM_ID);

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(result.getBody()).isNotNull();
                assertThat(result.getBody().getData().optOut()).isTrue();
                assertThat(result.getBody().getData().teamId()).isEqualTo(TEAM_ID);
            }
        }

        @Test
        @DisplayName("異常系: ADMIN 以外（DEPUTY_ADMIN 等）は BusinessException が発生する")
        void 異常系_ADMIN以外はBusinessException() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

                // When / Then
                assertThatThrownBy(() -> controller.optOut(TEAM_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                                .isEqualTo("COMMON_002"));
            }
        }

        @Test
        @DisplayName("異常系: 既に opt-out 済みは ALREADY_OPT_OUT 例外が発生する")
        void 異常系_既にoptOut済みはALREADY_OPT_OUT例外() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
                doThrow(new BusinessException(EquipmentRankingErrorCode.ALREADY_OPT_OUT))
                        .when(rankingService).optOut(TEAM_ID, USER_ID);

                // When / Then
                assertThatThrownBy(() -> controller.optOut(TEAM_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                                .isEqualTo("ERANK_002"));
            }
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/teams/{teamId}/equipment/trending/opt-out")
    class OptIn {

        @Test
        @DisplayName("正常系: 200 OK と optOut=false が返る")
        void 正常系_200OKとoptOutfalseが返る() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

                // When
                ResponseEntity<ApiResponse<OptOutStatusResponse>> result =
                        controller.optIn(TEAM_ID);

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody()).isNotNull();
                assertThat(result.getBody().getData().optOut()).isFalse();
                assertThat(result.getBody().getData().teamId()).isEqualTo(TEAM_ID);
            }
        }

        @Test
        @DisplayName("異常系: ADMIN 以外は BusinessException が発生する")
        void 異常系_ADMIN以外はBusinessException() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

                // When / Then
                assertThatThrownBy(() -> controller.optIn(TEAM_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                                .isEqualTo("COMMON_002"));
            }
        }

        @Test
        @DisplayName("異常系: opt-out 未設定で OPT_OUT_NOT_FOUND 例外が発生する")
        void 異常系_optOut未設定でOPT_OUT_NOT_FOUND例外() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
                doThrow(new BusinessException(EquipmentRankingErrorCode.OPT_OUT_NOT_FOUND))
                        .when(rankingService).optIn(TEAM_ID);

                // When / Then
                assertThatThrownBy(() -> controller.optIn(TEAM_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                                .isEqualTo("ERANK_003"));
            }
        }
    }
}
