package com.mannschaft.app.social.announcement.adapter;

import com.mannschaft.app.social.announcement.AnnouncementContentRequest;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import com.mannschaft.app.survey.dto.SurveyDetailResponse;
import com.mannschaft.app.survey.service.SurveyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * F02.8 アンケートチャネルアダプター。
 *
 * <p>{@link SurveyService} を呼び出してアンケートを作成し、
 * 作成されたアンケートの ID を返す。</p>
 *
 * <p>告知ウィザードから作成されるアンケートは設問なし（タイトル・説明のみ）で
 * 最小限の状態で作成される。詳細な設問は ADMIN が後からアンケート編集画面で設定することを想定する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SurveyAnnouncementAdapter implements AnnouncementChannelAdapter {

    private final SurveyService surveyService;

    @Override
    public AnnouncementSourceType getSourceType() {
        return AnnouncementSourceType.SURVEY;
    }

    @Override
    public Long createContent(AnnouncementContentRequest content, String scopeType,
                              Long scopeId, String targetRole, Long userId) {
        CreateSurveyRequest request = new CreateSurveyRequest(
                content.getTitle(),
                content.getDescription(),   // description
                false,               // isAnonymous（デフォルト false）
                false,               // allowMultipleSubmissions（デフォルト false）
                ResultsVisibility.AFTER_RESPONSE.name(), // resultsVisibility（回答後に結果を閲覧可）
                "ALL",               // distributionMode（全メンバー対象）
                "CREATOR_AND_ADMIN", // unrespondedVisibility
                false,               // autoPostToTimeline
                null,                // seriesId
                null,                // remindBeforeHours
                null,                    // startsAt（即時開始）
                content.getClosesAt(),   // expiresAt（closesAt として使用）
                Collections.emptyList(), // questions（告知ウィザードは設問なしで作成）
                null,                // targetUserIds（全対象）
                null,                // resultViewerUserIds
                false,               // includeSupporters（既定 false）
                false                // teamBreakdownEnabled（既定 false）
        );

        SurveyDetailResponse response = surveyService.createSurvey(
                scopeType, scopeId, userId, request);

        Long surveyId = response.getSurvey().getId();
        log.info("アンケート作成完了 surveyId={}, scopeType={}, scopeId={}",
                surveyId, scopeType, scopeId);
        return surveyId;
    }

    @Override
    public String buildContentUrl(String scopeType, Long scopeId, Long contentId) {
        String scopePath = "TEAM".equalsIgnoreCase(scopeType) ? "teams" : "organizations";
        return "/" + scopePath + "/" + scopeId + "/surveys/" + contentId;
    }
}
