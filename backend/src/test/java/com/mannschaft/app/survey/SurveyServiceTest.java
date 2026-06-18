package com.mannschaft.app.survey;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.survey.event.SurveyPublishedEvent;
import com.mannschaft.app.survey.dto.DuplicateSurveyRequest;
import com.mannschaft.app.survey.dto.SurveyResponse;
import com.mannschaft.app.survey.dto.SurveyStatsResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.repository.SurveyOptionRepository;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyResultViewerRepository;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import com.mannschaft.app.survey.service.SurveyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SurveyService} の単体テスト。
 * アンケートのCRUD・ライフサイクル管理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyService 単体テスト")
class SurveyServiceTest {

    @Mock
    private SurveyRepository surveyRepository;

    @Mock
    private SurveyQuestionRepository questionRepository;

    @Mock
    private SurveyOptionRepository optionRepository;

    @Mock
    private SurveyTargetRepository targetRepository;

    @Mock
    private SurveyResultViewerRepository resultViewerRepository;

    @Mock
    private SurveyResponseRepository responseRepository;

    @Mock
    private SurveyMapper surveyMapper;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private NotificationHelper notificationHelper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private OrganizationMembershipService organizationMembershipService;

    @InjectMocks
    private SurveyService surveyService;

    private static final Long SURVEY_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final String SCOPE_TYPE = "TEAM";

