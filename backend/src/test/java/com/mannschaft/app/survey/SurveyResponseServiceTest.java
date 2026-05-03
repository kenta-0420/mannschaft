package com.mannschaft.app.survey;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.survey.dto.SubmitResponseRequest;
import com.mannschaft.app.survey.dto.SurveyResponseEntry;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import com.mannschaft.app.survey.service.SurveyResponseService;
import com.mannschaft.app.survey.service.SurveyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link SurveyResponseService} の単体テスト。
 * 回答の送信・取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyResponseService 単体テスト")
class SurveyResponseServiceTest {

    @Mock
    private SurveyRepository surveyRepository;

    @Mock
    private SurveyQuestionRepository questionRepository;

    @Mock
    private SurveyResponseRepository responseRepository;

    @Mock
    private SurveyTargetRepository targetRepository;

    @Mock
    private SurveyMapper surveyMapper;

    @Mock
    private SurveyService surveyService;

    @Mock
    private ProxyInputContext proxyInputContext;

    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;

    @InjectMocks
    private SurveyResponseService surveyResponseService;

    private static final Long SURVEY_ID = 100L;
    private static final Long USER_ID = 10L;

    private SurveyEntity createPublishedSurvey() {
        SurveyEntity entity = SurveyEntity.builder()
                .scopeType("TEAM").scopeId(1L).title("テスト")
                .distributionMode(DistributionMode.ALL)
                .allowMultipleSubmissions(false).createdBy(1L).build();
        entity.publish();
        return entity;
    }

    @Nested
    @DisplayName("submitResponse")
    class SubmitResponse {

        @Test
        @DisplayName("回答送信_DRAFT状態_BusinessException")
        void 回答送信_DRAFT状態_BusinessException() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("テスト")
                    .distributionMode(DistributionMode.ALL).createdBy(1L).build();

            SubmitResponseRequest request = new SubmitResponseRequest(List.of());

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);

