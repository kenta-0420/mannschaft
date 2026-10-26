package com.mannschaft.app.survey.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.survey.QuestionType;
import com.mannschaft.app.survey.SurveyErrorCode;
import com.mannschaft.app.survey.dto.SurveyComparisonResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyOptionEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.repository.SurveyOptionRepository;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * アンケートシリーズ比較サービス（F05.4 §4.9 series/{seriesId}/comparison）。
 *
 * <p>同一 {@code series_id} を持つアンケートを時系列でグループ化し、共通設問の
 * 推移を返す。設問・選択肢のマッチングは「テキスト完全一致」で行う（設計書 §1287-1290）。</p>
 *
 * <p>{@code survey_series} テーブルは存在しないため、{@code surveys.series_id} カラムで
 * グルーピングする運用。シリーズメタ情報は将来別 PR で導入予定。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SurveySeriesService {

    /** CMP-041: ADMIN+ 委任判定に用いる permission 名。 */
    private static final String PERMISSION_MANAGE_SURVEYS = "MANAGE_SURVEYS";

    private final SurveyRepository surveyRepository;
    private final SurveyQuestionRepository questionRepository;
    private final SurveyOptionRepository optionRepository;
    private final SurveyResponseRepository responseRepository;
    private final AccessControlService accessControlService;

    /**
     * 指定シリーズの時系列比較データを構築する。
     *
     * @param seriesId      シリーズ識別子
     * @param currentUserId 操作実行者ユーザー ID
     * @return シリーズ比較レスポンス
     */
    public SurveyComparisonResponse compareSeries(String seriesId, Long currentUserId) {
        List<SurveyEntity> surveys = surveyRepository.findBySeriesIdOrderByCreatedAtDesc(seriesId);
        if (surveys.isEmpty()) {
            throw new BusinessException(SurveyErrorCode.SERIES_NOT_FOUND);
        }

        // 認可: ADMIN+（先頭アンケートのスコープを基準とする。MANAGE_SURVEYS 保有 DEPUTY_ADMIN へ委任・CMP-041）
        SurveyEntity head = surveys.get(0);
        boolean isAdmin = accessControlService.hasAdminOrPermissionInScope(
                currentUserId, head.getScopeId(), head.getScopeType(), PERMISSION_MANAGE_SURVEYS);
        if (!isAdmin) {
            throw new BusinessException(SurveyErrorCode.OPERATION_PERMISSION_DENIED);
        }

        // 時系列昇順に並べる（published_at 昇順、null は created_at 昇順）
        surveys.sort((a, b) -> {
            java.time.LocalDateTime ap = a.getPublishedAt() != null ? a.getPublishedAt() : a.getCreatedAt();
            java.time.LocalDateTime bp = b.getPublishedAt() != null ? b.getPublishedAt() : b.getCreatedAt();
            return ap.compareTo(bp);
        });

        List<SurveyComparisonResponse.SurveySummary> summaries = new ArrayList<>();
        for (SurveyEntity s : surveys) {
            double rate = (s.getTargetCount() != null && s.getTargetCount() > 0)
                    ? (double) s.getResponseCount() / s.getTargetCount() * 100.0
                    : 0.0;
            summaries.add(new SurveyComparisonResponse.SurveySummary(
                    s.getId(),
                    s.getTitle(),
                    s.getPublishedAt(),
                    s.getClosedAt(),
                    s.getResponseCount(),
                    s.getTargetCount(),
                    Math.round(rate * 10.0) / 10.0));
        }

        // 設問マッチング: (questionText, questionType) を key にグループ化
        Map<String, List<QuestionRef>> questionsByKey = new HashMap<>();
        for (SurveyEntity s : surveys) {
            List<SurveyQuestionEntity> qs = questionRepository.findBySurveyIdOrderByDisplayOrderAsc(s.getId());
            for (SurveyQuestionEntity q : qs) {
                String key = q.getQuestionText() + "|" + q.getQuestionType().name();
                questionsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(new QuestionRef(s.getId(), q));
            }
        }

        List<SurveyComparisonResponse.QuestionComparison> comparisons = new ArrayList<>();
        for (Map.Entry<String, List<QuestionRef>> entry : questionsByKey.entrySet()) {
            List<QuestionRef> refs = entry.getValue();
            // 2 シリーズ以上で登場する設問のみ比較対象
            if (refs.size() < 2) {
                continue;
            }
            SurveyQuestionEntity sample = refs.get(0).question;
            if (sample.getQuestionType() == QuestionType.FREE_TEXT) {
                continue;
            }

            List<SurveyComparisonResponse.QuestionTrend> trends = new ArrayList<>();
            for (QuestionRef ref : refs) {
                trends.add(buildTrend(ref));
            }
            comparisons.add(new SurveyComparisonResponse.QuestionComparison(
                    sample.getQuestionText(),
                    sample.getQuestionType().name(),
                    trends));
        }

        log.info("シリーズ比較: seriesId={}, surveys={}, comparisons={}, by={}",
                seriesId, surveys.size(), comparisons.size(), currentUserId);
        return new SurveyComparisonResponse(seriesId, summaries, comparisons);
    }

    private SurveyComparisonResponse.QuestionTrend buildTrend(QuestionRef ref) {
        SurveyQuestionEntity q = ref.question;
        Long sid = ref.surveyId;

        if (q.getQuestionType() == QuestionType.SCALE) {
            // テキスト回答（数値文字列）の平均
            var responses = responseRepository.findBySurveyIdAndQuestionId(sid, q.getId());
            double sum = 0.0;
            int count = 0;
            for (var r : responses) {
                if (r.getTextResponse() != null) {
                    try {
                        sum += Double.parseDouble(r.getTextResponse());
                        count++;
                    } catch (NumberFormatException ignored) {
                        // 数値変換失敗は集計から除外
                    }
                }
            }
            double avg = count > 0 ? sum / count : 0.0;
            return new SurveyComparisonResponse.QuestionTrend(
                    sid, null, Math.round(avg * 100.0) / 100.0);
        }

        // SINGLE_CHOICE / MULTIPLE_CHOICE
        List<SurveyOptionEntity> options =
                optionRepository.findByQuestionIdOrderByDisplayOrderAsc(q.getId());
        long totalRespondents = responseRepository.countDistinctUsersBySurveyId(sid);
        List<SurveyComparisonResponse.OptionTrend> optionTrends = new ArrayList<>();
        for (SurveyOptionEntity opt : options) {
            long c = responseRepository.countBySurveyIdAndQuestionIdAndOptionId(
                    sid, q.getId(), opt.getId());
            double pct = totalRespondents > 0 ? (double) c / totalRespondents * 100.0 : 0.0;
            optionTrends.add(new SurveyComparisonResponse.OptionTrend(
                    opt.getOptionText(), Math.round(pct * 10.0) / 10.0));
        }
        return new SurveyComparisonResponse.QuestionTrend(sid, optionTrends, null);
    }

    /** シリーズ内の (surveyId, question) ペア参照。 */
    private record QuestionRef(Long surveyId, SurveyQuestionEntity question) {
    }
}
