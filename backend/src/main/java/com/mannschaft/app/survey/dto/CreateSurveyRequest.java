package com.mannschaft.app.survey.dto;

import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.UnrespondedVisibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * アンケート作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateSurveyRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @Size(max = 5000)
    private final String description;

    @NotNull
    private final Boolean isAnonymous;

    @NotNull
    private final Boolean allowMultipleSubmissions;

    /**
     * 結果公開設定。未知値は Jackson の束縛段階で弾かれ 400 となる（#2617-1）。
     */
    @NotNull
    private final ResultsVisibility resultsVisibility;

    /**
     * 配信方式。未知値は Jackson の束縛段階で弾かれ 400 となる（#2617-1）。
     */
    @NotNull
    private final DistributionMode distributionMode;

    /**
     * 未回答者一覧の公開範囲。HIDDEN / CREATOR_AND_ADMIN / ALL_MEMBERS。
     * 省略時は CREATOR_AND_ADMIN（既存挙動と同等）。
     */
    private final UnrespondedVisibility unrespondedVisibility;

    private final Boolean autoPostToTimeline;

    @Size(max = 50)
    private final String seriesId;

    private final List<Integer> remindBeforeHours;

    private final LocalDateTime startsAt;

    private final LocalDateTime expiresAt;

    @Valid
    private final List<CreateQuestionRequest> questions;

    private final List<Long> targetUserIds;

    private final List<Long> resultViewerUserIds;

    /**
     * 配信母集団にサポーター（応援者）を含めるか。省略時 false（組織配信時はサポーター除外）。
     * (B) 組織→参加チーム配信 案C フェーズA 隊A で追加。値を使った母集団絞り込みは後続隊。
     */
    private final Boolean includeSupporters;

    /**
     * アンケート集計をチーム別内訳（by_team）でも収集・表示するか。省略時 false（従来挙動）。
     * (B) 組織→参加チーム配信 案C フェーズB（アンケートのチーム別内訳）で追加。
     *
     * <p><b>御裁可B（匿名保護）</b>: 匿名アンケート（{@code isAnonymous = true}）× 本トグル ON の
     * 併用は禁止。{@code SurveyService.createSurvey} が作成時に
     * {@link com.mannschaft.app.survey.SurveyErrorCode#ANONYMOUS_TEAM_BREAKDOWN_CONFLICT} で弾く（400）。</p>
     */
    private final Boolean teamBreakdownEnabled;
}
