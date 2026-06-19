package com.mannschaft.app.survey;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.survey.dto.SubmitResponseRequest;
import com.mannschaft.app.survey.dto.SurveyResponseEntry;
import com.mannschaft.app.survey.dto.UserResponseDetailResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.repository.SurveyOptionRepository;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyResultViewerRepository;
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
    private SurveyOptionRepository optionRepository;

    @Mock
    private SurveyResultViewerRepository resultViewerRepository;

    @Mock
    private SurveyMapper surveyMapper;

    @Mock
    private SurveyService surveyService;

    @Mock
    private ProxyInputContext proxyInputContext;

    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private UserRepository userRepository;

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

        @Test
        @DisplayName("番人: 回答送信_ALL×組織_配信母集団外ユーザーは認可で弾かれる（submitResponse ALL認可穴の塞ぎ）")
        void 回答送信_ALL組織_配信母集団外は認可で拒否() {
            // Given: DistributionMode.ALL × ORGANIZATION。旧実装は TARGETED のみ認可しており、
            // ALL は認可ゼロで組織外ユーザーも surveyId さえ知れば回答できる漏洩穴だった。
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("ORGANIZATION").scopeId(20L).title("組織アンケート")
                    .distributionMode(DistributionMode.ALL)
                    .includeSupporters(false)
                    .allowMultipleSubmissions(false).createdBy(1L).build();
            survey.publish();

            SubmitResponseRequest request = new SubmitResponseRequest(List.of());

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            // 配信母集団外 → checkMembershipOrDescendant が COMMON_002 を投げる
            org.mockito.Mockito.doThrow(new BusinessException(
                            com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .when(accessControlService)
                    .checkMembershipOrDescendant(USER_ID, 20L, "ORGANIZATION", false);

            // When & Then
            assertThatThrownBy(() -> surveyResponseService.submitResponse(SURVEY_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(com.mannschaft.app.common.CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("番人: 回答送信_ALL_scope欠落は認可をスキップせず403で弾く（fail-closed・不変条件ガード）")
        void 回答送信_ALL_scope欠落は403で拒否() {
            // Given: DistributionMode.ALL なのに scopeId/scopeType が null（不変条件違反）。
            // 旧ガードは scope!=null のときのみ認可していたため、null なら認可をサイレントスキップして
            // surveyId を知る任意ユーザーが回答できる漏洩穴になりうる。fail-closed で弾くことを担保する。
            SurveyEntity survey = SurveyEntity.builder()
                    .title("scope欠落アンケート")
                    .distributionMode(DistributionMode.ALL)
                    .includeSupporters(false)
                    .allowMultipleSubmissions(false).createdBy(1L).build();
            survey.publish();
            // scopeId/scopeType は未設定（null）

            SubmitResponseRequest request = new SubmitResponseRequest(List.of());

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);

            // When & Then: 認可をスキップせず COMMON_002（403）で拒否
            assertThatThrownBy(() -> surveyResponseService.submitResponse(SURVEY_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(com.mannschaft.app.common.CommonErrorCode.COMMON_002));
            // 回答保存・重複チェックには進まないこと（弾く前で握りつぶさず例外）
            org.mockito.Mockito.verify(responseRepository, org.mockito.Mockito.never())
                    .existsBySurveyIdAndUserId(org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("番人: 回答送信_ALL×組織_配信母集団メンバーはトグル準拠で認可を通過する")
        void 回答送信_ALL組織_配信母集団メンバーは通過() {
            // Given: includeSupporters=true の組織 ALL アンケ。配下 SUPPORTER も配信母集団＝回答可。
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("ORGANIZATION").scopeId(20L).title("組織アンケート")
                    .distributionMode(DistributionMode.ALL)
                    .includeSupporters(true)
                    .allowMultipleSubmissions(false).createdBy(1L).build();
            survey.publish();

            SubmitResponseRequest request = new SubmitResponseRequest(List.of());

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            // 母集団内 → checkMembershipOrDescendant(...,true) は例外なし（mock 既定の no-op）
            given(responseRepository.existsBySurveyIdAndUserId(SURVEY_ID, USER_ID)).willReturn(false);
            given(surveyMapper.toResponseEntryList(org.mockito.ArgumentMatchers.anyList()))
                    .willReturn(List.of());

            // When
            List<SurveyResponseEntry> result = surveyResponseService.submitResponse(SURVEY_ID, USER_ID, request);

            // Then: 認可を通過し回答が成立する（空回答だが例外は出ない）
            assertThat(result).isEmpty();
            // トグル準拠（includeSupporters=true）で認可されたこと
            org.mockito.Mockito.verify(accessControlService)
                    .checkMembershipOrDescendant(USER_ID, 20L, "ORGANIZATION", true);
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

    // ─────────────────────────────────────────────────────────────
    // 回帰テスト: SurveyResponseEntity @Builder.Default バグ再発防止
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SurveyResponseEntity — @Builder.Default 回帰テスト")
    class SurveyResponseEntityBuilderDefault {

        @Test
        @DisplayName("isProxyInput を明示しないビルドで false が設定されること（null にならないこと）")
        void isProxyInput未指定ビルド_falseが設定される() {
            // Given & When
            SurveyResponseEntity entity = SurveyResponseEntity.builder()
                    .surveyId(1L)
                    .questionId(1L)
                    .userId(1L)
                    .build();

            // Then — @Builder.Default がなければ null になり DB NOT NULL 制約違反で INSERT が 500 になる
            assertThat(entity.getIsProxyInput())
                    .as("isProxyInput に @Builder.Default が付いていないと null になり NOT NULL 制約違反で 500 になるバグの回帰テスト")
                    .isNotNull()
                    .isFalse();
        }

        @Test
        @DisplayName("isProxyInput=true を明示した場合は true が設定されること")
        void isProxyInput明示True_trueが設定される() {
            // Given & When
            SurveyResponseEntity entity = SurveyResponseEntity.builder()
                    .surveyId(1L)
                    .questionId(1L)
                    .userId(1L)
                    .isProxyInput(true)
                    .build();

            // Then
            assertThat(entity.getIsProxyInput()).isTrue();
        }
    }

    @Nested
    @DisplayName("getResponseByUser")
    class GetResponseByUser {

        @Test
        @DisplayName("匿名アンケート_ANONYMOUS_RESPONSE_FORBIDDEN")
        void 匿名アンケート_ANONYMOUS_RESPONSE_FORBIDDEN() {
            // Given
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("匿名")
                    .isAnonymous(true).createdBy(USER_ID).build();
            survey.publish();
            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);

            // When & Then
            assertThatThrownBy(() -> surveyResponseService.getResponseByUser(SURVEY_ID, 99L, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.ANONYMOUS_RESPONSE_FORBIDDEN));
        }

        @Test
        @DisplayName("権限なし_RESPONSE_ACCESS_DENIED")
        void 権限なし_RESPONSE_ACCESS_DENIED() {
            // Given
            Long otherUser = 999L;
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("非匿名")
                    .isAnonymous(false).createdBy(USER_ID).build();
            survey.publish();
            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(accessControlService.isAdminOrAbove(otherUser, 1L, "TEAM")).willReturn(false);
            given(resultViewerRepository.existsBySurveyIdAndUserId(SURVEY_ID, otherUser)).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> surveyResponseService.getResponseByUser(SURVEY_ID, 99L, otherUser))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.RESPONSE_ACCESS_DENIED));
        }

        @Test
        @DisplayName("未回答ユーザー_USER_RESPONSE_NOT_FOUND")
        void 未回答ユーザー_USER_RESPONSE_NOT_FOUND() {
            // Given
            Long targetUser = 50L;
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("非匿名")
                    .isAnonymous(false).createdBy(USER_ID).build();
            survey.publish();
            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            // 作成者はアクセス可
            given(responseRepository.findBySurveyIdAndUserId(SURVEY_ID, targetUser))
                    .willReturn(List.of());

            // When & Then
            assertThatThrownBy(() -> surveyResponseService.getResponseByUser(SURVEY_ID, targetUser, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SurveyErrorCode.USER_RESPONSE_NOT_FOUND));
        }

        @Test
        @DisplayName("作成者として取得_成功")
        void 作成者として取得_成功() {
            // Given
            Long targetUser = 50L;
            SurveyEntity survey = SurveyEntity.builder()
                    .scopeType("TEAM").scopeId(1L).title("非匿名")
                    .isAnonymous(false).createdBy(USER_ID).build();
            survey.publish();

            SurveyQuestionEntity q = SurveyQuestionEntity.builder()
                    .id(10L).surveyId(SURVEY_ID)
                    .questionType(QuestionType.FREE_TEXT)
                    .questionText("自由記述")
                    .isRequired(false).displayOrder(1).build();
            SurveyResponseEntity r = SurveyResponseEntity.builder()
                    .id(1L).surveyId(SURVEY_ID).questionId(10L)
                    .userId(targetUser).textResponse("回答内容").build();
            UserEntity user = org.mockito.Mockito.mock(UserEntity.class);
            given(user.getLastName()).willReturn("田中");
            given(user.getFirstName()).willReturn("太郎");

            given(surveyService.findSurveyEntityOrThrow(SURVEY_ID)).willReturn(survey);
            given(responseRepository.findBySurveyIdAndUserId(SURVEY_ID, targetUser))
                    .willReturn(List.of(r));
            given(questionRepository.findBySurveyIdOrderByDisplayOrderAsc(SURVEY_ID))
                    .willReturn(List.of(q));
            given(userRepository.findById(targetUser)).willReturn(Optional.of(user));

            // When
            UserResponseDetailResponse result =
                    surveyResponseService.getResponseByUser(SURVEY_ID, targetUser, USER_ID);

            // Then
            assertThat(result.surveyId()).isEqualTo(SURVEY_ID);
            assertThat(result.answers()).hasSize(1);
            assertThat(result.answers().get(0).answerText()).isEqualTo("回答内容");
            assertThat(result.user().displayName()).isEqualTo("田中 太郎");
        }
    }
}
