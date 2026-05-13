package com.mannschaft.app.repairplan.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.repairplan.dto.RepairPlanTimelineResponse;
import com.mannschaft.app.repairplan.service.RepairPlanTimelineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link RepairPlanTimelineController} の単体テスト（F08.8 Phase 3）。
 *
 * <p>Mockito によるユニットテスト。AOP（{@code @RequireRepairPlanModule}）は
 * {@link MockitoExtension} では動作しないため、認可ロジック
 * （{@link AccessControlService#checkMembership}）のみを直接検証する。</p>
 *
 * <p>検証観点:</p>
 * <ul>
 *   <li>GET /teams/{teamId}/repair-plan/timeline → 200 + 正常レスポンス</li>
 *   <li>未所属ユーザー → AccessControlService が BusinessException を投げる</li>
 *   <li>不正な scope（例: "persons"）→ normalizeScopePathSegment が BusinessException を投げる</li>
 *   <li>yearFrom / yearTo クエリパラメータが Service に正しく渡る</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RepairPlanTimelineController 単体テスト")
class RepairPlanTimelineControllerTest {

    @Mock
    private RepairPlanTimelineService timelineService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private RepairPlanTimelineController controller;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 100L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** テスト用の空タイムラインレスポンスを生成する。 */
    private RepairPlanTimelineResponse emptyResponse(String scopeType, Long scopeId) {
        return new RepairPlanTimelineResponse(
                scopeType, scopeId,
                2000, 2034,
                List.of(),
                List.of(),
                Map.of(), Map.of(),
                Map.of(), Map.of()
        );
    }

    // ========================================
    // 正常系
    // ========================================

    @Nested
    @DisplayName("正常系")
    class NormalCases {

        @Test
        @DisplayName("GET /teams/{teamId}/repair-plan/timeline → 200 を返す")
        void チームタイムライン_正常() {
            RepairPlanTimelineResponse mockResponse = emptyResponse("TEAM", TEAM_ID);
            given(timelineService.getTimeline("TEAM", TEAM_ID, null, null))
                    .willReturn(mockResponse);
            doNothing().when(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");

            ResponseEntity<ApiResponse<RepairPlanTimelineResponse>> result =
                    controller.getTimeline("teams", TEAM_ID, null, null);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().getData().scopeType()).isEqualTo("TEAM");
            assertThat(result.getBody().getData().scopeId()).isEqualTo(TEAM_ID);
        }

        @Test
        @DisplayName("GET /organizations/{orgId}/repair-plan/timeline → 200 を返す")
        void 組織タイムライン_正常() {
            Long orgId = 200L;
            RepairPlanTimelineResponse mockResponse = emptyResponse("ORGANIZATION", orgId);
            given(timelineService.getTimeline("ORGANIZATION", orgId, null, null))
                    .willReturn(mockResponse);
            doNothing().when(accessControlService).checkMembership(USER_ID, orgId, "ORGANIZATION");

            ResponseEntity<ApiResponse<RepairPlanTimelineResponse>> result =
                    controller.getTimeline("organizations", orgId, null, null);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().scopeType()).isEqualTo("ORGANIZATION");
        }

        @Test
        @DisplayName("yearFrom / yearTo クエリパラメータが Service に正しく渡る")
        void クエリパラメータが正しく渡る() {
            RepairPlanTimelineResponse mockResponse = emptyResponse("TEAM", TEAM_ID);
            given(timelineService.getTimeline("TEAM", TEAM_ID, 2010, 2040))
                    .willReturn(mockResponse);
            doNothing().when(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");

            ResponseEntity<ApiResponse<RepairPlanTimelineResponse>> result =
                    controller.getTimeline("teams", TEAM_ID, 2010, 2040);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(timelineService).getTimeline("TEAM", TEAM_ID, 2010, 2040);
        }
    }

    // ========================================
    // 認可エラー
    // ========================================

    @Nested
    @DisplayName("認可エラー")
    class AuthorizationErrors {

        @Test
        @DisplayName("未所属ユーザー → AccessControlService が BusinessException を投げる → 403 相当")
        void 未所属ユーザーは403相当() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> controller.getTimeline("teams", TEAM_ID, null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(CommonErrorCode.COMMON_002);

            // Service は呼ばれていないこと
            verifyNoInteractions(timelineService);
        }
    }

    // ========================================
    // 不正スコープ
    // ========================================

    @Nested
    @DisplayName("不正スコープ")
    class InvalidScope {

        @Test
        @DisplayName("不正な scope → BusinessException(COMMON_001) が投げられる")
        void 不正スコープ_例外() {
            assertThatThrownBy(() -> controller.getTimeline("persons", TEAM_ID, null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(CommonErrorCode.COMMON_001);

            verifyNoInteractions(timelineService);
            verifyNoInteractions(accessControlService);
        }
    }
}
