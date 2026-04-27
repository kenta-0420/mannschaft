package com.mannschaft.app.survey;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.survey.dto.RemindResponse;
import com.mannschaft.app.survey.dto.SurveyResponse;
import com.mannschaft.app.survey.dto.SurveyStatsResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.entity.SurveyTargetEntity;
import com.mannschaft.app.survey.repository.SurveyOptionRepository;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
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
    private SurveyResponseRepository responseRepository;

    @Mock
    private SurveyMapper surveyMapper;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private NotificationHelper notificationHelper;

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
        return new SurveyResponse(SURVEY_ID, SCOPE_TYPE, SCOPE_ID, "テストアンケート",
                "説明", "DRAFT", false, false, "AFTER_RESPONSE", "ALL",
                "CREATOR_AND_ADMIN",
                false, null, null, 0, null, null, 0, 0, USER_ID, null, null, null, null, null);
    }

    @Nested
    @DisplayName("publishSurvey")
    class PublishSurvey {

        @Test
        @DisplayName("アンケート公開_正常_PUBLISHED状態に遷移")
        void アンケート公開_正常_PUBLISHED状態に遷移() {
            // Given
            SurveyEntity entity = createDraftSurvey();
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
     * F05.4 督促 API の単体テスト。
     * 認可・状態・クールダウン・上限・境界値を網羅的に検証する。
     */
    @Nested
    @DisplayName("remind (F05.4 督促 API)")
    class Remind {

        private static final Long OTHER_USER_ID = 99L;

        /**
         * PUBLISHED 状態の督促可能アンケートを生成する。
         * lastRemindedAt = null / manualRemindCount = 0 が初期値。
         */
        private SurveyEntity createRemindableSurvey() {
            SurveyEntity entity = createDraftSurvey();
            entity.publish();
            return entity;
        }

        /**
         * 配信対象エンティティを構築する（テスト用ヘルパー）。
         */
        private SurveyTargetEntity buildTarget(Long userId) {
            return SurveyTargetEntity.builder().surveyId(SURVEY_ID).userId(userId).build();
        }

        /**
         * 回答済みエンティティを構築する（テスト用ヘルパー）。
         */
        private SurveyResponseEntity buildResponse(Long userId) {
            return SurveyResponseEntity.builder().surveyId(SURVEY_ID).userId(userId)
                    .questionId(1L).build();
        }

        @Test
        @DisplayName("督促_作成者が送信_未回答者へ通知し remindedCount と remainingRemindQuota を返す")
        void 督促_作成者が送信_未回答者へ通知し残回数を返す() {
            // Given: 作成者 = USER_ID。配信対象3名のうち1名が回答済み → 未回答2名
            SurveyEntity survey = createRemindableSurvey();
            given(surveyRepository.findById(SURVEY_ID)).willReturn(Optional.of(survey));
            // 作成者なので AccessControlService は呼ばれないはず
            given(targetRepository.findBySurveyId(SURVEY_ID)).willReturn(List.of(
                    buildTarget(101L), buildTarget(102L), buildTarget(103L)));
            given(responseRepository.findBySurveyIdOrderByCreatedAtAsc(SURVEY_ID)).willReturn(List.of(
                    buildResponse(102L)));
            given(surveyRepository.save(survey)).willReturn(survey);

            // When
            RemindResponse result = surveyService.remind(SURVEY_ID, USER_ID);

            // Then
            assertThat(result.surveyId()).isEqualTo(SURVEY_ID);
            assertThat(result.remindedCount()).isEqualTo(2);
            assertThat(result.remainingRemindQuota()).isEqualTo(2); // 上限3 - 1回目 = 2
            assertThat(result.message()).contains("2");
            assertThat(survey.getManualRemindCount()).isEqualTo(1);
            assertThat(survey.getLastRemindedAt()).isNotNull();
            verify(notificationHelper).notifyAll(
                    eq(List.of(101L, 103L)), eq("SURVEY_RESPONSE_REMINDER"),
                    anyString(), anyString(), eq("SURVEY"), eq(SURVEY_ID),
                    eq(NotificationScopeType.TEAM), eq(SCOPE_ID), anyString(), eq(USER_ID));
        }

        @Test
        @DisplayName("督促_ADMIN以上が送信_認可通過して通知される")
        void 督促_ADMIN以上が送信_認可通過() {
            // Given: 作成者ではない別ユーザーだが ADMIN 権限あり
            SurveyEntity survey = createRemindableSurvey();
            given(surveyRepository.findById(SURVEY_ID)).willReturn(Optional.of(survey));
            given(accessControlService.isAdminOrAbove(OTHER_USER_ID, SCOPE_ID, SCOPE_TYPE))
                    .willReturn(true);
            given(targetRepository.findBySurveyId(SURVEY_ID)).willReturn(List.of(buildTarget(200L)));
            given(responseRepository.findBySurveyIdOrderByCreatedAtAsc(SURVEY_ID))
                    .willReturn(List.of());
            given(surveyRepository.save(survey)).willReturn(survey);

            // When
            RemindResponse result = surveyService.remind(SURVEY_ID, OTHER_USER_ID);

            // Then
            assertThat(result.remindedCount()).isEqualTo(1);
            assertThat(survey.getManualRemindCount()).isEqualTo(1);
            verify(notificationHelper).notifyAll(
                    anyList(), anyString(), anyString(), anyString(),
                    anyString(), anyLong(), any(NotificationScopeType.class),
                    anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("督促_作成者でもADMINでもない_BusinessException SURVEY_014")
        void 督促_認可失敗_REMIND_PERMISSION_DENIED() {
            // Given
            SurveyEntity survey = createRemindableSurvey();
            given(surveyRepository.findById(SURVEY_ID)).willReturn(Optional.of(survey));
            given(accessControlService.isAdminOrAbove(OTHER_USER_ID, SCOPE_ID, SCOPE_TYPE))
                    .willReturn(false);

            // When & Then
            assertThatThrownBy(() -> surveyService.remind(SURVEY_ID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.REMIND_PERMISSION_DENIED));
            verify(notificationHelper, never()).notifyAll(
                    anyList(), anyString(), anyString(), anyString(),
                    anyString(), anyLong(), any(NotificationScopeType.class),
                    anyLong(), anyString(), anyLong());
            verify(surveyRepository, never()).save(any(SurveyEntity.class));
        }

        @Test
        @DisplayName("督促_DRAFT状態_BusinessException INVALID_SURVEY_STATUS")
        void 督促_DRAFT状態_INVALID_SURVEY_STATUS() {
            // Given: DRAFT のまま（publish していない）
            SurveyEntity survey = createDraftSurvey();
            given(surveyRepository.findById(SURVEY_ID)).willReturn(Optional.of(survey));

            // When & Then
            assertThatThrownBy(() -> surveyService.remind(SURVEY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.INVALID_SURVEY_STATUS));
            verify(notificationHelper, never()).notifyAll(
                    anyList(), anyString(), anyString(), anyString(),
                    anyString(), anyLong(), any(NotificationScopeType.class),
                    anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("督促_CLOSED状態_BusinessException INVALID_SURVEY_STATUS")
        void 督促_CLOSED状態_INVALID_SURVEY_STATUS() {
            // Given: PUBLISHED → CLOSED
            SurveyEntity survey = createDraftSurvey();
            survey.publish();
            survey.close();
            given(surveyRepository.findById(SURVEY_ID)).willReturn(Optional.of(survey));

            // When & Then
            assertThatThrownBy(() -> surveyService.remind(SURVEY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.INVALID_SURVEY_STATUS));
        }

        @Test
        @DisplayName("督促_クールダウン中_BusinessException REMIND_COOLDOWN_NOT_ELAPSED")
        void 督促_クールダウン未経過_REMIND_COOLDOWN_NOT_ELAPSED() {
            // Given: 直近23時間前に送信済み（24h 未満）
            SurveyEntity survey = createRemindableSurvey().toBuilder()
                    .lastRemindedAt(LocalDateTime.now().minusHours(23))
                    .manualRemindCount(1)
                    .build();
            given(surveyRepository.findById(SURVEY_ID)).willReturn(Optional.of(survey));

            // When & Then
            assertThatThrownBy(() -> surveyService.remind(SURVEY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.REMIND_COOLDOWN_NOT_ELAPSED));
            verify(notificationHelper, never()).notifyAll(
                    anyList(), anyString(), anyString(), anyString(),
                    anyString(), anyLong(), any(NotificationScopeType.class),
                    anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("督促_上限到達_BusinessException REMIND_QUOTA_EXCEEDED")
        void 督促_上限到達_REMIND_QUOTA_EXCEEDED() {
            // Given: 既に3回送信済み（クールダウンは過ぎている設定）
            SurveyEntity survey = createRemindableSurvey().toBuilder()
                    .lastRemindedAt(LocalDateTime.now().minusDays(2))
                    .manualRemindCount(3)
                    .build();
            given(surveyRepository.findById(SURVEY_ID)).willReturn(Optional.of(survey));

            // When & Then
            assertThatThrownBy(() -> surveyService.remind(SURVEY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.REMIND_QUOTA_EXCEEDED));
            verify(notificationHelper, never()).notifyAll(
                    anyList(), anyString(), anyString(), anyString(),
                    anyString(), anyLong(), any(NotificationScopeType.class),
                    anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("督促_アンケート存在しない_BusinessException SURVEY_NOT_FOUND")
        void 督促_アンケート存在しない_SURVEY_NOT_FOUND() {
            // Given
            given(surveyRepository.findById(SURVEY_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> surveyService.remind(SURVEY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.SURVEY_NOT_FOUND));
        }

        @Test
        @DisplayName("督促_クールダウン丁度24時間経過_正常通過")
        void 督促_境界値_24時間経過は通過() {
            // Given: ぴったり24時間経過（仕様: 24h 以上経過なら OK = `< 24` の判定）
            SurveyEntity survey = createRemindableSurvey().toBuilder()
                    .lastRemindedAt(LocalDateTime.now().minusHours(24).minusMinutes(1))
                    .manualRemindCount(1)
                    .build();
            given(surveyRepository.findById(SURVEY_ID)).willReturn(Optional.of(survey));
            given(targetRepository.findBySurveyId(SURVEY_ID)).willReturn(List.of(buildTarget(300L)));
            given(responseRepository.findBySurveyIdOrderByCreatedAtAsc(SURVEY_ID))
                    .willReturn(List.of());
            given(surveyRepository.save(survey)).willReturn(survey);

            // When
            RemindResponse result = surveyService.remind(SURVEY_ID, USER_ID);

            // Then: 通過する（送信成功）
            assertThat(result.remindedCount()).isEqualTo(1);
            assertThat(survey.getManualRemindCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("督促_境界値_残上限1回時に成功すると残ゼロ")
        void 督促_境界値_残上限1回成功で残ゼロ() {
            // Given: 既に2回送信済み（残1回）
            SurveyEntity survey = createRemindableSurvey().toBuilder()
                    .lastRemindedAt(LocalDateTime.now().minusDays(2))
                    .manualRemindCount(2)
                    .build();
            given(surveyRepository.findById(SURVEY_ID)).willReturn(Optional.of(survey));
            given(targetRepository.findBySurveyId(SURVEY_ID)).willReturn(List.of(buildTarget(400L)));
            given(responseRepository.findBySurveyIdOrderByCreatedAtAsc(SURVEY_ID))
                    .willReturn(List.of());
            given(surveyRepository.save(survey)).willReturn(survey);

            // When
            RemindResponse result = surveyService.remind(SURVEY_ID, USER_ID);

            // Then: 3回目に成功 → 残ゼロ
            assertThat(result.remindedCount()).isEqualTo(1);
            assertThat(result.remainingRemindQuota()).isEqualTo(0);
            assertThat(survey.getManualRemindCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("督促_配信対象未登録_対象0件で送信スキップだが状態更新は実施")
        void 督促_配信対象未登録_送信スキップで状態更新() {
            // Given: targets が空（事前登録なし）
            SurveyEntity survey = createRemindableSurvey();
            given(surveyRepository.findById(SURVEY_ID)).willReturn(Optional.of(survey));
            given(targetRepository.findBySurveyId(SURVEY_ID)).willReturn(List.of());
            given(surveyRepository.save(survey)).willReturn(survey);

            // When
            RemindResponse result = surveyService.remind(SURVEY_ID, USER_ID);

            // Then
            assertThat(result.remindedCount()).isEqualTo(0);
            assertThat(survey.getManualRemindCount()).isEqualTo(1);
            verify(notificationHelper, never()).notifyAll(
                    anyList(), anyString(), anyString(), anyString(),
                    anyString(), anyLong(), any(NotificationScopeType.class),
                    anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("督促_ORGANIZATIONスコープ_NotificationScopeType.ORGANIZATIONで送信")
        void 督促_ORGANIZATIONスコープ_ORG通知() {
            // Given: scopeType = ORGANIZATION の Survey
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("ORGANIZATION").scopeId(SCOPE_ID).title("ORG向け")
                    .description("").isAnonymous(false).allowMultipleSubmissions(false)
                    .resultsVisibility(ResultsVisibility.AFTER_RESPONSE)
                    .distributionMode(DistributionMode.ALL)
                    .createdBy(USER_ID).build();
            survey.publish();
            given(surveyRepository.findById(SURVEY_ID)).willReturn(Optional.of(survey));
            given(targetRepository.findBySurveyId(SURVEY_ID)).willReturn(List.of(buildTarget(500L)));
            given(responseRepository.findBySurveyIdOrderByCreatedAtAsc(SURVEY_ID))
                    .willReturn(List.of());
            given(surveyRepository.save(survey)).willReturn(survey);

            // When
            surveyService.remind(SURVEY_ID, USER_ID);

            // Then
            verify(notificationHelper).notifyAll(
                    anyList(), anyString(), anyString(), anyString(),
                    anyString(), anyLong(),
                    eq(NotificationScopeType.ORGANIZATION), eq(SCOPE_ID),
                    anyString(), anyLong());
        }
    }
}
