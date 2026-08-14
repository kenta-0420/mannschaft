package com.mannschaft.app.survey.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * アンケート詳細レスポンスDTO。設問・選択肢を含む。
 *
 * <p><b>応答形（#2635）</b>: かつては {@code survey} キーの下に {@link SurveyResponse} を
 * 入れ子で抱えており、作成・詳細取得・複製だけが {@code data.survey.id}、
 * 一覧・更新・公開・締切・延長は {@code data.id} という非対称な契約になっていた。
 * 現在は {@link SurveyResponse} の 9 フィールドを直下にフラットに並べ、
 * {@code questions} を加える形へ揃えている（先例: {@code EventDetailResponse}）。</p>
 */
@Getter
@Builder
public class SurveyDetailResponse {

    private final Long id;
    private final com.mannschaft.app.survey.SurveyStatus status;
    private final SurveyResponse.SurveyScopeDto scope;
    private final SurveyResponse.SurveyContentDto content;
    private final SurveyResponse.SurveyPolicyDto policy;
    private final SurveyResponse.SurveyDistributionDto distribution;
    private final SurveyResponse.SurveyScheduleDto schedule;
    private final SurveyResponse.SurveyStatsDto stats;
    private final SurveyResponse.SurveyAuditDto audit;
    private final List<QuestionResponse> questions;

    /**
     * フラットな {@link SurveyResponse} と設問一覧から詳細レスポンスを組み立てる。
     *
     * <p>一覧・更新等が返す {@link SurveyResponse} と本 DTO のフィールドを 1 箇所で対応付けるため、
     * 呼び出し側では本ファクトリを使う（フィールドの取りこぼしを 1 箇所に閉じ込める）。</p>
     *
     * @param survey    アンケート本体（null 不可でない場合は全フィールド null で構築される）
     * @param questions 設問一覧（null 可）
     * @return フラット形の詳細レスポンス
     */
    public static SurveyDetailResponse of(SurveyResponse survey, List<QuestionResponse> questions) {
        SurveyDetailResponseBuilder builder = SurveyDetailResponse.builder().questions(questions);
        if (survey == null) {
            return builder.build();
        }
        return builder
                .id(survey.getId())
                .status(survey.getStatus())
                .scope(survey.getScope())
                .content(survey.getContent())
                .policy(survey.getPolicy())
                .distribution(survey.getDistribution())
                .schedule(survey.getSchedule())
                .stats(survey.getStats())
                .audit(survey.getAudit())
                .build();
    }
}
