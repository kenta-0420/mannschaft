package com.mannschaft.app.survey.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.survey.QuestionType;
import com.mannschaft.app.survey.SurveyErrorCode;
import com.mannschaft.app.survey.SurveyMapper;
import com.mannschaft.app.survey.dto.SubmitResponseRequest;
import com.mannschaft.app.survey.dto.SurveyResponseEntry;
import com.mannschaft.app.survey.dto.UserResponseAnswerEntry;
import com.mannschaft.app.survey.dto.UserResponseDetailResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyOptionEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.repository.SurveyOptionRepository;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyResultViewerRepository;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * アンケート回答サービス。回答の送信・取得を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SurveyResponseService {

    private final SurveyRepository surveyRepository;
    private final SurveyQuestionRepository questionRepository;
    private final SurveyOptionRepository optionRepository;
    private final SurveyResponseRepository responseRepository;
    private final SurveyTargetRepository targetRepository;
    private final SurveyResultViewerRepository resultViewerRepository;
    private final SurveyMapper surveyMapper;
    private final SurveyService surveyService;
    private final ProxyInputContext proxyInputContext;
    private final ProxyInputRecordRepository proxyInputRecordRepository;
    private final AccessControlService accessControlService;
    private final UserRepository userRepository;

    /**
     * アンケートに回答を送信する。
     *
     * @param surveyId アンケートID
     * @param userId   回答者ユーザーID
     * @param request  回答送信リクエスト
     * @return 回答エントリリスト
     */
    @Transactional
    public List<SurveyResponseEntry> submitResponse(Long surveyId, Long userId,
                                                     SubmitResponseRequest request) {
        SurveyEntity survey = surveyService.findSurveyEntityOrThrow(surveyId);

        if (!survey.isAcceptingResponses()) {
            if (survey.getExpiresAt() != null
                    && java.time.LocalDateTime.now().isAfter(survey.getExpiresAt())) {
                throw new BusinessException(SurveyErrorCode.SURVEY_EXPIRED);
            }
            throw new BusinessException(SurveyErrorCode.INVALID_SURVEY_STATUS);
        }

        // 配信対象チェック（関所(3)回答・配信＝受信権 統一）
        if (survey.getDistributionMode() == DistributionMode.TARGETED) {
            if (!targetRepository.existsBySurveyIdAndUserId(surveyId, userId)) {
                throw new BusinessException(SurveyErrorCode.NOT_TARGET_USER);
            }
        } else {
            // DistributionMode.ALL: 旧実装はここで認可が一切なく、組織外の任意ユーザーが
            // surveyId さえ知れば回答できる漏洩穴だった（TARGETED のみ existsBySurveyIdAndUserId で
            // 弾いていた）。ALL は「スコープ内全メンバー」が母集団であるから、回答も配信母集団に限定する。
            //   - ORGANIZATION: includeSupporters トグル準拠の配信母集団（配下チーム展開）。配下は通し、
            //     母集団外は COMMON_002 で弾く。トグル OFF なら純 SUPPORTER も母集団外＝回答不可。
            //   - TEAM 等: 当該スコープの直接所属メンバー（配下概念を持ち込まない）。
            if (accessControlService != null && survey.getScopeId() != null
                    && survey.getScopeType() != null) {
                boolean includeSupporters = Boolean.TRUE.equals(survey.getIncludeSupporters());
                accessControlService.checkMembershipOrDescendant(
                        userId, survey.getScopeId(), survey.getScopeType(), includeSupporters);
            }
        }

        // 複数回答チェック
        boolean alreadyResponded = responseRepository.existsBySurveyIdAndUserId(surveyId, userId);
        if (alreadyResponded && !survey.getAllowMultipleSubmissions()) {
            throw new BusinessException(SurveyErrorCode.DUPLICATE_RESPONSE);
        }

        // 再回答の場合、既存回答を削除
        if (alreadyResponded && survey.getAllowMultipleSubmissions()) {
            responseRepository.deleteBySurveyIdAndUserId(surveyId, userId);
        }

        // 設問マップの構築
        List<SurveyQuestionEntity> questions = questionRepository.findBySurveyIdOrderByDisplayOrderAsc(surveyId);
        Map<Long, SurveyQuestionEntity> questionMap = questions.stream()
                .collect(Collectors.toMap(SurveyQuestionEntity::getId, Function.identity()));

        // 必須設問チェック
        validateRequiredQuestions(questions, request);

        // 回答の保存
        List<SurveyResponseEntity> savedResponses = new ArrayList<>();
        for (SubmitResponseRequest.AnswerEntry answer : request.getAnswers()) {
            SurveyQuestionEntity question = questionMap.get(answer.getQuestionId());
            if (question == null) {
                throw new BusinessException(SurveyErrorCode.QUESTION_NOT_FOUND);
            }

            savedResponses.addAll(saveAnswerEntries(surveyId, userId, question, answer));
        }

        // 代理入力の場合: proxy_input_records を作成し、各回答にフラグをセット
        if (proxyInputContext.isProxy()) {
            ProxyInputRecordEntity proxyRecord = buildAndSaveProxyInputRecord(
                    "SURVEY", surveyId);
            List<SurveyResponseEntity> proxyFlagged = new ArrayList<>();
            for (SurveyResponseEntity r : savedResponses) {
                proxyFlagged.add(r.toBuilder()
                        .isProxyInput(true)
                        .proxyInputRecordId(proxyRecord.getId())
                        .build());
            }
            savedResponses = responseRepository.saveAll(proxyFlagged);
        }

        // 回答カウントの更新（初回回答のみ）
        if (!alreadyResponded) {
            survey.incrementResponseCount();
            surveyRepository.save(survey);
        }

        log.info("アンケート回答送信: surveyId={}, userId={}", surveyId, userId);
        return surveyMapper.toResponseEntryList(savedResponses);
    }

    /**
     * ユーザーの回答を取得する。
     *
     * @param surveyId アンケートID
     * @param userId   ユーザーID
     * @return 回答エントリリスト
     */
    public List<SurveyResponseEntry> getMyResponses(Long surveyId, Long userId) {
        List<SurveyResponseEntity> responses = responseRepository.findBySurveyIdAndUserId(surveyId, userId);
        return surveyMapper.toResponseEntryList(responses);
    }

    /**
     * 指定ユーザーの回答詳細を取得する（F05.4 §4.8 /responses/{userId}）。
     *
     * <p>認可:
     * <ul>
     *   <li>匿名アンケート（{@code is_anonymous=true}）の場合は無条件で 403</li>
     *   <li>ADMIN+ / 作成者 / {@code survey_result_viewers} 登録者のみ閲覧可</li>
     *   <li>指定ユーザーが未回答の場合 404</li>
     * </ul>
     *
     * @param surveyId      対象アンケート ID
     * @param userId        回答取得対象ユーザー ID
     * @param currentUserId 操作実行者ユーザー ID
     * @return 個別回答詳細
     */
    public UserResponseDetailResponse getResponseByUser(Long surveyId, Long userId, Long currentUserId) {
        SurveyEntity survey = surveyService.findSurveyEntityOrThrow(surveyId);

        // 匿名アンケートは個別回答取得不可
        if (Boolean.TRUE.equals(survey.getIsAnonymous())) {
            throw new BusinessException(SurveyErrorCode.ANONYMOUS_RESPONSE_FORBIDDEN);
        }

        // 認可
        boolean isCreator = survey.getCreatedBy() != null && survey.getCreatedBy().equals(currentUserId);
        boolean isAdmin = accessControlService.isAdminOrAbove(
                currentUserId, survey.getScopeId(), survey.getScopeType());
        boolean isViewer = resultViewerRepository.existsBySurveyIdAndUserId(surveyId, currentUserId);
        if (!isAdmin && !isCreator && !isViewer) {
            throw new BusinessException(SurveyErrorCode.RESPONSE_ACCESS_DENIED);
        }

        List<SurveyResponseEntity> responses = responseRepository.findBySurveyIdAndUserId(surveyId, userId);
        if (responses.isEmpty()) {
            throw new BusinessException(SurveyErrorCode.USER_RESPONSE_NOT_FOUND);
        }

        List<SurveyQuestionEntity> questions =
                questionRepository.findBySurveyIdOrderByDisplayOrderAsc(surveyId);
        Map<Long, SurveyQuestionEntity> questionMap = questions.stream()
                .collect(Collectors.toMap(SurveyQuestionEntity::getId, Function.identity()));

        // question_id ごとに responses をグルーピング（MULTIPLE_CHOICE は複数行）
        Map<Long, List<SurveyResponseEntity>> byQuestion = responses.stream()
                .collect(Collectors.groupingBy(SurveyResponseEntity::getQuestionId));

        List<UserResponseAnswerEntry> answers = new ArrayList<>();
        java.time.LocalDateTime respondedAt = responses.get(0).getCreatedAt();
        for (SurveyQuestionEntity q : questions) {
            List<SurveyResponseEntity> qResponses = byQuestion.getOrDefault(q.getId(), List.of());
            if (qResponses.isEmpty()) {
                continue;
            }
            List<Long> optionIds = new ArrayList<>();
            List<String> optionTexts = new ArrayList<>();
            String answerText = null;
            for (SurveyResponseEntity r : qResponses) {
                if (r.getOptionId() != null) {
                    optionIds.add(r.getOptionId());
                    SurveyOptionEntity opt = optionRepository.findById(r.getOptionId()).orElse(null);
                    optionTexts.add(opt != null ? opt.getOptionText() : null);
                }
                if (r.getTextResponse() != null) {
                    answerText = r.getTextResponse();
                }
            }
            answers.add(new UserResponseAnswerEntry(
                    q.getId(),
                    q.getQuestionText(),
                    q.getQuestionType().name(),
                    optionIds.isEmpty() ? null : optionIds,
                    optionTexts.isEmpty() ? null : optionTexts,
                    answerText));
        }

        UserEntity user = userRepository.findById(userId).orElse(null);
        String displayName = user != null
                ? (user.getLastName() != null ? user.getLastName() : "") + " "
                  + (user.getFirstName() != null ? user.getFirstName() : "")
                : null;
        UserResponseDetailResponse.UserSummary summary =
                new UserResponseDetailResponse.UserSummary(userId, displayName != null ? displayName.trim() : null);
        // 未使用変数を黙らせるためのフィールド参照（questionMap は将来拡張用に保持）
        if (questionMap.isEmpty()) {
            log.debug("questionMap empty for surveyId={}", surveyId);
        }

        return new UserResponseDetailResponse(surveyId, summary, respondedAt, answers);
    }

    /**
     * 必須設問への回答が含まれているか検証する。
     */
    private void validateRequiredQuestions(List<SurveyQuestionEntity> questions,
                                           SubmitResponseRequest request) {
        Map<Long, SubmitResponseRequest.AnswerEntry> answerMap = request.getAnswers().stream()
                .collect(Collectors.toMap(SubmitResponseRequest.AnswerEntry::getQuestionId,
                        Function.identity()));

        for (SurveyQuestionEntity question : questions) {
            if (question.getIsRequired()) {
                SubmitResponseRequest.AnswerEntry answer = answerMap.get(question.getId());
                if (answer == null) {
                    throw new BusinessException(SurveyErrorCode.REQUIRED_QUESTION_MISSING);
                }
                boolean hasContent = (answer.getOptionIds() != null && !answer.getOptionIds().isEmpty())
                        || (answer.getTextResponse() != null && !answer.getTextResponse().isBlank());
                if (!hasContent) {
                    throw new BusinessException(SurveyErrorCode.REQUIRED_QUESTION_MISSING);
                }
            }
        }
    }

    /**
     * 回答エントリを保存する。設問タイプに応じて複数行を生成する。
     */
    private List<SurveyResponseEntity> saveAnswerEntries(Long surveyId, Long userId,
                                                          SurveyQuestionEntity question,
                                                          SubmitResponseRequest.AnswerEntry answer) {
        List<SurveyResponseEntity> saved = new ArrayList<>();

        if (question.getQuestionType() == QuestionType.SINGLE_CHOICE) {
            if (answer.getOptionIds() != null && !answer.getOptionIds().isEmpty()) {
                SurveyResponseEntity entity = SurveyResponseEntity.builder()
                        .surveyId(surveyId)
                        .questionId(question.getId())
                        .userId(userId)
                        .optionId(answer.getOptionIds().get(0))
                        .build();
                saved.add(responseRepository.save(entity));
            }
        } else if (question.getQuestionType() == QuestionType.MULTIPLE_CHOICE) {
            if (answer.getOptionIds() != null) {
                if (question.getMaxSelections() != null
                        && answer.getOptionIds().size() > question.getMaxSelections()) {
                    throw new BusinessException(SurveyErrorCode.MAX_SELECTIONS_EXCEEDED);
                }
                for (Long optionId : answer.getOptionIds()) {
                    SurveyResponseEntity entity = SurveyResponseEntity.builder()
                            .surveyId(surveyId)
                            .questionId(question.getId())
                            .userId(userId)
                            .optionId(optionId)
                            .build();
                    saved.add(responseRepository.save(entity));
                }
            }
        } else if (question.getQuestionType() == QuestionType.FREE_TEXT) {
            SurveyResponseEntity entity = SurveyResponseEntity.builder()
                    .surveyId(surveyId)
                    .questionId(question.getId())
                    .userId(userId)
                    .textResponse(answer.getTextResponse())
                    .build();
            saved.add(responseRepository.save(entity));
        } else if (question.getQuestionType() == QuestionType.SCALE) {
            SurveyResponseEntity entity = SurveyResponseEntity.builder()
                    .surveyId(surveyId)
                    .questionId(question.getId())
                    .userId(userId)
                    .textResponse(answer.getTextResponse())
                    .build();
            saved.add(responseRepository.save(entity));
        }

        return saved;
    }

    private ProxyInputRecordEntity buildAndSaveProxyInputRecord(String targetEntityType, Long targetEntityId) {
        Long proxyUserId = SecurityUtils.getCurrentUserIdOrNull();
        // 冪等性チェック（紙運用での二重登録防止）
        return proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                proxyInputContext.getConsentId(), targetEntityType, targetEntityId)
                .orElseGet(() -> proxyInputRecordRepository.save(
                        ProxyInputRecordEntity.builder()
                                .proxyInputConsentId(proxyInputContext.getConsentId())
                                .subjectUserId(proxyInputContext.getSubjectUserId())
                                .proxyUserId(proxyUserId)
                                .featureScope(targetEntityType.equals("SURVEY") ? "SURVEY" : "SCHEDULE_ATTENDANCE")
                                .targetEntityType(targetEntityType)
                                .targetEntityId(targetEntityId)
                                .inputSource(ProxyInputRecordEntity.InputSource.valueOf(
                                        proxyInputContext.getInputSource()))
                                .originalStorageLocation(proxyInputContext.getOriginalStorageLocation())
                                .build()));
    }
}
