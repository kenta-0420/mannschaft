package com.mannschaft.app.survey.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.survey.QuestionType;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.SurveyErrorCode;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.UnrespondedVisibility;
import com.mannschaft.app.survey.dto.RespondentResponse;
import com.mannschaft.app.survey.dto.SurveyResultResponse;
import com.mannschaft.app.survey.dto.SurveyResultResponse.OptionResultResponse;
import com.mannschaft.app.survey.dto.SurveyResultResponse.QuestionResultResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyOptionEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.entity.SurveyTargetEntity;
import com.mannschaft.app.survey.repository.SurveyOptionRepository;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyResultViewerRepository;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * アンケート結果サービス。結果の集計・閲覧権限管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SurveyResultService {

    private final SurveyQuestionRepository questionRepository;
    private final SurveyOptionRepository optionRepository;
    private final SurveyResponseRepository responseRepository;
    private final SurveyResultViewerRepository resultViewerRepository;
    private final SurveyTargetRepository targetRepository;
    private final SurveyService surveyService;
    private final AccessControlService accessControlService;
    private final UserRepository userRepository;

    /**
     * アンケート結果を取得する。閲覧権限チェックを行う。
     *
     * @param surveyId アンケートID
     * @param userId   閲覧者ユーザーID
     * @return アンケート結果レスポンス
     */
    public SurveyResultResponse getResults(Long surveyId, Long userId) {
        SurveyEntity survey = surveyService.findSurveyEntityOrThrow(surveyId);

        validateResultAccess(survey, userId);

        return buildResultResponse(survey);
    }

    /**
     * 結果閲覧権限を検証する。
     */
    private void validateResultAccess(SurveyEntity survey, Long userId) {
        ResultsVisibility visibility = survey.getResultsVisibility();

        switch (visibility) {
            case AFTER_RESPONSE -> {
                boolean hasResponded = responseRepository.existsBySurveyIdAndUserId(survey.getId(), userId);
                if (!hasResponded) {
                    throw new BusinessException(SurveyErrorCode.RESULT_ACCESS_DENIED);
                }
            }
            case AFTER_CLOSE -> {
                if (survey.getStatus() != SurveyStatus.CLOSED
                        && survey.getStatus() != SurveyStatus.ARCHIVED) {
                    throw new BusinessException(SurveyErrorCode.RESULT_ACCESS_DENIED);
                }
            }
            case ADMINS_ONLY -> {
                // 作成者またはADMIN/DEPUTY_ADMINのみ閲覧可能
                if (!survey.getCreatedBy().equals(userId)
                        && !accessControlService.isAdminOrAbove(userId, survey.getScopeId(), survey.getScopeType())) {
                    throw new BusinessException(SurveyErrorCode.RESULT_ACCESS_DENIED);
                }
            }
            case VIEWERS_ONLY -> {
                boolean isViewer = resultViewerRepository.existsBySurveyIdAndUserId(survey.getId(), userId);
                boolean isCreator = survey.getCreatedBy() != null && survey.getCreatedBy().equals(userId);
                if (!isViewer && !isCreator) {
                    throw new BusinessException(SurveyErrorCode.RESULT_ACCESS_DENIED);
                }
            }
        }
    }

    /**
     * 回答者一覧（未回答者を含む）を取得する。F05.4 §7.2「未回答者一覧の可視化」。
     *
     * <p>認可:
     * <ul>
     *   <li>ADMIN+ / 作成者 / survey_result_viewers → 全件返却（has_responded 付き）</li>
     *   <li>{@code unresponded_visibility = ALL_MEMBERS} かつ MEMBER（本人が survey_targets に含まれる）
     *       → 未回答者のみ（user_id, display_name, avatar_url のみ。responded_at は null）</li>
     *   <li>それ以外 → {@link SurveyErrorCode#RESPONDENTS_ACCESS_DENIED}</li>
     * </ul>
     *
     * <p>母集団は {@code survey_targets}（TARGETED 配信時、もしくは ALL でも事前登録時）。
     * 配信対象が登録されていない場合は、回答済みユーザーのみを返却する。
     *
     * @param surveyId 対象アンケートID
     * @param userId   閲覧者ユーザーID
     * @return 回答者一覧
     */
    public List<RespondentResponse> getRespondents(Long surveyId, Long userId) {
        SurveyEntity survey = surveyService.findSurveyEntityOrThrow(surveyId);

        boolean isCreator = survey.getCreatedBy() != null && survey.getCreatedBy().equals(userId);
        boolean isAdmin = accessControlService.isAdminOrAbove(userId, survey.getScopeId(), survey.getScopeType());
        boolean isViewer = resultViewerRepository.existsBySurveyIdAndUserId(survey.getId(), userId);

        boolean fullAccess = isAdmin || isCreator || isViewer;

        if (!fullAccess) {
            // MEMBER 経路: ALL_MEMBERS かつ自分が対象者である場合のみ未回答者リスト閲覧可
            if (survey.getUnrespondedVisibility() != UnrespondedVisibility.ALL_MEMBERS
                    || !targetRepository.existsBySurveyIdAndUserId(survey.getId(), userId)) {
                throw new BusinessException(SurveyErrorCode.RESPONDENTS_ACCESS_DENIED);
            }
        }

        List<SurveyTargetEntity> targets = targetRepository.findBySurveyId(surveyId);
        Set<Long> respondedUserIds = responseRepository.findBySurveyIdOrderByCreatedAtAsc(surveyId)
                .stream()
                .map(SurveyResponseEntity::getUserId)
                .collect(Collectors.toCollection(HashSet::new));

        Map<Long, SurveyResponseEntity> firstResponseByUser = new HashMap<>();
        for (SurveyResponseEntity r : responseRepository.findBySurveyIdOrderByCreatedAtAsc(surveyId)) {
            firstResponseByUser.putIfAbsent(r.getUserId(), r);
        }

        List<Long> universeUserIds;
        if (!targets.isEmpty()) {
            universeUserIds = targets.stream().map(SurveyTargetEntity::getUserId).distinct().collect(Collectors.toList());
        } else {
            // ALL モードかつ survey_targets 未登録時は、回答済みユーザーのみを母集団とする（未回答者抽出は将来対応）
            universeUserIds = new ArrayList<>(respondedUserIds);
        }

        List<UserEntity> users = userRepository.findAllById(universeUserIds);
        Map<Long, UserEntity> userById = users.stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u));

        List<RespondentResponse> result = new ArrayList<>();
        for (Long uid : universeUserIds) {
            UserEntity u = userById.get(uid);
            if (u == null) {
                continue;
            }
            boolean hasResponded = respondedUserIds.contains(uid);

            if (!fullAccess) {
                // MEMBER 経路: 未回答者のみ・respondedAt は null
                if (hasResponded) {
                    continue;
                }
                result.add(new RespondentResponse(u.getId(), u.getDisplayName(), u.getAvatarUrl(), false, null));
            } else {
                java.time.LocalDateTime respondedAt = hasResponded
                        ? firstResponseByUser.get(uid).getCreatedAt()
                        : null;
                result.add(new RespondentResponse(u.getId(), u.getDisplayName(), u.getAvatarUrl(),
                        hasResponded, respondedAt));
            }
        }
        return result;
    }

    /**
     * アンケート結果レスポンスを構築する。
     */
    private SurveyResultResponse buildResultResponse(SurveyEntity survey) {
        List<SurveyQuestionEntity> questions =
                questionRepository.findBySurveyIdOrderByDisplayOrderAsc(survey.getId());

        long totalRespondents = responseRepository.countDistinctUsersBySurveyId(survey.getId());

        List<QuestionResultResponse> questionResults = new ArrayList<>();
        for (SurveyQuestionEntity question : questions) {
            questionResults.add(buildQuestionResult(survey.getId(), question, totalRespondents));
        }

        return new SurveyResultResponse(
                survey.getId(),
                survey.getTitle(),
                survey.getResponseCount(),
                survey.getTargetCount(),
                questionResults
        );
    }

    /**
     * 設問ごとの結果を構築する。
     */
    private QuestionResultResponse buildQuestionResult(Long surveyId, SurveyQuestionEntity question,
                                                        long totalRespondents) {
        List<OptionResultResponse> optionResults = new ArrayList<>();
        List<String> textResponses = new ArrayList<>();

        if (question.getQuestionType() == QuestionType.SINGLE_CHOICE
                || question.getQuestionType() == QuestionType.MULTIPLE_CHOICE) {
            List<SurveyOptionEntity> options =
                    optionRepository.findByQuestionIdOrderByDisplayOrderAsc(question.getId());

            for (SurveyOptionEntity option : options) {
                long count = responseRepository.countBySurveyIdAndQuestionIdAndOptionId(
                        surveyId, question.getId(), option.getId());
                double percentage = totalRespondents > 0
                        ? (double) count / totalRespondents * 100.0 : 0.0;
                optionResults.add(new OptionResultResponse(
                        option.getId(), option.getOptionText(), count, percentage));
            }
        }

        if (question.getQuestionType() == QuestionType.FREE_TEXT
                || question.getQuestionType() == QuestionType.SCALE) {
            List<SurveyResponseEntity> responses =
                    responseRepository.findBySurveyIdAndQuestionId(surveyId, question.getId());
            for (SurveyResponseEntity resp : responses) {
                if (resp.getTextResponse() != null) {
                    textResponses.add(resp.getTextResponse());
                }
            }
        }

        return new QuestionResultResponse(
                question.getId(),
                question.getQuestionText(),
                question.getQuestionType().name(),
                optionResults,
                textResponses
        );
    }
}
