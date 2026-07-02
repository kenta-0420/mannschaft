package com.mannschaft.app.social.announcement.adapter;

import com.mannschaft.app.social.announcement.AnnouncementContentRequest;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import com.mannschaft.app.survey.QuestionType;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.dto.CreateQuestionRequest;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import com.mannschaft.app.survey.dto.SurveyDetailResponse;
import com.mannschaft.app.survey.service.SurveyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F02.8 アンケートチャネルアダプター。
 *
 * <p>{@link SurveyService} を呼び出してアンケートを作成し、
 * 作成されたアンケートの ID を返す。</p>
 *
 * <p>告知ウィザードから作成されるアンケートは <b>自由記述の既定設問 1 問を同梱して作成し、
 * 直後に公開（PUBLISHED 化）する</b>。これにより受信者は告知直後からそのまま回答できる。
 * かつては設問ゼロの DRAFT で作成していたため「告知したのに回答不可」（{@code isAcceptingResponses()}
 * は PUBLISHED かつ設問必須、公開は設問 0 件だと {@code NO_QUESTIONS} で失敗）という矛盾があった。
 * 作成者は後からアンケート編集画面で設問を追加・編集できる。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SurveyAnnouncementAdapter implements AnnouncementChannelAdapter {

    /**
     * 告知ウィザード発のアンケートに同梱する既定設問（自由記述）のタイトル。
     *
     * <p>この文言はサーバ側で生成されアンケート本体（全受信者共通の保存コンテンツ）となるため、
     * 既定ロケール（ja）でハードコードする。i18n follow-up: 多言語化が必要になったら
     * 作成者ロケールに応じた MessageSource キー化を検討する（messages.properties は現状
     * エラー/メール文言用途が中心）。</p>
     */
    static final String DEFAULT_QUESTION_TEXT = "ご意見・ご感想をお聞かせください";

    private final SurveyService surveyService;

    @Override
    public AnnouncementSourceType getSourceType() {
        return AnnouncementSourceType.SURVEY;
    }

    @Override
    public Long createContent(AnnouncementContentRequest content, String scopeType,
                              Long scopeId, String targetRole, Long userId) {
        // 既定設問（自由記述・任意回答）を 1 問同梱する。これが無いと publishSurvey が
        // NO_QUESTIONS で失敗し、DRAFT のまま「告知したのに回答不可」になる。
        CreateQuestionRequest defaultQuestion = new CreateQuestionRequest(
                QuestionType.FREE_TEXT.name(), // questionType（自由記述）
                DEFAULT_QUESTION_TEXT,         // questionText（既定文言・ja）
                false,               // isRequired（任意回答）
                0,                   // displayOrder（先頭）
                null,                // maxSelections（自由記述では未使用）
                null,                // scaleMin
                null,                // scaleMax
                null,                // scaleMinLabel
                null,                // scaleMaxLabel
                null                 // options（自由記述では未使用）
        );

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
                content.getClosesAt(),   // expiresAt（closesAt として使用。null = 無期限でも公開可）
                List.of(defaultQuestion), // questions（既定 1 問。作成者は後から追加・編集可能）
                null,                // targetUserIds（全対象）
                null,                // resultViewerUserIds
                false,               // includeSupporters（既定 false）
                false                // teamBreakdownEnabled（既定 false）
        );

        SurveyDetailResponse response = surveyService.createSurvey(
                scopeType, scopeId, userId, request);

        Long surveyId = response.getSurvey().getId();

        // 作成直後に公開し PUBLISHED 化する（設問 1 問を同梱済みなので NO_QUESTIONS を通過）。
        // これで受信者は告知直後からそのまま回答できる。publish の失敗は握りつぶさず伝播させ、
        // 告知（broadcast）全体をロールバックさせる（設問ゼロ DRAFT の回答不可矛盾を残さない）。
        surveyService.publishSurvey(scopeType, scopeId, surveyId);

        log.info("アンケート作成・公開完了 surveyId={}, scopeType={}, scopeId={}",
                surveyId, scopeType, scopeId);
        return surveyId;
    }

    @Override
    public String buildContentUrl(String scopeType, Long scopeId, Long contentId) {
        String scopePath = "TEAM".equalsIgnoreCase(scopeType) ? "teams" : "organizations";
        return "/" + scopePath + "/" + scopeId + "/surveys/" + contentId;
    }
}
