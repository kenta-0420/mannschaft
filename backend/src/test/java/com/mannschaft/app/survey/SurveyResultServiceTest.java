package com.mannschaft.app.survey;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
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
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;

    @Mock
    private com.mannschaft.app.organization.service.OrganizationMembershipService organizationMembershipService;

    @Mock
    private MediaUrlResolver mediaUrlResolver;

    @InjectMocks
    private SurveyResultService surveyResultService;

    /**
     * 結果閲覧可否の判定点は<b>実物</b>を差し込む（Issue #2779）。
     *
     * <p>モックに置き換えると「403 を投げる経路」と「詳細応答の viewerCanViewResults」が
     * 同じ判定を通っている保証が消えるため、モック化した可視性基盤を包んだ実物を使う。
     * 既存の {@code contentVisibilityChecker} スタブはそのまま素通しで効く。</p>
     */
    @org.junit.jupiter.api.BeforeEach
    void injectRealResultAccessPolicy() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                surveyResultService, "resultAccessPolicy",
                new com.mannschaft.app.survey.service.SurveyResultAccessPolicy(contentVisibilityChecker));
    }

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
            // F00 Phase C: 判定は ContentVisibilityChecker に委譲済。
            // createdBy != USER_ID なので作成者高速パスは効かず、Checker の戻り値で判定される。
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.ALL).createdBy(1L).build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(contentVisibilityChecker.canView(eq(ReferenceType.SURVEY), eq(SURVEY_ID), eq(USER_ID)))
                    .willReturn(false);

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
            given(contentVisibilityChecker.canView(eq(ReferenceType.SURVEY), eq(SURVEY_ID), eq(USER_ID)))
                    .willReturn(false);

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
            // F00 Phase C: Checker が「回答済み」と判定する想定
            given(contentVisibilityChecker.canView(eq(ReferenceType.SURVEY), eq(SURVEY_ID), eq(USER_ID)))
                    .willReturn(true);
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
            given(contentVisibilityChecker.canView(eq(ReferenceType.SURVEY), eq(SURVEY_ID), eq(USER_ID)))
                    .willReturn(false);

            // When & Then
            assertThatThrownBy(() -> surveyResultService.getResults(SURVEY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.RESULT_ACCESS_DENIED));
        }

        @Test
        @DisplayName("F00 Phase C: 作成者本人は Checker をスキップして常に閲覧可（既存挙動の維持）")
        void 結果取得_作成者本人_Checkerスキップで正常() {
            // Given: createdBy = USER_ID
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("テスト")
                    .resultsVisibility(ResultsVisibility.VIEWERS_ONLY)
                    .distributionMode(DistributionMode.ALL).createdBy(USER_ID).build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(SURVEY_ID)).willReturn(List.of());
            given(responseRepository.countDistinctUsersBySurveyId(SURVEY_ID)).willReturn(0L);

            // When
            SurveyResultResponse result = surveyResultService.getResults(SURVEY_ID, USER_ID);

            // Then: Checker は呼ばれない（高速パス）。
            assertThat(result).isNotNull();
            org.mockito.Mockito.verifyNoInteractions(contentVisibilityChecker);
        }
    }

    @Nested
    @DisplayName("getRespondents — 認可テスト")
    class GetRespondents {

        /**
         * ADMIN 向け共通セットアップ: 対象者2人（userId=10 回答済み, userId=20 未回答）を stub する。
         *
         * <p>テストアンケートは {@link DistributionMode#ALL} モードのため、母集団は user_roles 経由で取得する。</p>
         *
         * @param survey テスト対象のアンケートエンティティ
         */
        private void setupAdminScenario(SurveyEntity survey) {
            UserEntity user1 = buildUser(10L, "回答済みユーザー");
            UserEntity user2 = buildUser(20L, "未回答ユーザー");

            LocalDateTime respondedAt = LocalDateTime.of(2026, 4, 25, 10, 0);
            SurveyResponseEntity response1 = buildResponse(SURVEY_ID, 10L, respondedAt);

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, 1L, "TEAM")).willReturn(true);
            given(userRoleRepository.findUserIdsByScope("TEAM", 1L)).willReturn(List.of(10L, 20L));
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

            LocalDateTime respondedAt = LocalDateTime.of(2026, 4, 25, 10, 0);
            SurveyResponseEntity response1 = buildResponse(SURVEY_ID, 10L, respondedAt);

            // ALL モード: user_roles 経由で母集団を取得する
            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, 1L, "TEAM")).willReturn(false);
            given(resultViewerRepository.existsBySurveyIdAndUserId(SURVEY_ID, MEMBER_USER_ID)).willReturn(false);
            given(userRoleRepository.findUserIdsByScope("TEAM", 1L)).willReturn(List.of(10L, MEMBER_USER_ID));
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
            // ALL モード: user_roles に MEMBER_USER_ID が含まれないので isUserInUniverse は false
            given(userRoleRepository.findUserIdsByScope("TEAM", 1L)).willReturn(List.of(10L, 20L));

            // When & Then
            assertThatThrownBy(() -> surveyResultService.getRespondents(SURVEY_ID, MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.RESPONDENTS_ACCESS_DENIED));
        }

        @Test
        @DisplayName("ケース8: ALLモード_user_rolesから母集団取得（F05.4 §1035-1036）")
        void ケース8_ALLモード_user_rolesから母集団取得() {
            // Given: ALL モード、ADMIN 経路で全件返却
            // user_roles にスコープ内 5 名おり、うち 2 名のみ回答済み → 全 5 名返却（has_responded 付き）
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("ALL配信テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.ALL)
                    .unrespondedVisibility(UnrespondedVisibility.HIDDEN)
                    .createdBy(CREATOR_USER_ID)
                    .build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            UserEntity u1 = buildUser(10L, "ユーザー1");
            UserEntity u2 = buildUser(20L, "ユーザー2");
            UserEntity u3 = buildUser(30L, "ユーザー3");
            UserEntity u4 = buildUser(40L, "ユーザー4");
            UserEntity u5 = buildUser(50L, "ユーザー5");

            LocalDateTime respondedAt = LocalDateTime.of(2026, 4, 25, 10, 0);
            SurveyResponseEntity r1 = buildResponse(SURVEY_ID, 10L, respondedAt);
            SurveyResponseEntity r2 = buildResponse(SURVEY_ID, 30L, respondedAt);

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, 1L, "TEAM")).willReturn(true);
            given(userRoleRepository.findUserIdsByScope("TEAM", 1L))
                    .willReturn(List.of(10L, 20L, 30L, 40L, 50L));
            given(responseRepository.findBySurveyIdOrderByCreatedAtAsc(SURVEY_ID))
                    .willReturn(List.of(r1, r2));
            given(userRepository.findAllById(anyList()))
                    .willReturn(List.of(u1, u2, u3, u4, u5));

            // When
            List<RespondentResponse> result = surveyResultService.getRespondents(SURVEY_ID, ADMIN_USER_ID);

            // Then: 母集団 5 名全て返却。回答済み 2 名（10, 30）の respondedAt は非 null
            assertThat(result).hasSize(5);
            long respondedCount = result.stream().filter(RespondentResponse::getHasResponded).count();
            assertThat(respondedCount).isEqualTo(2);
        }

        @Test
        @DisplayName("ケース9: TARGETEDモード_survey_targetsから母集団取得")
        void ケース9_TARGETEDモード_survey_targetsから母集団取得() {
            // Given: TARGETED モード。targets に登録された 2 名が母集団
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("TARGETED配信テスト")
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.TARGETED)
                    .unrespondedVisibility(UnrespondedVisibility.HIDDEN)
                    .createdBy(CREATOR_USER_ID)
                    .build();
            setEntityId(survey, SURVEY_ID);
            survey.publish();

            UserEntity u1 = buildUser(10L, "ユーザー1");
            UserEntity u2 = buildUser(20L, "ユーザー2");

            SurveyTargetEntity t1 = buildTarget(SURVEY_ID, 10L);
            SurveyTargetEntity t2 = buildTarget(SURVEY_ID, 20L);

            LocalDateTime respondedAt = LocalDateTime.of(2026, 4, 25, 10, 0);
            SurveyResponseEntity r1 = buildResponse(SURVEY_ID, 10L, respondedAt);

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, 1L, "TEAM")).willReturn(true);
            given(targetRepository.findBySurveyId(SURVEY_ID)).willReturn(List.of(t1, t2));
            given(responseRepository.findBySurveyIdOrderByCreatedAtAsc(SURVEY_ID))
                    .willReturn(List.of(r1));
            given(userRepository.findAllById(anyList())).willReturn(List.of(u1, u2));

            // When
            List<RespondentResponse> result = surveyResultService.getRespondents(SURVEY_ID, ADMIN_USER_ID);

            // Then: 母集団 2 名（targets ベース）。userRoleRepository は呼ばれない
            assertThat(result).hasSize(2);
        }
    }

    /**
     * (B) 組織→参加チーム配信 案C フェーズB（アンケートのチーム別内訳 by_team）の番人。
     * 御裁可A（全チーム計上）・御裁可B（5名未満マスク）・トグル/匿名による by_team 省略・認可を検証する。
     */
    @Nested
    @DisplayName("getTeamBreakdown チーム別内訳")
    class GetTeamBreakdown {

        private static final Long ORG_ID = 500L;
        private static final Long Q1 = 11L;
        private static final Long OPT_A = 101L;
        private static final Long OPT_B = 102L;

        /** team-breakdown 用の組織アンケートを生成する。 */
        private SurveyEntity buildOrgSurvey(boolean teamBreakdownEnabled, boolean anonymous) {
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("ORGANIZATION").scopeId(ORG_ID).title("内訳テスト")
                    .isAnonymous(anonymous)
                    .teamBreakdownEnabled(teamBreakdownEnabled)
                    .includeSupporters(false)
                    .resultsVisibility(ResultsVisibility.AFTER_CLOSE)
                    .distributionMode(DistributionMode.ALL).createdBy(CREATOR_USER_ID).build();
            setEntityId(survey, SURVEY_ID);
            return survey;
        }

        private com.mannschaft.app.survey.entity.SurveyQuestionEntity singleChoiceQuestion() {
            return com.mannschaft.app.survey.entity.SurveyQuestionEntity.builder()
                    .id(Q1).surveyId(SURVEY_ID).questionType(QuestionType.SINGLE_CHOICE)
                    .questionText("好きな色は?").displayOrder(1).build();
        }

        private List<com.mannschaft.app.survey.entity.SurveyOptionEntity> twoOptions() {
            return List.of(
                    com.mannschaft.app.survey.entity.SurveyOptionEntity.builder()
                            .id(OPT_A).questionId(Q1).optionText("赤").displayOrder(1).build(),
                    com.mannschaft.app.survey.entity.SurveyOptionEntity.builder()
                            .id(OPT_B).questionId(Q1).optionText("青").displayOrder(2).build());
        }

        /** userId が optionId を選んだ回答行を生成する。 */
        private SurveyResponseEntity answer(Long userId, Long optionId) {
            return SurveyResponseEntity.builder()
                    .surveyId(SURVEY_ID).questionId(Q1).userId(userId).optionId(optionId).build();
        }

        private OrganizationMembershipService.TeamRef ref(Long teamId, String name) {
            return new OrganizationMembershipService.TeamRef(teamId, name);
        }

        @Test
        @DisplayName("トグルOFF_byTeamは省略されtotalのみ返る")
        void トグルOFF_byTeam省略() {
            SurveyEntity survey = buildOrgSurvey(false, false);
            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(SURVEY_ID))
                    .willReturn(List.of(singleChoiceQuestion()));
            given(optionRepository.findByQuestionIdOrderByDisplayOrderAsc(Q1)).willReturn(twoOptions());
            given(responseRepository.findBySurveyIdOrderByCreatedAtAsc(SURVEY_ID))
                    .willReturn(List.of(answer(1L, OPT_A)));

            var result = surveyResultService.getTeamBreakdown(SURVEY_ID, ADMIN_USER_ID);

            assertThat(result.getByTeam()).isNull();
            assertThat(result.getTotal().respondentCount()).isEqualTo(1);
            // resolveMemberTeams は呼ばれない（トグルOFF）
            org.mockito.Mockito.verify(organizationMembershipService, org.mockito.Mockito.never())
                    .resolveMemberTeams(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("匿名アンケートはトグルONでもbyTeam省略_二重防御")
        void 匿名はbyTeam省略() {
            SurveyEntity survey = buildOrgSurvey(true, true);
            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(SURVEY_ID))
                    .willReturn(List.of(singleChoiceQuestion()));
            given(optionRepository.findByQuestionIdOrderByDisplayOrderAsc(Q1)).willReturn(twoOptions());
            given(responseRepository.findBySurveyIdOrderByCreatedAtAsc(SURVEY_ID))
                    .willReturn(List.of(answer(1L, OPT_A)));

            var result = surveyResultService.getTeamBreakdown(SURVEY_ID, ADMIN_USER_ID);

            assertThat(result.getByTeam()).isNull();
            org.mockito.Mockito.verify(organizationMembershipService, org.mockito.Mockito.never())
                    .resolveMemberTeams(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("トグルON_複数チーム所属者が全チームに計上_チーム別合計はtotal以上")
        void トグルON_全チーム計上() {
            SurveyEntity survey = buildOrgSurvey(true, false);
            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(SURVEY_ID))
                    .willReturn(List.of(singleChoiceQuestion()));
            given(optionRepository.findByQuestionIdOrderByDisplayOrderAsc(Q1)).willReturn(twoOptions());

            // 7 名が回答（うち user 1 はチームT1とT2を兼任 → 両チームに計上）。
            // T1: users 1,2,3,4,5（5名）/ T2: users 1,6,7（3名）/ 組織直属枠(null): user 5
            List<SurveyResponseEntity> responses = new java.util.ArrayList<>();
            for (long u = 1; u <= 5; u++) {
                responses.add(answer(u, OPT_A));
            }
            responses.add(answer(6L, OPT_B));
            responses.add(answer(7L, OPT_B));
            given(responseRepository.findBySurveyIdOrderByCreatedAtAsc(SURVEY_ID)).willReturn(responses);

            java.util.Map<Long, List<OrganizationMembershipService.TeamRef>> memberTeams = new java.util.HashMap<>();
            memberTeams.put(1L, List.of(ref(1L, "T1"), ref(2L, "T2")));
            memberTeams.put(2L, List.of(ref(1L, "T1")));
            memberTeams.put(3L, List.of(ref(1L, "T1")));
            memberTeams.put(4L, List.of(ref(1L, "T1")));
            memberTeams.put(5L, List.of(ref(1L, "T1"), ref(null, null)));
            memberTeams.put(6L, List.of(ref(2L, "T2")));
            memberTeams.put(7L, List.of(ref(2L, "T2")));
            given(organizationMembershipService.resolveMemberTeams(ORG_ID, false)).willReturn(memberTeams);

            var result = surveyResultService.getTeamBreakdown(SURVEY_ID, ADMIN_USER_ID);

            // total は実人数 7。
            assertThat(result.getTotal().respondentCount()).isEqualTo(7);
            assertThat(result.getByTeam()).isNotNull();

            int sumOfTeamRespondents = result.getByTeam().stream()
                    .mapToInt(SurveyTeamBreakdownItemRespondents()).sum();
            // 御裁可A: のべ人数（T1=5 + T2=3 + null枠=1 = 9）≧ total（7）。
            assertThat(sumOfTeamRespondents).isGreaterThanOrEqualTo(result.getTotal().respondentCount());

            // T1 は 5 名でマスクされず、赤(OPT_A)が 5 票。
            var t1 = result.getByTeam().stream().filter(i -> java.util.Objects.equals(i.teamId(), 1L))
                    .findFirst().orElseThrow();
            assertThat(t1.respondentCount()).isEqualTo(5);
            assertThat(t1.masked()).isFalse();
            long t1RedCount = t1.questionResults().get(0).optionResults().stream()
                    .filter(o -> o.optionId().equals(OPT_A)).findFirst().orElseThrow().count();
            assertThat(t1RedCount).isEqualTo(5);
        }

        @Test
        @DisplayName("5名未満チームはマスクされ設問内訳が空")
        void 五名未満マスク() {
            SurveyEntity survey = buildOrgSurvey(true, false);
            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(SURVEY_ID))
                    .willReturn(List.of(singleChoiceQuestion()));
            given(optionRepository.findByQuestionIdOrderByDisplayOrderAsc(Q1)).willReturn(twoOptions());

            // T2 は 3 名のみ → マスク対象。
            given(responseRepository.findBySurveyIdOrderByCreatedAtAsc(SURVEY_ID))
                    .willReturn(List.of(answer(6L, OPT_B), answer(7L, OPT_B), answer(8L, OPT_A)));
            java.util.Map<Long, List<OrganizationMembershipService.TeamRef>> memberTeams = new java.util.HashMap<>();
            memberTeams.put(6L, List.of(ref(2L, "T2")));
            memberTeams.put(7L, List.of(ref(2L, "T2")));
            memberTeams.put(8L, List.of(ref(2L, "T2")));
            given(organizationMembershipService.resolveMemberTeams(ORG_ID, false)).willReturn(memberTeams);

            var result = surveyResultService.getTeamBreakdown(SURVEY_ID, ADMIN_USER_ID);

            var t2 = result.getByTeam().stream().filter(i -> java.util.Objects.equals(i.teamId(), 2L))
                    .findFirst().orElseThrow();
            assertThat(t2.respondentCount()).isEqualTo(3);
            assertThat(t2.masked()).isTrue();
            assertThat(t2.questionResults()).isEmpty();
        }

        @Test
        @DisplayName("team_id_null枠は組織直接メンバーとして集計される")
        void null枠は組織直接メンバー() {
            SurveyEntity survey = buildOrgSurvey(true, false);
            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(SURVEY_ID))
                    .willReturn(List.of(singleChoiceQuestion()));
            given(optionRepository.findByQuestionIdOrderByDisplayOrderAsc(Q1)).willReturn(twoOptions());

            // 5 名全員が組織直属（teamId=null 枠）。
            List<SurveyResponseEntity> responses = new java.util.ArrayList<>();
            java.util.Map<Long, List<OrganizationMembershipService.TeamRef>> memberTeams = new java.util.HashMap<>();
            for (long u = 1; u <= 5; u++) {
                responses.add(answer(u, OPT_A));
                memberTeams.put(u, List.of(ref(null, null)));
            }
            given(responseRepository.findBySurveyIdOrderByCreatedAtAsc(SURVEY_ID)).willReturn(responses);
            given(organizationMembershipService.resolveMemberTeams(ORG_ID, false)).willReturn(memberTeams);

            var result = surveyResultService.getTeamBreakdown(SURVEY_ID, ADMIN_USER_ID);

            var directGroup = result.getByTeam().stream().filter(i -> i.teamId() == null)
                    .findFirst().orElseThrow();
            assertThat(directGroup.teamName()).isNull();
            assertThat(directGroup.respondentCount()).isEqualTo(5);
            assertThat(directGroup.masked()).isFalse();
        }

        @Test
        @DisplayName("非ADMINは403_BusinessExceptionで集計に到達しない")
        void 非ADMINは403() {
            SurveyEntity survey = buildOrgSurvey(true, false);
            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            org.mockito.BDDMockito.willThrow(new BusinessException(
                            com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(MEMBER_USER_ID, ORG_ID, "ORGANIZATION");

            assertThatThrownBy(() -> surveyResultService.getTeamBreakdown(SURVEY_ID, MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class);
            // 認可で弾かれ集計の入口（questions 取得）に到達しない
            org.mockito.Mockito.verify(questionRepository, org.mockito.Mockito.never())
                    .findBySurveyIdOrderByDisplayOrderAsc(org.mockito.ArgumentMatchers.anyLong());
        }

        /** byTeam の respondentCount を取り出す ToIntFunction（のべ人数の合計検証用）。 */
        private java.util.function.ToIntFunction<com.mannschaft.app.survey.dto.SurveyTeamBreakdownResponse.TeamBreakdownItem>
                SurveyTeamBreakdownItemRespondents() {
            return com.mannschaft.app.survey.dto.SurveyTeamBreakdownResponse.TeamBreakdownItem::respondentCount;
        }
    }
}
