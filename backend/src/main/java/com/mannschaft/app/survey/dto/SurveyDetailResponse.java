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
     * この応答を受け取る閲覧者が、当該アンケートの結果を閲覧できるか。
     *
     * <p>Issue #2779: これが無かった頃、フロントエンドは結果取得 API を 1 回余分に叩き
     * 403 が返るかどうかで可否を判定していた。値は結果取得 API が 403 を投げるのと
     * <b>同じ判定点</b>（{@code SurveyResultAccessGuard}）から得ているため、
     * {@code true} なら結果取得は必ず 200、{@code false} なら必ず 403 になる。</p>
     */
    @io.swagger.v3.oas.annotations.media.Schema(
            description = "この閲覧者がアンケート結果を閲覧できるか。true なら結果取得 API は 200、"
                    + "false なら 403 を返す（結果取得の 403 プローブは不要）",
            example = "true")
    private final Boolean viewerCanViewResults;

    /**
     * フラットな {@link SurveyResponse} と設問一覧から詳細レスポンスを組み立てる。
     *
     * <p>一覧・更新等が返す {@link SurveyResponse} と本 DTO のフィールドを 1 箇所で対応付けるため、
     * 呼び出し側では本ファクトリを使う（フィールドの取りこぼしを 1 箇所に閉じ込める）。</p>
     *
     * @param survey               アンケート本体（null 不可でない場合は全フィールド null で構築される）
     * @param questions            設問一覧（null 可）
     * @param viewerCanViewResults 閲覧者が結果を閲覧できるか
     * @return フラット形の詳細レスポンス
     */
    public static SurveyDetailResponse of(SurveyResponse survey, List<QuestionResponse> questions,
                                          boolean viewerCanViewResults) {
        SurveyDetailResponseBuilder builder = SurveyDetailResponse.builder()
                .questions(questions)
                .viewerCanViewResults(viewerCanViewResults);
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
