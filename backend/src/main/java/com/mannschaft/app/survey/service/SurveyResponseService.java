package com.mannschaft.app.survey.service;

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
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
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
    private final SurveyResponseRepository responseRepository;
    private final SurveyTargetRepository targetRepository;
    private final SurveyMapper surveyMapper;
    private final SurveyService surveyService;
    private final ProxyInputContext proxyInputContext;
    private final ProxyInputRecordRepository proxyInputRecordRepository;

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

        // 配信対象チェック
        if (survey.getDistributionMode() == DistributionMode.TARGETED) {
            if (!targetRepository.existsBySurveyIdAndUserId(surveyId, userId)) {
                throw new BusinessException(SurveyErrorCode.NOT_TARGET_USER);
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
