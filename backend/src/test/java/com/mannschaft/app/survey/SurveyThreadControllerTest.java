package com.mannschaft.app.survey;

import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.service.SurveyBulletinThreadService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.survey.controller.SurveyThreadController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;

/**
 * {@link SurveyThreadController} の単体テスト。
 * アンケート専用掲示板スレッド取得APIを検証する（フラット enrich 済みレスポンス）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyThreadController 単体テスト")
class SurveyThreadControllerTest {

    @Mock
    private SurveyBulletinThreadService surveyBulletinThreadService;

    @InjectMocks
    private SurveyThreadController surveyThreadController;

    private static final Long SURVEY_ID = 1L;
    private static final Long CURRENT_USER_ID = 77L;

    @Nested
    @DisplayName("GET /{surveyId}/thread")
    class GetSurveyThread {

        @Test
        @DisplayName("スレッドが存在する場合は 200 と フラット ThreadResponse を返す")
        void スレッド存在時は200を返す() {
            // given
            ThreadResponse threadResponse = ThreadResponse.builder()
                    .id(1L)
                    .categoryId(null)
                    .scopeType("ORGANIZATION")
                    .scopeId(10L)
                    .author(new ThreadResponse.AuthorDto(null, null, null))
                    .title("テストアンケート — 掲示板")
                    .body("")
                    .priority("INFO")
                    .readTrackingMode("COUNT_ONLY")
                    .isPinned(false)
                    .isLocked(false)
                    .isArchived(false)
                    .replyCount(0)
                    .readCount(0)
                    .isRead(false)
                    .reactionSummary(Collections.emptyMap())
                    .myReactions(Collections.emptyList())
                    .sourceType("SURVEY")
                    .sourceId(SURVEY_ID)
                    .build();

            try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
                security.when(SecurityUtils::getCurrentUserId).thenReturn(CURRENT_USER_ID);
                given(surveyBulletinThreadService.findThreadResponseBySurveyId(eq(SURVEY_ID), eq(CURRENT_USER_ID)))
                        .willReturn(Optional.of(threadResponse));

                // when
                ResponseEntity<ApiResponse<ThreadResponse>> response =
                        surveyThreadController.getSurveyThread(SURVEY_ID);

                // then
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().getData()).isEqualTo(threadResponse);
            }
        }

        @Test
        @DisplayName("スレッドが存在しない場合は 404 を返す")
        void スレッド未存在時は404を返す() {
            // given
            try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
                security.when(SecurityUtils::getCurrentUserId).thenReturn(CURRENT_USER_ID);
                given(surveyBulletinThreadService.findThreadResponseBySurveyId(eq(SURVEY_ID), eq(CURRENT_USER_ID)))
                        .willReturn(Optional.empty());

                // when
                ResponseEntity<ApiResponse<ThreadResponse>> response =
                        surveyThreadController.getSurveyThread(SURVEY_ID);

                // then
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            }
        }
    }
}