            // When & Then
            assertThatThrownBy(() -> surveyResponseService.submitResponse(SURVEY_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.INVALID_SURVEY_STATUS));
        }

        @Test
        @DisplayName("回答送信_重複回答不許可_BusinessException")
        void 回答送信_重複回答不許可_BusinessException() {
            // Given
            SurveyEntity survey = createPublishedSurvey();
            SubmitResponseRequest request = new SubmitResponseRequest(List.of());

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(responseRepository.existsBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> surveyResponseService.submitResponse(SURVEY_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.DUPLICATE_RESPONSE));
        }

        @Test
        @DisplayName("回答送信_配信対象外_BusinessException")
        void 回答送信_配信対象外_BusinessException() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("テスト")
                    .distributionMode(DistributionMode.TARGETED)
                    .allowMultipleSubmissions(false).createdBy(1L).build();
            survey.publish();

            SubmitResponseRequest request = new SubmitResponseRequest(List.of());

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(targetRepository.existsBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> surveyResponseService.submitResponse(SURVEY_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.NOT_TARGET_USER));
        }
    }

    @Nested
    @DisplayName("getMyResponses")
    class GetMyResponses {

        @Test
        @DisplayName("自分の回答取得_正常_リスト返却")
        void 自分の回答取得_正常_リスト返却() {
            // Given
            List<SurveyResponseEntity> entities = List.of();
            given(responseRepository.findBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(entities);
            given(surveyMapper.toResponseEntryList(entities)).willReturn(List.of());

            // When
            List<SurveyResponseEntry> result = surveyResponseService.getMyResponses(SURVEY_ID, USER_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 代理入力フロー
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("submitResponse — 代理入力フロー")
    class SubmitResponseProxyFlow {

        private static final Long PROXY_RECORD_ID = 999L;
        private static final Long CONSENT_ID = 50L;

        /** FREE_TEXT・任意回答の設問を1件持つアンケートエンティティを用意する。 */
        private SurveyEntity createPublishedSurveyForProxy() {
            SurveyEntity entity = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("代理テスト")
                    .distributionMode(DistributionMode.ALL)
                    .allowMultipleSubmissions(false).createdBy(1L).build();
            entity.publish();
            return entity;
        }

        /** isRequired=false の FREE_TEXT 設問を返す。 */
        private SurveyQuestionEntity createOptionalFreeTextQuestion(Long questionId) {
            return SurveyQuestionEntity.builder()
                    .id(questionId)
                    .surveyId(SURVEY_ID)
                    .questionType(QuestionType.FREE_TEXT)
                    .questionText("自由記述")
                    .isRequired(false)
                    .displayOrder(1)
                    .build();
        }

        /** 保存済みの SurveyResponseEntity（is_proxy_input=false）を返す。 */
        private SurveyResponseEntity createSavedResponse(Long questionId) {
            return SurveyResponseEntity.builder()
                    .id(1L)
                    .surveyId(SURVEY_ID)
                    .questionId(questionId)
                    .userId(USER_ID)
                    .textResponse("テスト回答")
                    .isProxyInput(false)
                    .build();
        }

        /** is_proxy_input=true で再保存された SurveyResponseEntity。 */
        private SurveyResponseEntity createProxySavedResponse(Long questionId) {
            return SurveyResponseEntity.builder()
                    .id(1L)
                    .surveyId(SURVEY_ID)
                    .questionId(questionId)
                    .userId(USER_ID)
                    .textResponse("テスト回答")
                    .isProxyInput(true)
                    .proxyInputRecordId(PROXY_RECORD_ID)
                    .build();
        }

        @Test
        @DisplayName("代理入力モードの場合: ProxyInputRecordRepository.save() を1回呼ぶこと")
        void 代理入力モード_ProxyInputRecordRepositorySaveを1回呼ぶ() {
            // Given
            final Long questionId = 10L;
            SurveyEntity survey = createPublishedSurveyForProxy();
            SurveyQuestionEntity question = createOptionalFreeTextQuestion(questionId);
            SurveyResponseEntity savedResponse = createSavedResponse(questionId);
            SurveyResponseEntity proxySavedResponse = createProxySavedResponse(questionId);

            ProxyInputRecordEntity proxyRecord = ProxyInputRecordEntity.builder()
                    .id(PROXY_RECORD_ID)
                    .proxyInputConsentId(CONSENT_ID)
                    .subjectUserId(USER_ID)
                    .proxyUserId(2L)
                    .featureScope("SURVEY")
                    .targetEntityType("SURVEY")
                    .targetEntityId(SURVEY_ID)
                    .inputSource(ProxyInputRecordEntity.InputSource.PAPER_FORM)
                    .originalStorageLocation("proxy-records/scan.pdf")
                    .build();

            SubmitResponseRequest request = new SubmitResponseRequest(
                    List.of(new SubmitResponseRequest.AnswerEntry(questionId, null, "テスト回答")));

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(responseRepository.existsBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(false);
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(SURVEY_ID))
                    .willReturn(List.of(question));
            given(responseRepository.save(any(SurveyResponseEntity.class))).willReturn(savedResponse);
            given(proxyInputContext.isProxy()).willReturn(true);
            given(proxyInputContext.getConsentId()).willReturn(CONSENT_ID);
            given(proxyInputContext.getSubjectUserId()).willReturn(USER_ID);
            given(proxyInputContext.getInputSource()).willReturn("PAPER_FORM");
            given(proxyInputContext.getOriginalStorageLocation()).willReturn("proxy-records/scan.pdf");
            given(proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                    CONSENT_ID, "SURVEY", SURVEY_ID)).willReturn(Optional.empty());
            given(proxyInputRecordRepository.save(any(ProxyInputRecordEntity.class))).willReturn(proxyRecord);
            given(responseRepository.saveAll(any())).willReturn(List.of(proxySavedResponse));
            given(surveyRepository.save(any(SurveyEntity.class))).willReturn(survey);
            given(surveyMapper.toResponseEntryList(any())).willReturn(List.of());

            // When
            surveyResponseService.submitResponse(SURVEY_ID, USER_ID, request);

            // Then
            verify(proxyInputRecordRepository, times(1)).save(any(ProxyInputRecordEntity.class));
        }

        @Test
        @DisplayName("代理入力モードの場合: 保存された回答は isProxyInput=true であること")
        void 代理入力モード_保存回答がisProxyInputTrue() {
            // Given
            final Long questionId = 10L;
            SurveyEntity survey = createPublishedSurveyForProxy();
            SurveyQuestionEntity question = createOptionalFreeTextQuestion(questionId);
            SurveyResponseEntity savedResponse = createSavedResponse(questionId);

            ProxyInputRecordEntity proxyRecord = ProxyInputRecordEntity.builder()
                    .id(PROXY_RECORD_ID)
                    .proxyInputConsentId(CONSENT_ID)
                    .subjectUserId(USER_ID)
                    .proxyUserId(2L)
                    .featureScope("SURVEY")
                    .targetEntityType("SURVEY")
                    .targetEntityId(SURVEY_ID)
                    .inputSource(ProxyInputRecordEntity.InputSource.PAPER_FORM)
                    .originalStorageLocation("proxy-records/scan.pdf")
                    .build();

            SubmitResponseRequest request = new SubmitResponseRequest(
                    List.of(new SubmitResponseRequest.AnswerEntry(questionId, null, "テスト回答")));

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(responseRepository.existsBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(false);
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(SURVEY_ID))
                    .willReturn(List.of(question));
            given(responseRepository.save(any(SurveyResponseEntity.class))).willReturn(savedResponse);
            given(proxyInputContext.isProxy()).willReturn(true);
            given(proxyInputContext.getConsentId()).willReturn(CONSENT_ID);
            given(proxyInputContext.getSubjectUserId()).willReturn(USER_ID);
            given(proxyInputContext.getInputSource()).willReturn("PAPER_FORM");
            given(proxyInputContext.getOriginalStorageLocation()).willReturn("proxy-records/scan.pdf");
            given(proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                    CONSENT_ID, "SURVEY", SURVEY_ID)).willReturn(Optional.empty());
            given(proxyInputRecordRepository.save(any(ProxyInputRecordEntity.class))).willReturn(proxyRecord);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<SurveyResponseEntity>> saveAllCaptor =
                    ArgumentCaptor.forClass(List.class);
            given(responseRepository.saveAll(saveAllCaptor.capture()))
                    .willReturn(List.of(createProxySavedResponse(questionId)));
            given(surveyRepository.save(any(SurveyEntity.class))).willReturn(survey);
            given(surveyMapper.toResponseEntryList(any())).willReturn(List.of());

            // When
            surveyResponseService.submitResponse(SURVEY_ID, USER_ID, request);

            // Then
            List<SurveyResponseEntity> capturedList = saveAllCaptor.getValue();
            assertThat(capturedList).isNotEmpty();
            assertThat(capturedList.get(0).getIsProxyInput()).isTrue();
        }

        @Test
        @DisplayName("代理入力モードの場合: proxyInputRecordId が ProxyInputRecord の id と一致すること")
        void 代理入力モード_proxyInputRecordIdが一致すること() {
            // Given
            final Long questionId = 10L;
            SurveyEntity survey = createPublishedSurveyForProxy();
            SurveyQuestionEntity question = createOptionalFreeTextQuestion(questionId);
            SurveyResponseEntity savedResponse = createSavedResponse(questionId);

            ProxyInputRecordEntity proxyRecord = ProxyInputRecordEntity.builder()
                    .id(PROXY_RECORD_ID)
                    .proxyInputConsentId(CONSENT_ID)
                    .subjectUserId(USER_ID)
                    .proxyUserId(2L)
                    .featureScope("SURVEY")
                    .targetEntityType("SURVEY")
                    .targetEntityId(SURVEY_ID)
                    .inputSource(ProxyInputRecordEntity.InputSource.PAPER_FORM)
                    .originalStorageLocation("proxy-records/scan.pdf")
                    .build();

            SubmitResponseRequest request = new SubmitResponseRequest(
                    List.of(new SubmitResponseRequest.AnswerEntry(questionId, null, "テスト回答")));

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(responseRepository.existsBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(false);
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(SURVEY_ID))
                    .willReturn(List.of(question));
            given(responseRepository.save(any(SurveyResponseEntity.class))).willReturn(savedResponse);
            given(proxyInputContext.isProxy()).willReturn(true);
            given(proxyInputContext.getConsentId()).willReturn(CONSENT_ID);
            given(proxyInputContext.getSubjectUserId()).willReturn(USER_ID);
            given(proxyInputContext.getInputSource()).willReturn("PAPER_FORM");
            given(proxyInputContext.getOriginalStorageLocation()).willReturn("proxy-records/scan.pdf");
            given(proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                    CONSENT_ID, "SURVEY", SURVEY_ID)).willReturn(Optional.empty());
            given(proxyInputRecordRepository.save(any(ProxyInputRecordEntity.class))).willReturn(proxyRecord);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<SurveyResponseEntity>> saveAllCaptor =
                    ArgumentCaptor.forClass(List.class);
            given(responseRepository.saveAll(saveAllCaptor.capture()))
                    .willReturn(List.of(createProxySavedResponse(questionId)));
            given(surveyRepository.save(any(SurveyEntity.class))).willReturn(survey);
            given(surveyMapper.toResponseEntryList(any())).willReturn(List.of());

            // When
            surveyResponseService.submitResponse(SURVEY_ID, USER_ID, request);

            // Then
            List<SurveyResponseEntity> capturedList = saveAllCaptor.getValue();
            assertThat(capturedList).isNotEmpty();
            assertThat(capturedList.get(0).getProxyInputRecordId()).isEqualTo(PROXY_RECORD_ID);
        }

        @Test
        @DisplayName("通常モードの場合: ProxyInputRecordRepository.save() を呼ばないこと")
        void 通常モード_ProxyInputRecordRepositorySaveを呼ばない() {
            // Given
            final Long questionId = 10L;
            SurveyEntity survey = createPublishedSurveyForProxy();
            SurveyQuestionEntity question = createOptionalFreeTextQuestion(questionId);
            SurveyResponseEntity savedResponse = createSavedResponse(questionId);

            SubmitResponseRequest request = new SubmitResponseRequest(
                    List.of(new SubmitResponseRequest.AnswerEntry(questionId, null, "テスト回答")));

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(responseRepository.existsBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(false);
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(SURVEY_ID))
                    .willReturn(List.of(question));
            given(responseRepository.save(any(SurveyResponseEntity.class))).willReturn(savedResponse);
            given(proxyInputContext.isProxy()).willReturn(false);
            given(surveyRepository.save(any(SurveyEntity.class))).willReturn(survey);
            given(surveyMapper.toResponseEntryList(any())).willReturn(List.of());

            // When
            surveyResponseService.submitResponse(SURVEY_ID, USER_ID, request);

            // Then
            verify(proxyInputRecordRepository, never()).save(any(ProxyInputRecordEntity.class));
        }
    }
}