    private SurveyEntity createDraftSurvey() {
        return SurveyEntity.builder()
                .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).title("テストアンケート")
                .description("説明").isAnonymous(false).allowMultipleSubmissions(false)
                .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                .distributionMode(DistributionMode.ALL)
                .createdBy(USER_ID).build();
    }

    private SurveyEntity createPublishedSurvey() {
        SurveyEntity entity = createDraftSurvey();
        entity.publish();
        return entity;
    }

    private SurveyResponse createSurveyResponse() {
        return SurveyResponse.builder()
                .id(SURVEY_ID).status("DRAFT")
                .scope(new SurveyResponse.SurveyScopeDto(SCOPE_TYPE, SCOPE_ID))
                .content(new SurveyResponse.SurveyContentDto("テストアンケート", "説明"))
                .policy(new SurveyResponse.SurveyPolicyDto(false, false, "AFTER_RESPONSE", "CREATOR_AND_ADMIN"))
                .distribution(new SurveyResponse.SurveyDistributionDto("ALL", false, null, null, 0, false))
                .schedule(new SurveyResponse.SurveyScheduleDto(null, null, null, null))
                .stats(new SurveyResponse.SurveyStatsDto(0, 0))
                .audit(new SurveyResponse.SurveyAuditDto(null, USER_ID, null, null))
                .build();
    }

    @Nested
    @DisplayName("publishSurvey")
    class PublishSurvey {

        @Test
        @DisplayName("アンケート公開_正常_PUBLISHED状態に遷移")
        void アンケート公開_正常_PUBLISHED状態に遷移() {
            // Given
            SurveyEntity entity = createDraftSurvey();
            ReflectionTestUtils.setField(entity, "id", SURVEY_ID);
            SurveyResponse response = createSurveyResponse();

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(questionRepository.countBySurveyId(SURVEY_ID)).willReturn(3L);
            given(surveyRepository.save(entity)).willReturn(entity);
            given(surveyMapper.toSurveyResponse(entity)).willReturn(response);

            // When
            surveyService.publishSurvey(SCOPE_TYPE, SCOPE_ID, SURVEY_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(SurveyStatus.PUBLISHED);
        }

        @Test
        @DisplayName("アンケート公開_正常_SurveyPublishedEvent発火_配信母集団解決はリスナー委譲")
        void アンケート公開_正常_SurveyPublishedEvent発火() {
            // Given
            SurveyEntity entity = createDraftSurvey();
            ReflectionTestUtils.setField(entity, "id", SURVEY_ID);
            SurveyResponse response = createSurveyResponse();

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(questionRepository.countBySurveyId(SURVEY_ID)).willReturn(3L);
            given(surveyRepository.save(entity)).willReturn(entity);
            given(surveyMapper.toSurveyResponse(entity)).willReturn(response);

            // When
            surveyService.publishSurvey(SCOPE_TYPE, SCOPE_ID, SURVEY_ID);

            // Then: 公開時通知は AFTER_COMMIT・非同期化のため、ここでは SurveyPublishedEvent の
            // 発火のみを検証する（母集団解決・notifyAll はリスナー側）。
            ArgumentCaptor<SurveyPublishedEvent> captor =
                    ArgumentCaptor.forClass(SurveyPublishedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            SurveyPublishedEvent published = captor.getValue();
            assertThat(published.getSurveyId()).isEqualTo(SURVEY_ID);
            assertThat(published.getScopeType()).isEqualTo(SCOPE_TYPE);
            assertThat(published.getScopeId()).isEqualTo(SCOPE_ID);
            assertThat(published.getDistributionMode()).isEqualTo(DistributionMode.ALL);
            assertThat(published.isIncludeSupporters()).isFalse();
        }

        @Test
        @DisplayName("アンケート公開_PUBLISHED状態_BusinessException")
        void アンケート公開_PUBLISHED状態_BusinessException() {
            // Given
            SurveyEntity entity = createPublishedSurvey();

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> surveyService.publishSurvey(SCOPE_TYPE, SCOPE_ID, SURVEY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.INVALID_SURVEY_STATUS));
        }

        @Test
        @DisplayName("アンケート公開_設問なし_BusinessException")
        void アンケート公開_設問なし_BusinessException() {
            // Given
            SurveyEntity entity = createDraftSurvey();

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(questionRepository.countBySurveyId(SURVEY_ID)).willReturn(0L);

            // When & Then
            assertThatThrownBy(() -> surveyService.publishSurvey(SCOPE_TYPE, SCOPE_ID, SURVEY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.NO_QUESTIONS));
        }
    }

    @Nested
    @DisplayName("closeSurvey")
    class CloseSurvey {

        @Test
        @DisplayName("アンケート締め切り_正常_CLOSED状態に遷移")
        void アンケート締め切り_正常_CLOSED状態に遷移() {
            // Given
            SurveyEntity entity = createPublishedSurvey();
            SurveyResponse response = createSurveyResponse();

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(surveyRepository.save(entity)).willReturn(entity);
            given(surveyMapper.toSurveyResponse(entity)).willReturn(response);

            // When
            surveyService.closeSurvey(SCOPE_TYPE, SCOPE_ID, SURVEY_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(SurveyStatus.CLOSED);
        }

        @Test
        @DisplayName("アンケート締め切り_DRAFT状態_BusinessException")
        void アンケート締め切り_DRAFT状態_BusinessException() {
            // Given
            SurveyEntity entity = createDraftSurvey();

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> surveyService.closeSurvey(SCOPE_TYPE, SCOPE_ID, SURVEY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.INVALID_SURVEY_STATUS));
        }
    }

    @Nested
    @DisplayName("deleteSurvey")
    class DeleteSurvey {

        @Test
        @DisplayName("アンケート削除_正常_論理削除実行")
        void アンケート削除_正常_論理削除実行() {
            // Given
            SurveyEntity entity = createDraftSurvey();
            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When
            surveyService.deleteSurvey(SCOPE_TYPE, SCOPE_ID, SURVEY_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(surveyRepository).save(entity);
        }
    }

    @Nested
    @DisplayName("deleteQuestion")
    class DeleteQuestion {

        @Test
        @DisplayName("設問削除_正常_設問と選択肢削除")
        void 設問削除_正常_設問と選択肢削除() {
            // Given
            Long questionId = 50L;
            SurveyEntity survey = createDraftSurvey();
            SurveyQuestionEntity question = SurveyQuestionEntity.builder()
                    .surveyId(SURVEY_ID).questionType(QuestionType.SINGLE_CHOICE)
                    .questionText("質問").isRequired(true).build();

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(survey));
            given(questionRepository.findById(questionId)).willReturn(Optional.of(question));

            // When
            surveyService.deleteQuestion(SCOPE_TYPE, SCOPE_ID, SURVEY_ID, questionId);

            // Then
            verify(optionRepository).deleteByQuestionId(questionId);
            verify(questionRepository).delete(question);
        }

        @Test
        @DisplayName("設問削除_存在しない_BusinessException")
        void 設問削除_存在しない_BusinessException() {
            // Given
            SurveyEntity survey = createDraftSurvey();
            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(survey));
            given(questionRepository.findById(999L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> surveyService.deleteQuestion(SCOPE_TYPE, SCOPE_ID, SURVEY_ID, 999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.QUESTION_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("extendDeadline")
    class ExtendDeadline {

        @Test
        @DisplayName("締切延長_正常_PUBLISHED状態で延長成功")
        void 締切延長_正常_PUBLISHED状態で延長成功() {
            // Given
            SurveyEntity entity = createPublishedSurvey();
            entity.updatePeriod(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7));
            LocalDateTime newDeadline = LocalDateTime.now().plusDays(14);

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, SCOPE_TYPE)).willReturn(true);
            given(surveyRepository.save(entity)).willReturn(entity);
            given(surveyMapper.toSurveyResponse(entity)).willReturn(createSurveyResponse());
            given(userRoleRepository.findUserIdsByScope(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Collections.emptyList());

            // When
            surveyService.extendDeadline(SCOPE_TYPE, SCOPE_ID, SURVEY_ID, newDeadline, USER_ID);

            // Then
            assertThat(entity.getExpiresAt()).isEqualTo(newDeadline);
        }

        @Test
        @DisplayName("締切延長_組織ALL_配下チーム展開の母集団へ通知")
        void 締切延長_組織ALL_配下チーム展開() {
            // Given: 組織スコープ × ALL
            String orgScopeType = "ORGANIZATION";
            SurveyEntity entity = SurveyEntity.builder()
                    .scopeType(orgScopeType).scopeId(SCOPE_ID).title("組織アンケート")
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.ALL)
                    .includeSupporters(false)
                    .createdBy(USER_ID).build();
            entity.publish();
            entity.updatePeriod(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7));
            LocalDateTime newDeadline = LocalDateTime.now().plusDays(14);

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, orgScopeType, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, orgScopeType)).willReturn(true);
            given(surveyRepository.save(entity)).willReturn(entity);
            given(surveyMapper.toSurveyResponse(entity)).willReturn(createSurveyResponse());
            // 配下チーム展開の窓口（組織×ALL のみ呼ばれること）
            given(organizationMembershipService.resolveOrgDistributionUserIds(SCOPE_ID, false))
                    .willReturn(java.util.List.of(11L, 22L, 33L));

            // When
            surveyService.extendDeadline(orgScopeType, SCOPE_ID, SURVEY_ID, newDeadline, USER_ID);

            // Then: 組織配下展開の窓口経由で母集団を解決し、findUserIdsByScope は使わない
            verify(organizationMembershipService).resolveOrgDistributionUserIds(SCOPE_ID, false);
            verify(notificationHelper).notifyAll(
                    org.mockito.ArgumentMatchers.eq(java.util.List.of(11L, 22L, 33L)),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq(SURVEY_ID),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.eq(SCOPE_ID),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq(USER_ID));
        }

        @Test
        @DisplayName("締切延長_短縮試行_BusinessException")
        void 締切延長_短縮試行_BusinessException() {
            // Given
            SurveyEntity entity = createPublishedSurvey();
            LocalDateTime currentDeadline = LocalDateTime.now().plusDays(7);
            entity.updatePeriod(LocalDateTime.now().minusDays(1), currentDeadline);
            LocalDateTime shorterDeadline = LocalDateTime.now().plusDays(3);

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, SCOPE_TYPE)).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> surveyService.extendDeadline(
                    SCOPE_TYPE, SCOPE_ID, SURVEY_ID, shorterDeadline, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.INVALID_NEW_DEADLINE));
        }

        @Test
        @DisplayName("締切延長_権限なし_BusinessException")
        void 締切延長_権限なし_BusinessException() {
            // Given
            Long otherUserId = 999L;
            SurveyEntity entity = createPublishedSurvey();

            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(otherUserId, SCOPE_ID, SCOPE_TYPE)).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> surveyService.extendDeadline(
                    SCOPE_TYPE, SCOPE_ID, SURVEY_ID, LocalDateTime.now().plusDays(30), otherUserId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.OPERATION_PERMISSION_DENIED));
        }
    }

    @Nested
    @DisplayName("duplicateSurvey")
    class DuplicateSurvey {

        @Test
        @DisplayName("アンケート複製_正常_DRAFT状態で新規作成")
        void アンケート複製_正常_DRAFT状態で新規作成() {
            // Given
            SurveyEntity source = createDraftSurvey();
            SurveyEntity newEntity = createDraftSurvey();
            // 複製元 (SURVEY_ID) と、getSurveyDetail 内部で参照される複製後 (savedNew.getId() は null)
            // をそれぞれ specific に stub する。
            // any() を使うと Mockito Strict 配下で先に登録した stub を上書きし
            // UnnecessaryStubbingException が発生するため、引数ごとに分離する。
            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(source));
            given(surveyRepository.findByIdAndScopeTypeAndScopeId(
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.eq(SCOPE_TYPE),
                    org.mockito.ArgumentMatchers.eq(SCOPE_ID)))
                    .willReturn(Optional.of(newEntity));
            given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, SCOPE_TYPE)).willReturn(true);
            // save の呼び出しでは引数のエンティティをそのまま返す
            given(surveyRepository.save(org.mockito.ArgumentMatchers.any(SurveyEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(SURVEY_ID))
                    .willReturn(Collections.emptyList());
            given(targetRepository.findBySurveyId(SURVEY_ID)).willReturn(Collections.emptyList());
            given(resultViewerRepository.findBySurveyId(SURVEY_ID)).willReturn(Collections.emptyList());
            given(surveyMapper.toSurveyResponse(org.mockito.ArgumentMatchers.any()))
                    .willReturn(createSurveyResponse());

            DuplicateSurveyRequest request = new DuplicateSurveyRequest();

            // When
            surveyService.duplicateSurvey(SCOPE_TYPE, SCOPE_ID, SURVEY_ID, request, USER_ID);

            // Then
            verify(surveyRepository, org.mockito.Mockito.atLeastOnce())
                    .save(org.mockito.ArgumentMatchers.argThat(s -> s != null
                            && s.getStatus() == SurveyStatus.DRAFT
                            && s.getTitle().endsWith("（コピー）")));
        }

        @Test
        @DisplayName("アンケート複製_権限なし_BusinessException")
        void アンケート複製_権限なし_BusinessException() {
            // Given
            Long otherUserId = 999L;
            SurveyEntity source = createDraftSurvey();
            given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(source));
            given(accessControlService.isAdminOrAbove(otherUserId, SCOPE_ID, SCOPE_TYPE))
                    .willReturn(false);

            // When & Then
            assertThatThrownBy(() -> surveyService.duplicateSurvey(
                    SCOPE_TYPE, SCOPE_ID, SURVEY_ID, new DuplicateSurveyRequest(), otherUserId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.OPERATION_PERMISSION_DENIED));
        }
    }

    @Nested
    @DisplayName("getStats")
    class GetStats {

        @Test
        @DisplayName("統計取得_正常_カウント集計")
        void 統計取得_正常_カウント集計() {
            // Given
            given(surveyRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, SurveyStatus.DRAFT)).willReturn(2L);
            given(surveyRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, SurveyStatus.PUBLISHED)).willReturn(3L);
            given(surveyRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, SurveyStatus.CLOSED)).willReturn(1L);
            given(surveyRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, SurveyStatus.ARCHIVED)).willReturn(0L);

            // When
            SurveyStatsResponse result = surveyService.getStats(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getTotal()).isEqualTo(6L);
            assertThat(result.getDraft()).isEqualTo(2L);
            assertThat(result.getPublished()).isEqualTo(3L);
        }
    }

}
