package com.mannschaft.app.social.announcement.adapter;

import com.mannschaft.app.social.announcement.AnnouncementContentRequest;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import com.mannschaft.app.survey.QuestionType;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.dto.CreateQuestionRequest;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import com.mannschaft.app.survey.dto.SurveyDetailResponse;
import com.mannschaft.app.survey.dto.SurveyResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.service.SurveyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

/**
 * {@link SurveyAnnouncementAdapter} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyAnnouncementAdapter 単体テスト")
class SurveyAnnouncementAdapterTest {

    @Mock
    private SurveyService surveyService;

    @InjectMocks
    private SurveyAnnouncementAdapter adapter;

    // ──────────────────────────────────────────────────────────────────────────
    // テストデータ定数
    // ──────────────────────────────────────────────────────────────────────────

    private static final Long SCOPE_ID = 20L;
    private static final Long USER_ID = 2L;
    private static final Long SURVEY_ID = 200L;
    private static final LocalDateTime CLOSES_AT = LocalDateTime.of(2026, 7, 31, 23, 59);

    // ──────────────────────────────────────────────────────────────────────────
    // getSourceType
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getSourceType")
    class GetSourceType {

        @Test
        @DisplayName("SURVEY を返すこと")
        void returnsSurveySourceType() {
            // when
            AnnouncementSourceType result = adapter.getSourceType();

            // then
            assertThat(result).isEqualTo(AnnouncementSourceType.SURVEY);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // createContent
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createContent")
    class CreateContent {

        @Test
        @DisplayName("SurveyService.createSurvey() が呼ばれ、アンケート ID が返ること")
        void createsSurveyAndReturnsId() {
            // given
            AnnouncementContentRequest content = AnnouncementContentRequest.builder()
                    .title("満足度アンケート")
                    .description("今期の活動に関するアンケートです")
                    .build();

            given(surveyService.createSurvey(anyString(), anyLong(), anyLong(), any(CreateSurveyRequest.class)))
                    .willReturn(buildSurveyDetailResponse(SURVEY_ID));

            // when
            Long result = adapter.createContent(content, "TEAM", SCOPE_ID, "MEMBERS_AND_ABOVE", USER_ID);

            // then
            verify(surveyService).createSurvey(anyString(), anyLong(), anyLong(), any(CreateSurveyRequest.class));
            assertThat(result).isEqualTo(SURVEY_ID);
        }

        @Test
        @DisplayName("description が content.getDescription() からそのまま渡されること")
        void passesDescriptionFromContent() {
            // given
            String expectedDescription = "チームの練習満足度をお聞かせください";
            AnnouncementContentRequest content = AnnouncementContentRequest.builder()
                    .title("説明フィールド確認")
                    .description(expectedDescription)
                    .build();

            given(surveyService.createSurvey(anyString(), anyLong(), anyLong(), any(CreateSurveyRequest.class)))
                    .willReturn(buildSurveyDetailResponse(SURVEY_ID));

            ArgumentCaptor<CreateSurveyRequest> captor =
                    ArgumentCaptor.forClass(CreateSurveyRequest.class);

            // when
            adapter.createContent(content, "TEAM", SCOPE_ID, "MEMBERS_AND_ABOVE", USER_ID);

            // then
            verify(surveyService).createSurvey(anyString(), anyLong(), anyLong(), captor.capture());
            assertThat(captor.getValue().getDescription()).isEqualTo(expectedDescription);
        }

        @Test
        @DisplayName("closesAt が設定されている場合、CreateSurveyRequest の expiresAt に反映されること")
        void passesClosesAtAsExpiresAt() {
            // given
            AnnouncementContentRequest content = AnnouncementContentRequest.builder()
                    .title("締切日時付きアンケート")
                    .closesAt(CLOSES_AT)
                    .build();

            given(surveyService.createSurvey(anyString(), anyLong(), anyLong(), any(CreateSurveyRequest.class)))
                    .willReturn(buildSurveyDetailResponse(SURVEY_ID));

            ArgumentCaptor<CreateSurveyRequest> captor =
                    ArgumentCaptor.forClass(CreateSurveyRequest.class);

            // when
            adapter.createContent(content, "TEAM", SCOPE_ID, "MEMBERS_AND_ABOVE", USER_ID);

            // then
            verify(surveyService).createSurvey(anyString(), anyLong(), anyLong(), captor.capture());
            assertThat(captor.getValue().getExpiresAt()).isEqualTo(CLOSES_AT);
        }

        @Test
        @DisplayName("resultsVisibility が ResultsVisibility の有効値（AFTER_RESPONSE）であること（SURVEY_024 回帰の防止）")
        void passesValidResultsVisibility() {
            // given
            AnnouncementContentRequest content = AnnouncementContentRequest.builder()
                    .title("結果公開設定の有効値テスト")
                    .build();

            given(surveyService.createSurvey(anyString(), anyLong(), anyLong(), any(CreateSurveyRequest.class)))
                    .willReturn(buildSurveyDetailResponse(SURVEY_ID));

            ArgumentCaptor<CreateSurveyRequest> captor =
                    ArgumentCaptor.forClass(CreateSurveyRequest.class);

            // when（第4引数は target_role。resultsVisibility に流用してはならない）
            adapter.createContent(content, "TEAM", SCOPE_ID, "MEMBERS_AND_ABOVE", USER_ID);

            // then: 実 enum の valueOf を実際に呼ぶ。アダプターが無効文字列（旧バグの
            // "ALL_MEMBERS" 等）を渡したら IllegalArgumentException で必ず落ちる
            // （SurveyService.parseEnumOrThrow が SURVEY_024 を投げる経路をモック無しで補完）。
            verify(surveyService).createSurvey(anyString(), anyLong(), anyLong(), captor.capture());
            ResultsVisibility resultsVisibility = captor.getValue().getResultsVisibility();
            assertThatCode(() -> ResultsVisibility.valueOf(resultsVisibility.name()))
                    .doesNotThrowAnyException();
            assertThat(resultsVisibility).isEqualTo(ResultsVisibility.AFTER_RESPONSE);
        }

        @Test
        @DisplayName("target_role が resultsVisibility に流用されていないこと")
        void doesNotUseTargetRoleAsResultsVisibility() {
            // given
            AnnouncementContentRequest content = AnnouncementContentRequest.builder()
                    .title("target_role 非流用テスト")
                    .build();

            given(surveyService.createSurvey(anyString(), anyLong(), anyLong(), any(CreateSurveyRequest.class)))
                    .willReturn(buildSurveyDetailResponse(SURVEY_ID));

            ArgumentCaptor<CreateSurveyRequest> captor =
                    ArgumentCaptor.forClass(CreateSurveyRequest.class);

            // when
            adapter.createContent(content, "ORGANIZATION", SCOPE_ID, "PUBLIC", USER_ID);

            // then: resultsVisibility に "PUBLIC"（target_role）が漏れていない
            verify(surveyService).createSurvey(anyString(), anyLong(), anyLong(), captor.capture());
            // enum 型化（#2617-1）後は target_role のような非 enum 値はそもそも持ちえないため、
            // 「PUBLIC が漏れていない」ことを既定値との一致で確認する。
            assertThat(captor.getValue().getResultsVisibility())
                    .isEqualTo(ResultsVisibility.AFTER_RESPONSE);
        }

        @Test
        @DisplayName("AC-1: 自由記述の既定設問 1 問を同梱して作成されること（設問ゼロで回答不可の根治）")
        void createsWithDefaultFreeTextQuestion() {
            // given
            AnnouncementContentRequest content = AnnouncementContentRequest.builder()
                    .title("既定設問付き告知アンケート")
                    .build();

            given(surveyService.createSurvey(anyString(), anyLong(), anyLong(), any(CreateSurveyRequest.class)))
                    .willReturn(buildSurveyDetailResponse(SURVEY_ID));

            ArgumentCaptor<CreateSurveyRequest> captor =
                    ArgumentCaptor.forClass(CreateSurveyRequest.class);

            // when
            adapter.createContent(content, "ORGANIZATION", SCOPE_ID, "PUBLIC", USER_ID);

            // then: 設問が 1 件・自由記述（FREE_TEXT）・任意回答（required=false）で同梱される
            verify(surveyService).createSurvey(anyString(), anyLong(), anyLong(), captor.capture());
            List<CreateQuestionRequest> questions = captor.getValue().getQuestions();
            assertThat(questions).hasSize(1);
            CreateQuestionRequest defaultQuestion = questions.get(0);
            assertThat(defaultQuestion.getQuestionType()).isEqualTo(QuestionType.FREE_TEXT);
            assertThat(defaultQuestion.getIsRequired()).isFalse();
            assertThat(defaultQuestion.getQuestionText()).isNotBlank();
        }

        @Test
        @DisplayName("AC-2: createSurvey の直後に publishSurvey が呼ばれ PUBLISHED 化されること")
        void publishesSurveyAfterCreate() {
            // given
            AnnouncementContentRequest content = AnnouncementContentRequest.builder()
                    .title("即公開アンケート")
                    .build();

            given(surveyService.createSurvey(anyString(), anyLong(), anyLong(), any(CreateSurveyRequest.class)))
                    .willReturn(buildSurveyDetailResponse(SURVEY_ID));

            // when
            adapter.createContent(content, "TEAM", SCOPE_ID, "MEMBERS_AND_ABOVE", USER_ID);

            // then: createSurvey → publishSurvey の順で呼ばれ、公開対象は作成された surveyId
            InOrder inOrder = inOrder(surveyService);
            inOrder.verify(surveyService)
                    .createSurvey(anyString(), anyLong(), anyLong(), any(CreateSurveyRequest.class));
            inOrder.verify(surveyService)
                    .publishSurvey(eq("TEAM"), eq(SCOPE_ID), eq(SURVEY_ID));
        }

        @Test
        @DisplayName("AC-4: closesAt 指定時も publishSurvey が呼ばれ公開されること")
        void publishesEvenWhenClosesAtSet() {
            // given
            AnnouncementContentRequest content = AnnouncementContentRequest.builder()
                    .title("締切付き即公開アンケート")
                    .closesAt(CLOSES_AT)
                    .build();

            given(surveyService.createSurvey(anyString(), anyLong(), anyLong(), any(CreateSurveyRequest.class)))
                    .willReturn(buildSurveyDetailResponse(SURVEY_ID));

            // when
            adapter.createContent(content, "ORGANIZATION", SCOPE_ID, "PUBLIC", USER_ID);

            // then
            verify(surveyService).publishSurvey(eq("ORGANIZATION"), eq(SCOPE_ID), eq(SURVEY_ID));
        }

        @Test
        @DisplayName("AC-3: 公開された設問 1 問のアンケートは回答受付状態（isAcceptingResponses=true）になること")
        void publishedSurveyWithOneQuestionAcceptsResponses() {
            // given: DRAFT（既定）の設問 1 問アンケート
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM")
                    .scopeId(SCOPE_ID)
                    .title("回答受付確認")
                    .build();
            assertThat(survey.getStatus()).isEqualTo(SurveyStatus.DRAFT);
            assertThat(survey.isAcceptingResponses()).isFalse();

            // when: publishSurvey 相当の状態遷移
            survey.publish();

            // then: PUBLISHED かつ期限未設定 → 受信直後から回答可能
            assertThat(survey.getStatus()).isEqualTo(SurveyStatus.PUBLISHED);
            assertThat(survey.isAcceptingResponses()).isTrue();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // buildContentUrl
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildContentUrl")
    class BuildContentUrl {

        @Test
        @DisplayName("TEAM スコープの場合、/teams/{scopeId}/surveys/{contentId} 形式になること")
        void buildsTeamScopeUrl() {
            // given
            Long scopeId = 3L;
            Long contentId = 55L;

            // when
            String url = adapter.buildContentUrl("TEAM", scopeId, contentId);

            // then
            assertThat(url).isEqualTo("/teams/3/surveys/55");
        }

        @Test
        @DisplayName("ORGANIZATION スコープの場合、/organizations/{scopeId}/surveys/{contentId} 形式になること")
        void buildsOrganizationScopeUrl() {
            // given
            Long scopeId = 88L;
            Long contentId = 12L;

            // when
            String url = adapter.buildContentUrl("ORGANIZATION", scopeId, contentId);

            // then
            assertThat(url).isEqualTo("/organizations/88/surveys/12");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ヘルパー
    // ──────────────────────────────────────────────────────────────────────────

    private SurveyDetailResponse buildSurveyDetailResponse(Long id) {
        LocalDateTime now = LocalDateTime.now();
        SurveyResponse surveyResponse = SurveyResponse.builder()
                .id(id).status(com.mannschaft.app.survey.SurveyStatus.PUBLISHED)
                .scope(new SurveyResponse.SurveyScopeDto("TEAM", SCOPE_ID))
                .content(new SurveyResponse.SurveyContentDto("テストアンケート", "説明"))
                .policy(new SurveyResponse.SurveyPolicyDto(false, false,
                        ResultsVisibility.AFTER_RESPONSE,
                        com.mannschaft.app.survey.UnrespondedVisibility.CREATOR_AND_ADMIN))
                .distribution(new SurveyResponse.SurveyDistributionDto(
                        com.mannschaft.app.survey.DistributionMode.ALL, false, null, null, 0, false))
                .schedule(new SurveyResponse.SurveyScheduleDto(null, CLOSES_AT, null, null))
                .stats(new SurveyResponse.SurveyStatsDto(0, 0))
                .audit(new SurveyResponse.SurveyAuditDto(1L, USER_ID, now, now))
                .build();
        return SurveyDetailResponse.of(surveyResponse, Collections.emptyList(), true);
    }
}
