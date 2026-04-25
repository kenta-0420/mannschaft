package com.mannschaft.app.survey;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.survey.dto.RespondentResponse;
import com.mannschaft.app.survey.dto.SurveyResultResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.entity.SurveyTargetEntity;
import com.mannschaft.app.survey.repository.SurveyOptionRepository;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyResultViewerRepository;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import com.mannschaft.app.survey.service.SurveyResultService;
import com.mannschaft.app.survey.service.SurveyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

/**
 * {@link SurveyResultService} の単体テスト。
 * 結果の集計・閲覧権限管理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyResultService 単体テスト")
class SurveyResultServiceTest {

    @Mock
    private SurveyQuestionRepository questionRepository;

    @Mock
    private SurveyOptionRepository optionRepository;

    @Mock
    private SurveyResponseRepository responseRepository;

    @Mock
    private SurveyResultViewerRepository resultViewerRepository;

    @Mock
    private SurveyTargetRepository targetRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SurveyService surveyService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private SurveyResultService surveyResultService;

    private static final Long SURVEY_ID = 100L;
    private static final Long USER_ID = 10L;
    private static final Long ADMIN_USER_ID = 1L;
    private static final Long MEMBER_USER_ID = 2L;
    private static final Long CREATOR_USER_ID = 99L;

    /**
     * SurveyEntity の BaseEntity.id をリフレクションで設定する。
     */
    private void setEntityId(SurveyEntity entity, Long id) {
        try {
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (Exception ignored) {
        }
    }

    /**
     * UserEntity をリフレクションで ID 付きで生成する。
     */
    private UserEntity buildUser(Long id, String displayName) {
        UserEntity user = UserEntity.builder()
                .email(id + "@example.com")
                .lastName("姓")
                .firstName("名")
                .displayName(displayName)
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .build();
        try {
            var idField = user.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (Exception ignored) {
        }
        return user;
    }

    /**
     * SurveyTargetEntity を builder で生成する。
     */
    private SurveyTargetEntity buildTarget(Long surveyId, Long userId) {
        return SurveyTargetEntity.builder()
                .surveyId(surveyId)
                .userId(userId)
                .build();
    }

    /**
     * SurveyResponseEntity を builder で生成する（createdAt はリフレクションで設定）。
     */
    private SurveyResponseEntity buildResponse(Long surveyId, Long userId, LocalDateTime createdAt) {
        SurveyResponseEntity response = SurveyResponseEntity.builder()
                .surveyId(surveyId)
                .questionId(1L)
                .userId(userId)
                .build();
        try {
            var field = SurveyResponseEntity.class.getDeclaredField("createdAt");
            field.setAccessible(true);
            field.set(response, createdAt);
        } catch (Exception ignored) {
        }
        return response;
    }

    @Nested
    @DisplayName("getResults")
    class GetResults {

        @Test
        @DisplayName("結果取得_AFTER_RESPONSE_未回答_BusinessException")
        void 結果取得_AFTER_RESPONSE_未回答_BusinessException() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.ALL).createdBy(1L).build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(responseRepository.existsBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> surveyResultService.getResults(SURVEY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.RESULT_ACCESS_DENIED));
        }

        @Test
        @DisplayName("結果取得_AFTER_CLOSE_PUBLISHED状態_BusinessException")
        void 結果取得_AFTER_CLOSE_PUBLISHED状態_BusinessException() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_CLOSE)
                    .distributionMode(DistributionMode.ALL).createdBy(1L).build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);

            // When & Then
            assertThatThrownBy(() -> surveyResultService.getResults(SURVEY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.RESULT_ACCESS_DENIED));
        }

        @Test
        @DisplayName("結果取得_AFTER_RESPONSE_回答済み_正常")
        void 結果取得_AFTER_RESPONSE_回答済み_正常() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.ALL).createdBy(1L).build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(responseRepository.existsBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(true);
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(SURVEY_ID)).willReturn(List.of());
            given(responseRepository.countDistinctUsersBySurveyId(SURVEY_ID)).willReturn(5L);

            // When
            SurveyResultResponse result = surveyResultService.getResults(SURVEY_ID, USER_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getSurveyId()).isEqualTo(SURVEY_ID);
        }

        @Test
        @DisplayName("結果取得_VIEWERS_ONLY_閲覧者でない_BusinessException")
        void 結果取得_VIEWERS_ONLY_閲覧者でない_BusinessException() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("テスト")
                    .resultsVisibility(ResultsVisibility.VIEWERS_ONLY)
                    .distributionMode(DistributionMode.ALL).createdBy(999L).build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(resultViewerRepository.existsBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> surveyResultService.getResults(SURVEY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.RESULT_ACCESS_DENIED));
        }
    }

    @Nested
    @DisplayName("getRespondents — 認可テスト")
    class GetRespondents {

        /**
         * ADMIN 向け共通セットアップ: 対象者2人（userId=10 回答済み, userId=20 未回答）を stub する。
         *
         * @param survey テスト対象のアンケートエンティティ
         */
        private void setupAdminScenario(SurveyEntity survey) {
            UserEntity user1 = buildUser(10L, "回答済みユーザー");
            UserEntity user2 = buildUser(20L, "未回答ユーザー");

            SurveyTargetEntity target1 = buildTarget(SURVEY_ID, 10L);
            SurveyTargetEntity target2 = buildTarget(SURVEY_ID, 20L);

            LocalDateTime respondedAt = LocalDateTime.of(2026, 4, 25, 10, 0);
            SurveyResponseEntity response1 = buildResponse(SURVEY_ID, 10L, respondedAt);

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, 1L, "TEAM")).willReturn(true);
            given(targetRepository.findBySurveyId(SURVEY_ID)).willReturn(List.of(target1, target2));
            given(responseRepository.findBySurveyIdOrderByCreatedAtAsc(SURVEY_ID)).willReturn(List.of(response1));
            given(userRepository.findAllById(anyList())).willReturn(List.of(user1, user2));
        }

        /**
         * ADMIN 結果の検証: 2件返却、回答済み（userId=10）の respondedAt が非 null、未回答（userId=20）が null。
         */
        private void assertAdminResult(List<RespondentResponse> result) {
            assertThat(result).hasSize(2);
            RespondentResponse responded = result.stream()
                    .filter(r -> r.getUserId().equals(10L)).findFirst().orElseThrow();
            RespondentResponse notResponded = result.stream()
                    .filter(r -> r.getUserId().equals(20L)).findFirst().orElseThrow();
            assertThat(responded.getRespondedAt()).isNotNull();
            assertThat(notResponded.getRespondedAt()).isNull();
        }

        @Test
        @DisplayName("ケース1: visibility=HIDDEN, ADMIN → 全件返却（2件）")
        void ケース1_HIDDEN_ADMIN_全件返却() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("回答者テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.ALL)
                    .unrespondedVisibility(UnrespondedVisibility.HIDDEN)
                    .createdBy(CREATOR_USER_ID)
                    .build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            setupAdminScenario(survey);

            // When
            List<RespondentResponse> result = surveyResultService.getRespondents(SURVEY_ID, ADMIN_USER_ID);

            // Then
            assertAdminResult(result);
        }

        @Test
        @DisplayName("ケース2: visibility=CREATOR_AND_ADMIN, ADMIN → 全件返却（2件）")
        void ケース2_CREATOR_AND_ADMIN_ADMIN_全件返却() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("回答者テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.ALL)
                    .unrespondedVisibility(UnrespondedVisibility.CREATOR_AND_ADMIN)
                    .createdBy(CREATOR_USER_ID)
                    .build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            setupAdminScenario(survey);

            // When
            List<RespondentResponse> result = surveyResultService.getRespondents(SURVEY_ID, ADMIN_USER_ID);

            // Then
            assertAdminResult(result);
        }

        @Test
        @DisplayName("ケース3: visibility=ALL_MEMBERS, ADMIN → 全件返却（2件）")
        void ケース3_ALL_MEMBERS_ADMIN_全件返却() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("回答者テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.ALL)
                    .unrespondedVisibility(UnrespondedVisibility.ALL_MEMBERS)
                    .createdBy(CREATOR_USER_ID)
                    .build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            setupAdminScenario(survey);

            // When
            List<RespondentResponse> result = surveyResultService.getRespondents(SURVEY_ID, ADMIN_USER_ID);

            // Then
            assertAdminResult(result);
        }

        @Test
        @DisplayName("ケース4: visibility=HIDDEN, MEMBER → RESPONDENTS_ACCESS_DENIED")
        void ケース4_HIDDEN_MEMBER_アクセス拒否() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("回答者テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.ALL)
                    .unrespondedVisibility(UnrespondedVisibility.HIDDEN)
                    .createdBy(CREATOR_USER_ID)
                    .build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, 1L, "TEAM")).willReturn(false);
            given(resultViewerRepository.existsBySurveyIdAndUserId(SURVEY_ID, MEMBER_USER_ID)).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> surveyResultService.getRespondents(SURVEY_ID, MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.RESPONDENTS_ACCESS_DENIED));
        }

        @Test
        @DisplayName("ケース5: visibility=CREATOR_AND_ADMIN, MEMBER → RESPONDENTS_ACCESS_DENIED")
        void ケース5_CREATOR_AND_ADMIN_MEMBER_アクセス拒否() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("回答者テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.ALL)
                    .unrespondedVisibility(UnrespondedVisibility.CREATOR_AND_ADMIN)
                    .createdBy(CREATOR_USER_ID)
                    .build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, 1L, "TEAM")).willReturn(false);
            given(resultViewerRepository.existsBySurveyIdAndUserId(SURVEY_ID, MEMBER_USER_ID)).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> surveyResultService.getRespondents(SURVEY_ID, MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.RESPONDENTS_ACCESS_DENIED));
        }

        @Test
        @DisplayName("ケース6: visibility=ALL_MEMBERS, MEMBER（対象者） → 未回答者のみ返却")
        void ケース6_ALL_MEMBERS_MEMBER対象者_未回答者のみ返却() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("回答者テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.ALL)
                    .unrespondedVisibility(UnrespondedVisibility.ALL_MEMBERS)
                    .createdBy(CREATOR_USER_ID)
                    .build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            UserEntity respondedUser = buildUser(10L, "回答済みユーザー");
            UserEntity unrespondedUser = buildUser(MEMBER_USER_ID, "未回答ユーザー");

            SurveyTargetEntity target1 = buildTarget(SURVEY_ID, 10L);
            SurveyTargetEntity target2 = buildTarget(SURVEY_ID, MEMBER_USER_ID);

            LocalDateTime respondedAt = LocalDateTime.of(2026, 4, 25, 10, 0);
            SurveyResponseEntity response1 = buildResponse(SURVEY_ID, 10L, respondedAt);

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, 1L, "TEAM")).willReturn(false);
            given(resultViewerRepository.existsBySurveyIdAndUserId(SURVEY_ID, MEMBER_USER_ID)).willReturn(false);
            given(targetRepository.existsBySurveyIdAndUserId(SURVEY_ID, MEMBER_USER_ID)).willReturn(true);
            given(targetRepository.findBySurveyId(SURVEY_ID)).willReturn(List.of(target1, target2));
            given(responseRepository.findBySurveyIdOrderByCreatedAtAsc(SURVEY_ID)).willReturn(List.of(response1));
            given(userRepository.findAllById(anyList())).willReturn(List.of(respondedUser, unrespondedUser));

            // When
            List<RespondentResponse> result = surveyResultService.getRespondents(SURVEY_ID, MEMBER_USER_ID);

            // Then: 未回答者（MEMBER_USER_ID）のみ返却、respondedAt は null
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUserId()).isEqualTo(MEMBER_USER_ID);
            assertThat(result.get(0).getRespondedAt()).isNull();
        }

        @Test
        @DisplayName("ケース7: visibility=ALL_MEMBERS, MEMBER（非対象者） → RESPONDENTS_ACCESS_DENIED")
        void ケース7_ALL_MEMBERS_MEMBER非対象者_アクセス拒否() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("回答者テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.ALL)
                    .unrespondedVisibility(UnrespondedVisibility.ALL_MEMBERS)
                    .createdBy(CREATOR_USER_ID)
                    .build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, 1L, "TEAM")).willReturn(false);
            given(resultViewerRepository.existsBySurveyIdAndUserId(SURVEY_ID, MEMBER_USER_ID)).willReturn(false);
            given(targetRepository.existsBySurveyIdAndUserId(SURVEY_ID, MEMBER_USER_ID)).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> surveyResultService.getRespondents(SURVEY_ID, MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.RESPONDENTS_ACCESS_DENIED));
        }
    }
}
