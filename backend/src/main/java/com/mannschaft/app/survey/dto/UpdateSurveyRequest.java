package com.mannschaft.app.survey.dto;

import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.UnrespondedVisibility;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * アンケート更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateSurveyRequest {

    @Size(max = 200)
    private final String title;

    @Size(max = 5000)
    private final String description;

    private final Boolean isAnonymous;

    private final Boolean allowMultipleSubmissions;

    /** 結果公開設定。null=変更なし。未知値は束縛段階で 400（#2617-1）。 */
    private final ResultsVisibility resultsVisibility;

    /**
     * 未回答者一覧の公開範囲。HIDDEN / CREATOR_AND_ADMIN / ALL_MEMBERS。null=変更なし。
     */
    private final UnrespondedVisibility unrespondedVisibility;

    private final Boolean autoPostToTimeline;

    private final List<Integer> remindBeforeHours;

    private final LocalDateTime startsAt;

    private final LocalDateTime expiresAt;

    /**
     * 配信母集団にサポーター（応援者）を含めるか。null=変更なし。
     * (B) 組織→参加チーム配信 案C フェーズA 隊A で追加。
     */
    private final Boolean includeSupporters;
}
