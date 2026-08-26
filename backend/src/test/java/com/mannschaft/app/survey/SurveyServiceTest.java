package com.mannschaft.app.survey;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.survey.event.SurveyPublishedEvent;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
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

    /** 結果閲覧可否の判定点（Issue #2779）。本テストは応答形のみを見るため既定の false で足りる。 */
    @Mock
    private com.mannschaft.app.survey.service.SurveyResultAccessGuard resultAccessGuard;

    /** Issue #2715 CMP-055 lot C-5: newly added i18n dependencies. */
    @Mock private MessageSource messageSource;

    @InjectMocks
    private SurveyService surveyService;

    /**
     * Issue #2715 CMP-055 lot C-5/C-6: the bare MessageSource mock would return null for
     * title/body. Return the supplied default message so existing assertions keep working.
     */
    @org.junit.jupiter.api.BeforeEach
    void stubI18nMessageSource() {
        org.mockito.Mockito.lenient().when(messageSource.getMessage(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    private static final Long SURVEY_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final String SCOPE_TYPE = "TEAM";

    /**
     * 本体ガード（listSurveys/getSurveyDetail/getStats）は内部で
     * {@link SecurityUtils#getCurrentUserId()} を呼ぶため、SecurityContext を張って
     * 認証済みユーザー（USER_ID）を確立する。これにより既存の正常系テスト
     * （createSurvey → getSurveyDetail、getStats 等）が新ガードで COMMON_000 に落ちない。
     * accessControlService モックは既定で void（何もしない）ため所属チェックは通過する。
     * 認可遮断を明示検証する所属ゲートテストは MockedStatic + doThrow で個別に上書きする。
     */
    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

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
                .id(SURVEY_ID).status(SurveyStatus.DRAFT)
                .scope(new SurveyResponse.SurveyScopeDto(SCOPE_TYPE, SCOPE_ID))
                .content(new SurveyResponse.SurveyContentDto("テストアンケート", "説明"))
                .policy(new SurveyResponse.SurveyPolicyDto(false, false,
                        com.mannschaft.app.survey.ResultsVisibility.AFTER_RESPONSE,
                        com.mannschaft.app.survey.UnrespondedVisibility.CREATOR_AND_ADMIN))
                .distribution(new SurveyResponse.SurveyDistributionDto(
                        com.mannschaft.app.survey.DistributionMode.ALL, false, null, null, 0, false))
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
        @DisplayName("締切延長_組織ALL_配下チーム展開の母集団へ事前認可通知（(B)レグ番人）")
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
            // (B) レグ番人: 締切延長通知も publish/remind と同形に notifyAllPreAuthorized で送られ、
            // canView 絞り込みを通さない（配下/直属一般メンバーへ誤 deny で届かないことを担保）。
            verify(notificationHelper).notifyAllPreAuthorizedLocalized(
                    org.mockito.ArgumentMatchers.eq(java.util.List.of(11L, 22L, 33L)),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq(SURVEY_ID),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.eq(SCOPE_ID),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq(USER_ID),
                    any());
            // 旧 canView ゲート付き notifyAll は使わないことも明示（取りこぼし非回帰）。
            verify(notificationHelper, org.mockito.Mockito.never()).notifyAll(
                    org.mockito.ArgumentMatchers.anyList(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any());
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
            // duplicateSurvey は複製直後を非ガード toDetailResponse(savedNew) で返すため、
            // 新規survey の再lookup（findByIdAndScopeTypeAndScopeId）は不要になった。
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

    /**
     * (B) 組織→参加チーム配信 案C フェーズB（御裁可B・匿名保護）。
     * 匿名アンケート × チーム別内訳トグル ON の併用が作成時バリデーションで弾かれることを検証する。
     */
    @Nested
    @DisplayName("createSurvey 匿名×チーム別内訳トグル併用禁止")
    class CreateSurveyTeamBreakdownValidation {

        private CreateSurveyRequest request(boolean anonymous, boolean teamBreakdownEnabled) {
            return new CreateSurveyRequest(
                    "タイトル",            // title
                    null,                  // description
                    anonymous,             // isAnonymous
                    false,                 // allowMultipleSubmissions
                    com.mannschaft.app.survey.ResultsVisibility.AFTER_CLOSE, // resultsVisibility
                    com.mannschaft.app.survey.DistributionMode.ALL,          // distributionMode
                    null,                  // unrespondedVisibility
                    false,                 // autoPostToTimeline
                    null,                  // seriesId
                    null,                  // remindBeforeHours
                    null,                  // startsAt
                    null,                  // expiresAt
                    Collections.emptyList(), // questions
                    null,                  // targetUserIds
                    null,                  // resultViewerUserIds
                    false,                 // includeSupporters
                    teamBreakdownEnabled); // teamBreakdownEnabled
        }

        @Test
        @DisplayName("匿名ON×トグルON_作成時にANONYMOUS_TEAM_BREAKDOWN_CONFLICTで弾かれる")
        void 匿名ON_トグルON_弾かれる() {
            assertThatThrownBy(() ->
                    surveyService.createSurvey("ORGANIZATION", 1L, 10L, request(true, true)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.ANONYMOUS_TEAM_BREAKDOWN_CONFLICT));
            // バリデーションで弾かれ永続化に到達しない
            verify(surveyRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("匿名OFF×トグルON_併用禁止に該当せず保存へ進む")
        void 匿名OFF_トグルON_許容() {
            SurveyEntity saved = SurveyEntity.builder()
                    .scopeType("ORGANIZATION").scopeId(1L).title("タイトル")
                    .teamBreakdownEnabled(true).build();
            ReflectionTestUtils.setField(saved, "id", 777L);
            given(surveyRepository.save(org.mockito.ArgumentMatchers.any(SurveyEntity.class)))
                    .willReturn(saved);
            // 末尾の toDetailResponse（設問ビルド）が NPE しないよう空設問を返す。
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(777L))
                    .willReturn(Collections.emptyList());

            // 例外を投げずに作成経路（save）へ到達することを確認する。
            assertThat(surveyService.createSurvey("ORGANIZATION", 1L, 10L, request(false, true)))
                    .isNotNull();
            verify(surveyRepository).save(org.mockito.ArgumentMatchers.any(SurveyEntity.class));
        }
    }

    /**
     * follow-up④: listSurveys の status クエリパラメータ不正値 → 500 ではなく 400（BusinessException）。
     *
     * <p>真因: {@code SurveyStatus.valueOf(status)} がクライアント入力の不正値で
     * {@code IllegalArgumentException} を投げ、GlobalExceptionHandler の汎用ハンドラに落ちて
     * 500 COMMON_999 になっていた。本来クライアント入力エラーなので {@code parseEnumOrThrow}
     * 流用により {@code SurveyErrorCode.INVALID_ENUM_VALUE}（Severity.WARN → 400）を返す。
     * null/空は「フィルタなし（全件）」として通し続ける（仕様変更なし）。</p>
     */
    @Nested
    @DisplayName("listSurveys status enum 不正値は400(BusinessException)")
    class ListSurveysStatusEnumValidation {

        @Test
        @DisplayName("status不正値(BOGUS)_INVALID_ENUM_VALUEで400")
        void status不正値_400() {
            // When & Then: 修正前は IllegalArgumentException が伝播して 500 になっていた
            assertThatThrownBy(() -> surveyService.listSurveys(
                    SCOPE_TYPE, SCOPE_ID, "BOGUS",
                    org.springframework.data.domain.Pageable.unpaged()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.INVALID_ENUM_VALUE));
            // 不正 enum 値はリポジトリへ到達しない
            verify(surveyRepository, org.mockito.Mockito.never())
                    .findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("status=null_フィルタなし全件クエリが実行される(null 400化しない)")
        void status_null_全件クエリ() {
            org.springframework.data.domain.Page<SurveyEntity> emptyPage =
                    org.springframework.data.domain.Page.empty();
            given(surveyRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    SCOPE_TYPE, SCOPE_ID,
                    org.springframework.data.domain.Pageable.unpaged()))
                    .willReturn(emptyPage);

            // null は例外を投げず全件クエリへ進む
            org.springframework.data.domain.Page<SurveyResponse> result =
                    surveyService.listSurveys(SCOPE_TYPE, SCOPE_ID, null,
                            org.springframework.data.domain.Pageable.unpaged());

            assertThat(result).isNotNull();
            verify(surveyRepository).findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    SCOPE_TYPE, SCOPE_ID,
                    org.springframework.data.domain.Pageable.unpaged());
        }

        @Test
        @DisplayName("status=DRAFT_正常値は従来どおりフィルタありクエリへ")
        void status正常値_DRAFT_フィルタクエリ() {
            org.springframework.data.domain.Page<SurveyEntity> emptyPage =
                    org.springframework.data.domain.Page.empty();
            given(surveyRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    SCOPE_TYPE, SCOPE_ID, SurveyStatus.DRAFT,
                    org.springframework.data.domain.Pageable.unpaged()))
                    .willReturn(emptyPage);

            org.springframework.data.domain.Page<SurveyResponse> result =
                    surveyService.listSurveys(SCOPE_TYPE, SCOPE_ID, "DRAFT",
                            org.springframework.data.domain.Pageable.unpaged());

            assertThat(result).isNotNull();
            verify(surveyRepository).findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    SCOPE_TYPE, SCOPE_ID, SurveyStatus.DRAFT,
                    org.springframework.data.domain.Pageable.unpaged());
        }
    }

    /**
     * 作成経路の enum 項目の不正値は 500 ではなく 400 とする（follow-up② の意図を継承）。
     *
     * <p><b>#2617-1 による設計変更</b>: かつて DTO は enum 項目を {@code String} で受け、
     * Service が {@code parseEnumOrThrow} で {@code SurveyErrorCode.INVALID_ENUM_VALUE}（400）へ
     * 変換していた。現在は DTO 自体が enum 型のため、未知値は Jackson の束縛段階で弾かれ
     * {@code HttpMessageNotReadableException} → 400 となり、Service には到達しえない
     * （＝不正値が DB へ半端に書かれる経路が型で消えた）。
     * よって本クラスでは「束縛段階で弾かれること」と「正当値は従来どおり作成へ到達すること」を検証する。
     * HTTP ステータスとしての 400 は {@code SurveyDetailShapeContractIT} が担保する。</p>
     */
    @Nested
    @DisplayName("createSurvey/addQuestion enum 不正値は束縛段階で拒否(→400)")
    class CreateSurveyEnumValidation {

        private final com.fasterxml.jackson.databind.ObjectMapper boundaryMapper =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.module.paramnames.ParameterNamesModule());

        private CreateSurveyRequest createRequest(
                com.mannschaft.app.survey.ResultsVisibility resultsVisibility,
                com.mannschaft.app.survey.DistributionMode distributionMode,
                com.mannschaft.app.survey.UnrespondedVisibility unrespondedVisibility,
                java.util.List<com.mannschaft.app.survey.dto.CreateQuestionRequest> questions) {
            return new CreateSurveyRequest(
                    "タイトル", null, false, false,
                    resultsVisibility,    // resultsVisibility
                    distributionMode,     // distributionMode
                    unrespondedVisibility, // unrespondedVisibility
                    false, null, null, null, null,
                    questions,            // questions
                    null, null, false, false);
        }

        @Test
        @DisplayName("resultsVisibility不正(ADMIN_ONLY)は束縛段階で拒否(Serviceに到達しない)")
        void resultsVisibility不正_束縛拒否() {
            // 正は ADMINS_ONLY 等。ADMIN_ONLY は定義外（設計書の綴り違いに由来する典型的な誤値）。
            assertThatThrownBy(() -> boundaryMapper.readValue("""
                    {"title":"タイトル","isAnonymous":false,"allowMultipleSubmissions":false,
                     "resultsVisibility":"ADMIN_ONLY","distributionMode":"ALL"}
                    """, CreateSurveyRequest.class))
                    .isInstanceOf(com.fasterxml.jackson.core.JacksonException.class)
                    .hasMessageContaining("ADMIN_ONLY");
            verify(surveyRepository, org.mockito.Mockito.never())
                    .save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("distributionMode不正は束縛段階で拒否")
        void distributionMode不正_束縛拒否() {
            assertThatThrownBy(() -> boundaryMapper.readValue("""
                    {"title":"タイトル","isAnonymous":false,"allowMultipleSubmissions":false,
                     "resultsVisibility":"AFTER_CLOSE","distributionMode":"INVALID_MODE"}
                    """, CreateSurveyRequest.class))
                    .isInstanceOf(com.fasterxml.jackson.core.JacksonException.class)
                    .hasMessageContaining("INVALID_MODE");
            verify(surveyRepository, org.mockito.Mockito.never())
                    .save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("unrespondedVisibility不正は束縛段階で拒否")
        void unrespondedVisibility不正_束縛拒否() {
            assertThatThrownBy(() -> boundaryMapper.readValue("""
                    {"title":"タイトル","isAnonymous":false,"allowMultipleSubmissions":false,
                     "resultsVisibility":"AFTER_CLOSE","distributionMode":"ALL",
                     "unrespondedVisibility":"BOGUS"}
                    """, CreateSurveyRequest.class))
                    .isInstanceOf(com.fasterxml.jackson.core.JacksonException.class)
                    .hasMessageContaining("BOGUS");
            verify(surveyRepository, org.mockito.Mockito.never())
                    .save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("questionType不正(INVALID)_作成同梱設問も束縛段階で拒否")
        void questionType不正_作成同梱_束縛拒否() {
            assertThatThrownBy(() -> boundaryMapper.readValue("""
                    {"title":"タイトル","isAnonymous":false,"allowMultipleSubmissions":false,
                     "resultsVisibility":"AFTER_CLOSE","distributionMode":"ALL",
                     "questions":[{"questionType":"INVALID","questionText":"Q1","isRequired":true}]}
                    """, CreateSurveyRequest.class))
                    .isInstanceOf(com.fasterxml.jackson.core.JacksonException.class)
                    .hasMessageContaining("INVALID");
            verify(surveyRepository, org.mockito.Mockito.never())
                    .save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("addQuestion_questionType不正も束縛段階で拒否(save到達せず)")
        void addQuestion_questionType不正_束縛拒否() {
            assertThatThrownBy(() -> boundaryMapper.readValue(
                    "{\"questionType\":\"INVALID\",\"questionText\":\"Q1\",\"isRequired\":true}",
                    com.mannschaft.app.survey.dto.CreateQuestionRequest.class))
                    .isInstanceOf(com.fasterxml.jackson.core.JacksonException.class)
                    .hasMessageContaining("INVALID");
            verify(questionRepository, org.mockito.Mockito.never())
                    .save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("正常値_従来どおり作成へ到達する(回帰防止)")
        void 正常値_作成到達() {
            SurveyEntity saved = SurveyEntity.builder()
                    .scopeType("ORGANIZATION").scopeId(1L).title("タイトル").build();
            ReflectionTestUtils.setField(saved, "id", 888L);
            given(surveyRepository.save(org.mockito.ArgumentMatchers.any(SurveyEntity.class)))
                    .willReturn(saved);
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(888L))
                    .willReturn(Collections.emptyList());

            CreateSurveyRequest req = createRequest(
                    com.mannschaft.app.survey.ResultsVisibility.AFTER_CLOSE,
                    com.mannschaft.app.survey.DistributionMode.ALL,
                    com.mannschaft.app.survey.UnrespondedVisibility.CREATOR_AND_ADMIN,
                    Collections.emptyList());
            assertThat(surveyService.createSurvey("ORGANIZATION", 1L, 10L, req)).isNotNull();
            verify(surveyRepository).save(org.mockito.ArgumentMatchers.any(SurveyEntity.class));
        }
    }

    /**
     * 軍議③（BE セキュリティ・本体漏洩根治）: アンケート<b>本体</b>の一覧/詳細/集計取得に per-scope 認可を追加。
     *
     * <p>従来 {@code listSurveys}/{@code getSurveyDetail}/{@code getStats} は per-scope の所属チェックが無く、
     * 認証済みかつ他スコープの slug + surveyId を知る任意ユーザーが本体（設問・選択肢）を 200 取得できる
     * 漏洩があった。回覧板 {@code CirculationService.listDocuments} の手本を踏襲し、各メソッド冒頭で
     * {@code accessControlService.checkMembershipOrDescendant(userId, scopeId, scopeType, true)} を通し、
     * 非所属（{@code COMMON_002} = 403）を弾く。</p>
     *
     * <p>{@code ContentVisibilityChecker.canView(SURVEY,...)} は結果（resultsVisibility）専用のため本体ガードに
     * 流用しない（DRAFT 非作成者等を誤 deny する）。本体は per-scope メンバーシップで守る。
     * 応援者は閲覧可（{@code includeSupporters=true}）・組織発は配下再帰（AccessControlService の責務）。</p>
     */
    @Nested
    @DisplayName("本体ガード（listSurveys/getSurveyDetail/getStats の per-scope 認可）")
    class SurveyBodyAuthorization {

        @Test
        @DisplayName("AC-1: 非所属ユーザーの listSurveys は COMMON_002(403) で遮断される（リポジトリに到達しない）")
        void 一覧_非所属はCOMMON_002で遮断() {
            try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
                securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                        .when(accessControlService)
                        .checkMembershipOrDescendant(anyLong(), eq(SCOPE_ID), eq(SCOPE_TYPE), eq(true));

                assertThatThrownBy(() -> surveyService.listSurveys(
                        SCOPE_TYPE, SCOPE_ID, null,
                        org.springframework.data.domain.Pageable.unpaged()))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                                .isEqualTo(CommonErrorCode.COMMON_002));

                // ゲートで弾かれるため一覧クエリには到達しない
                verify(surveyRepository, never())
                        .findByScopeTypeAndScopeIdOrderByCreatedAtDesc(any(), any(), any());
            }
        }

        @Test
        @DisplayName("AC-2: 非所属ユーザーの getSurveyDetail は COMMON_002(403) で遮断される（存在露見前に弾く）")
        void 詳細_非所属はCOMMON_002で遮断() {
            try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
                securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                        .when(accessControlService)
                        .checkMembershipOrDescendant(anyLong(), eq(SCOPE_ID), eq(SCOPE_TYPE), eq(true));

                assertThatThrownBy(() -> surveyService.getSurveyDetail(SCOPE_TYPE, SCOPE_ID, SURVEY_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                                .isEqualTo(CommonErrorCode.COMMON_002));

                // 存在露見前に弾くため findSurveyOrThrow（リポジトリ）には到達しない
                verify(surveyRepository, never())
                        .findByIdAndScopeTypeAndScopeId(any(), any(), any());
            }
        }

        @Test
        @DisplayName("AC-3: 非所属ユーザーの getStats は COMMON_002(403) で遮断される（集計に到達しない）")
        void 集計_非所属はCOMMON_002で遮断() {
            try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
                securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                        .when(accessControlService)
                        .checkMembershipOrDescendant(anyLong(), eq(SCOPE_ID), eq(SCOPE_TYPE), eq(true));

                assertThatThrownBy(() -> surveyService.getStats(SCOPE_TYPE, SCOPE_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                                .isEqualTo(CommonErrorCode.COMMON_002));

                // ゲートで弾かれるため集計クエリには到達しない
                verify(surveyRepository, never())
                        .countByScopeTypeAndScopeIdAndStatus(any(), any(), any());
            }
        }

        @Test
        @DisplayName("AC-4: メンバーの listSurveys は正常に結果を返し、ゲートが includeSupporters=true で呼ばれる")
        void 一覧_メンバーは通過しゲートがincludeSupportersTrueで呼ばれる() {
            try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
                securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doNothing().when(accessControlService)
                        .checkMembershipOrDescendant(anyLong(), eq(SCOPE_ID), eq(SCOPE_TYPE), eq(true));
                given(surveyRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                        eq(SCOPE_TYPE), eq(SCOPE_ID), any()))
                        .willReturn(org.springframework.data.domain.Page.empty());

                org.springframework.data.domain.Page<SurveyResponse> result = surveyService.listSurveys(
                        SCOPE_TYPE, SCOPE_ID, null,
                        org.springframework.data.domain.Pageable.unpaged());

                assertThat(result).isNotNull();
                verify(accessControlService)
                        .checkMembershipOrDescendant(USER_ID, SCOPE_ID, SCOPE_TYPE, true);
            }
        }

        @Test
        @DisplayName("AC-4: メンバーの getSurveyDetail は正常に結果を返し、ゲートが呼ばれる")
        void 詳細_メンバーは通過しゲートが呼ばれる() {
            try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
                securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doNothing().when(accessControlService)
                        .checkMembershipOrDescendant(anyLong(), eq(SCOPE_ID), eq(SCOPE_TYPE), eq(true));
                SurveyEntity entity = createDraftSurvey();
                ReflectionTestUtils.setField(entity, "id", SURVEY_ID);
                given(surveyRepository.findByIdAndScopeTypeAndScopeId(SURVEY_ID, SCOPE_TYPE, SCOPE_ID))
                        .willReturn(Optional.of(entity));
                given(surveyMapper.toSurveyResponse(entity)).willReturn(createSurveyResponse());
                given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(SURVEY_ID))
                        .willReturn(Collections.emptyList());

                assertThat(surveyService.getSurveyDetail(SCOPE_TYPE, SCOPE_ID, SURVEY_ID)).isNotNull();
                verify(accessControlService)
                        .checkMembershipOrDescendant(USER_ID, SCOPE_ID, SCOPE_TYPE, true);
            }
        }

        @Test
        @DisplayName("AC-4: メンバーの getStats は正常に結果を返し、ゲートが呼ばれる")
        void 集計_メンバーは通過しゲートが呼ばれる() {
            try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
                securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doNothing().when(accessControlService)
                        .checkMembershipOrDescendant(anyLong(), eq(SCOPE_ID), eq(SCOPE_TYPE), eq(true));

                SurveyStatsResponse result = surveyService.getStats(SCOPE_TYPE, SCOPE_ID);

                assertThat(result).isNotNull();
                verify(accessControlService)
                        .checkMembershipOrDescendant(USER_ID, SCOPE_ID, SCOPE_TYPE, true);
            }
        }

        @Test
        @DisplayName("AC-5/6: ORGANIZATION スコープでも同一 API 経由で includeSupporters=true でゲートが呼ばれる")
        void 組織スコープ_同一API経由でincludeSupportersTrueで呼ばれる() {
            String orgScopeType = "ORGANIZATION";
            try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
                securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doNothing().when(accessControlService)
                        .checkMembershipOrDescendant(anyLong(), eq(SCOPE_ID), eq(orgScopeType), eq(true));
                given(surveyRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                        eq(orgScopeType), eq(SCOPE_ID), any()))
                        .willReturn(org.springframework.data.domain.Page.empty());

                surveyService.listSurveys(orgScopeType, SCOPE_ID, null,
                        org.springframework.data.domain.Pageable.unpaged());

                // 配下再帰の実判定は AccessControlService の責務。ここでは同一 API を
                // includeSupporters=true で通していることを verify で担保する。
                verify(accessControlService)
                        .checkMembershipOrDescendant(USER_ID, SCOPE_ID, orgScopeType, true);
            }
        }
    }

}
