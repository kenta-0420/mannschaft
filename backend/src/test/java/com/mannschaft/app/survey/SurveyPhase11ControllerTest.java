package com.mannschaft.app.survey;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.survey.controller.SurveyController;
import com.mannschaft.app.survey.controller.SurveyResponseController;
import com.mannschaft.app.survey.controller.SurveySeriesController;
import com.mannschaft.app.survey.dto.DuplicateSurveyRequest;
import com.mannschaft.app.survey.dto.ExtendDeadlineRequest;
import com.mannschaft.app.survey.dto.SurveyComparisonResponse;
import com.mannschaft.app.survey.dto.SurveyDetailResponse;
import com.mannschaft.app.survey.dto.SurveyResponse;
import com.mannschaft.app.survey.dto.UserResponseAnswerEntry;
import com.mannschaft.app.survey.dto.UserResponseDetailResponse;
import com.mannschaft.app.survey.service.SurveyResponseService;
import com.mannschaft.app.survey.service.SurveyResultService;
import com.mannschaft.app.survey.service.SurveySeriesService;
import com.mannschaft.app.survey.service.SurveyService;
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

import com.mannschaft.app.team.service.TeamService;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mockStatic;

/**
 * Phase 11 第四陣 4-A で追加した 5 endpoint の Controller 単体テスト。
 *
 * <p>HTTP マッピング・Service 委譲・SecurityUtils 経由の認証ユーザーID取得・例外伝播を検証する。
 * 認可エラーの HTTP マッピング（403/404 等）は GlobalExceptionHandler の責務のため、
 * 単体テストでは Service が投げた {@link BusinessException} がそのまま伝播することを確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 11 第四陣 4-A Controller 単体テスト")
class SurveyPhase11ControllerTest {

    private static final Long SURVEY_ID = 100L;
    /** FE から渡る UUID 文字列（publicId）。 */
    private static final String SCOPE_ID = "00000000-0000-7000-8000-000000000001";
    /** resolveTeamId() が返す内部 BIGINT。 */
    private static final Long SCOPE_ID_LONG = 1L;
    /** URL パスに使う scopeType（複数形）。Controller の resolveScopeType で正準値に変換される。 */
    private static final String SCOPE_TYPE = "teams";
    /** Controller が resolveScopeType で変換した正準値。Service mock の期待値に使う。 */
    private static final String CANONICAL_SCOPE_TYPE = "TEAM";
    private static final Long USER_ID = 10L;

    @Nested
    @DisplayName("GET /results/export — exportResults")
    class ExportResults {

        @Mock
        private SurveyService surveyService;

        @Mock
        private SurveyResultService surveyResultService;

        @Mock
        private TeamService teamService;

        @InjectMocks
        private SurveyController controller;

        @Test
        @DisplayName("正常系_200OKとCSVバイト列を返す")
        void 正常系_200OKとCSVバイト列を返す() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(teamService.resolveTeamId(SCOPE_ID)).willReturn(SCOPE_ID_LONG);
                byte[] csv = "回答日時,回答者\n2026-01-01,田中".getBytes();
                // Controller が resolveScopeType で "teams" → "TEAM" に正準化してから Service を呼ぶ
                given(surveyResultService.exportResultsCsv(CANONICAL_SCOPE_TYPE, SCOPE_ID_LONG, SURVEY_ID, USER_ID))
                        .willReturn(csv);

                ResponseEntity<byte[]> result = controller.exportResults(SCOPE_TYPE, SCOPE_ID, SURVEY_ID);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody()).isEqualTo(csv);
                assertThat(result.getHeaders().getContentDisposition().getFilename())
                        .isEqualTo("survey_" + SURVEY_ID + ".csv");
            }
        }

        @Test
        @DisplayName("認可エラー_RESULT_ACCESS_DENIED伝播")
        void 認可エラー_例外伝播() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(teamService.resolveTeamId(SCOPE_ID)).willReturn(SCOPE_ID_LONG);
                // Controller が resolveScopeType で "teams" → "TEAM" に正準化してから Service を呼ぶ
                willThrow(new BusinessException(SurveyErrorCode.RESULT_ACCESS_DENIED))
                        .given(surveyResultService)
                        .exportResultsCsv(CANONICAL_SCOPE_TYPE, SCOPE_ID_LONG, SURVEY_ID, USER_ID);

                assertThatThrownBy(() -> controller.exportResults(SCOPE_TYPE, SCOPE_ID, SURVEY_ID))
                        .isInstanceOf(BusinessException.class);
            }
        }
    }

    @Nested
    @DisplayName("POST /duplicate — duplicateSurvey")
    class Duplicate {

        @Mock
        private SurveyService surveyService;

        @Mock
        private SurveyResultService surveyResultService;

        @Mock
        private TeamService teamService;

        @InjectMocks
        private SurveyController controller;

        @Test
        @DisplayName("正常系_201Createdとdetail返却")
        void 正常系_201Created() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(teamService.resolveTeamId(SCOPE_ID)).willReturn(SCOPE_ID_LONG);
                SurveyDetailResponse detail = SurveyDetailResponse.of(null, List.of(), true);
                // Controller が resolveScopeType で "teams" → "TEAM" に正準化してから Service を呼ぶ
                given(surveyService.duplicateSurvey(
                        org.mockito.ArgumentMatchers.eq(CANONICAL_SCOPE_TYPE),
                        org.mockito.ArgumentMatchers.eq(SCOPE_ID_LONG),
                        org.mockito.ArgumentMatchers.eq(SURVEY_ID),
                        any(),
                        org.mockito.ArgumentMatchers.eq(USER_ID)))
                        .willReturn(detail);

                ResponseEntity<ApiResponse<SurveyDetailResponse>> result = controller.duplicateSurvey(
                        SCOPE_TYPE, SCOPE_ID, SURVEY_ID, new DuplicateSurveyRequest());

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(result.getBody()).isNotNull();
                assertThat(result.getBody().getData()).isEqualTo(detail);
            }
        }
    }

    @Nested
    @DisplayName("POST /extend — extendDeadline")
    class Extend {

        @Mock
        private SurveyService surveyService;

        @Mock
        private SurveyResultService surveyResultService;

        @Mock
        private TeamService teamService;

        @InjectMocks
        private SurveyController controller;

        @Test
        @DisplayName("正常系_200OK")
        void 正常系_200OK() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(teamService.resolveTeamId(SCOPE_ID)).willReturn(SCOPE_ID_LONG);
                LocalDateTime newDeadline = LocalDateTime.now().plusDays(30);
                SurveyResponse response = org.mockito.Mockito.mock(SurveyResponse.class);
                // Controller が resolveScopeType で "teams" → "TEAM" に正準化してから Service を呼ぶ
                given(surveyService.extendDeadline(CANONICAL_SCOPE_TYPE, SCOPE_ID_LONG, SURVEY_ID, newDeadline, USER_ID))
                        .willReturn(response);

                ResponseEntity<ApiResponse<SurveyResponse>> result = controller.extendDeadline(
                        SCOPE_TYPE, SCOPE_ID, SURVEY_ID, new ExtendDeadlineRequest(newDeadline, null));

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData()).isEqualTo(response);
            }
        }

        @Test
        @DisplayName("短縮試行_INVALID_NEW_DEADLINE伝播")
        void 短縮試行_例外伝播() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                given(teamService.resolveTeamId(SCOPE_ID)).willReturn(SCOPE_ID_LONG);
                LocalDateTime shorter = LocalDateTime.now().minusDays(1);
                // Controller が resolveScopeType で "teams" → "TEAM" に正準化してから Service を呼ぶ
                willThrow(new BusinessException(SurveyErrorCode.INVALID_NEW_DEADLINE))
                        .given(surveyService)
                        .extendDeadline(CANONICAL_SCOPE_TYPE, SCOPE_ID_LONG, SURVEY_ID, shorter, USER_ID);

                assertThatThrownBy(() -> controller.extendDeadline(
                        SCOPE_TYPE, SCOPE_ID, SURVEY_ID, new ExtendDeadlineRequest(shorter, null)))
                        .isInstanceOf(BusinessException.class);
            }
        }
    }

    @Nested
    @DisplayName("GET /responses/{userId} — getResponseByUser")
    class GetResponseByUser {

        @Mock
        private SurveyResponseService responseService;

        @InjectMocks
        private SurveyResponseController controller;

        @Test
        @DisplayName("正常系_200OK")
        void 正常系_200OK() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                Long targetUser = 50L;
                UserResponseDetailResponse detail = new UserResponseDetailResponse(
                        SURVEY_ID,
                        new UserResponseDetailResponse.UserSummary(targetUser, "田中 太郎"),
                        LocalDateTime.now(),
                        List.<UserResponseAnswerEntry>of());
                given(responseService.getResponseByUser(SURVEY_ID, targetUser, USER_ID))
                        .willReturn(detail);

                ResponseEntity<ApiResponse<UserResponseDetailResponse>> result =
                        controller.getResponseByUser(SURVEY_ID, targetUser);

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData()).isEqualTo(detail);
            }
        }

        @Test
        @DisplayName("匿名アンケート_ANONYMOUS_RESPONSE_FORBIDDEN伝播")
        void 匿名アンケート_例外伝播() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                willThrow(new BusinessException(SurveyErrorCode.ANONYMOUS_RESPONSE_FORBIDDEN))
                        .given(responseService).getResponseByUser(SURVEY_ID, 50L, USER_ID);

                assertThatThrownBy(() -> controller.getResponseByUser(SURVEY_ID, 50L))
                        .isInstanceOf(BusinessException.class);
            }
        }
    }

    @Nested
    @DisplayName("GET /series/{seriesId}/comparison — compareSeries")
    class CompareSeries {

        @Mock
        private SurveySeriesService seriesService;

        @InjectMocks
        private SurveySeriesController controller;

        @Test
        @DisplayName("正常系_200OK")
        void 正常系_200OK() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                SurveyComparisonResponse cmp = new SurveyComparisonResponse(
                        "menu_satisfaction", List.of(), List.of());
                given(seriesService.compareSeries("menu_satisfaction", USER_ID)).willReturn(cmp);

                ResponseEntity<ApiResponse<SurveyComparisonResponse>> result =
                        controller.compareSeries("menu_satisfaction");

                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody().getData().seriesId()).isEqualTo("menu_satisfaction");
            }
        }

        @Test
        @DisplayName("シリーズ未存在_SERIES_NOT_FOUND伝播")
        void シリーズ未存在_例外伝播() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                willThrow(new BusinessException(SurveyErrorCode.SERIES_NOT_FOUND))
                        .given(seriesService).compareSeries("unknown", USER_ID);

                assertThatThrownBy(() -> controller.compareSeries("unknown"))
                        .isInstanceOf(BusinessException.class);
            }
        }
    }
}
