package com.mannschaft.app.survey;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.survey.controller.SurveyController;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import com.mannschaft.app.survey.dto.SurveyDetailResponse;
import com.mannschaft.app.survey.service.SurveyResultService;
import com.mannschaft.app.survey.service.SurveyService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * M0 フェーズ: SurveyController の scopeType 正準化テスト。
 *
 * <p>URLパス語（"teams"/"organizations"）が Controller の {@code resolveScopeType} で
 * 正準 enum 値（"TEAM"/"ORGANIZATION"）に変換されてから Service に渡されることを検証する。</p>
 *
 * <p>これは「URLパス語入力 → 正準値保存」の実経路を通す test-first テスト。
 * 従来のテストがモックで直接 "TEAM" を渡していたのと異なり、
 * 本テストは "teams" を Controller に入力し、Service mock が "TEAM" で呼ばれることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyController scopeType 正準化テスト（M0）")
class SurveyControllerScopeTypeCanonicalTest {

    private static final Long USER_ID = 10L;
    private static final Long SCOPE_ID_LONG = 1L;
    private static final Long SURVEY_ID = 100L;

    @Nested
    @DisplayName("createSurvey — URLパス語 → 正準値変換")
    class CreateSurvey {

        @Mock
        private SurveyService surveyService;

        @Mock
        private SurveyResultService surveyResultService;

        @Mock
        private TeamService teamService;

        @Mock
        private OrganizationService organizationService;

        @InjectMocks
        private SurveyController controller;

        @Test
        @DisplayName("teams → TEAM に正準化して createSurvey を呼ぶ")
        void teams_がTEAMに正準化されてService呼出() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(teamService.resolveTeamId("my-team-slug")).willReturn(SCOPE_ID_LONG);

                CreateSurveyRequest request = mock(CreateSurveyRequest.class);
                SurveyDetailResponse detail = new SurveyDetailResponse(null, List.of());
                // Service は正準値 "TEAM" で呼ばれる（URLパス語 "teams" ではない）
                given(surveyService.createSurvey(eq("TEAM"), eq(SCOPE_ID_LONG), eq(USER_ID), any()))
                        .willReturn(detail);

                ResponseEntity<ApiResponse<SurveyDetailResponse>> result =
                        controller.createSurvey("teams", "my-team-slug", request);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                // Service に "TEAM"（正準値）が渡ったことを ArgumentCaptor で確認
                ArgumentCaptor<String> scopeTypeCaptor = ArgumentCaptor.forClass(String.class);
                verify(surveyService).createSurvey(
                        scopeTypeCaptor.capture(), eq(SCOPE_ID_LONG), eq(USER_ID), any());
                assertThat(scopeTypeCaptor.getValue())
                        .as("URLパス語 'teams' が正準値 'TEAM' に変換されていること")
                        .isEqualTo("TEAM");
            }
        }

        @Test
        @DisplayName("organizations → ORGANIZATION に正準化して createSurvey を呼ぶ")
        void organizations_がORGANIZATIONに正準化されてService呼出() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(organizationService.resolveOrgId("my-org-slug")).willReturn(SCOPE_ID_LONG);

                CreateSurveyRequest request = mock(CreateSurveyRequest.class);
                SurveyDetailResponse detail = new SurveyDetailResponse(null, List.of());
                // Service は正準値 "ORGANIZATION" で呼ばれる
                given(surveyService.createSurvey(eq("ORGANIZATION"), eq(SCOPE_ID_LONG), eq(USER_ID), any()))
                        .willReturn(detail);

                ResponseEntity<ApiResponse<SurveyDetailResponse>> result =
                        controller.createSurvey("organizations", "my-org-slug", request);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                ArgumentCaptor<String> scopeTypeCaptor = ArgumentCaptor.forClass(String.class);
                verify(surveyService).createSurvey(
                        scopeTypeCaptor.capture(), eq(SCOPE_ID_LONG), eq(USER_ID), any());
                assertThat(scopeTypeCaptor.getValue())
                        .as("URLパス語 'organizations' が正準値 'ORGANIZATION' に変換されていること")
                        .isEqualTo("ORGANIZATION");
            }
        }

        @Test
        @DisplayName("不明な scopeType → ResponseStatusException (HTTP 400 BAD_REQUEST)")
        void 不明なscopeType_ResponseStatusException400() {
            CreateSurveyRequest request = mock(CreateSurveyRequest.class);

            assertThatThrownBy(() -> controller.createSurvey("committees", "some-slug", request))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> {
                        ResponseStatusException rse = (ResponseStatusException) ex;
                        assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(rse.getMessage()).contains("不明な scopeType");
                    });
        }

        @Test
        @DisplayName("不明な scopeType（users）→ ResponseStatusException (HTTP 400 BAD_REQUEST)")
        void users_scopeType_ResponseStatusException400() {
            CreateSurveyRequest request = mock(CreateSurveyRequest.class);

            assertThatThrownBy(() -> controller.createSurvey("users", "123", request))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> {
                        ResponseStatusException rse = (ResponseStatusException) ex;
                        assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });
        }
    }
}
