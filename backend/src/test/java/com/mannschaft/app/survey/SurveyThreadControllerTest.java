package com.mannschaft.app.survey;

import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.service.SurveyBulletinThreadService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.survey.controller.SurveyThreadController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link SurveyThreadController} の単体テスト。
 * アンケート専用掲示板スレッド取得APIを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyThreadController 単体テスト")
class SurveyThreadControllerTest {

    @Mock
    private SurveyBulletinThreadService surveyBulletinThreadService;

    @Mock
    private BulletinMapper bulletinMapper;

    @InjectMocks
    private SurveyThreadController surveyThreadController;

    private static final Long SURVEY_ID = 1L;

    @Nested
    @DisplayName("GET /{surveyId}/thread")
    class GetSurveyThread {

        @Test
        @DisplayName("スレッドが存在する場合は 200 と ThreadResponse を返す")
        void スレッド存在時は200を返す() {
            // given
            BulletinThreadEntity threadEntity = BulletinThreadEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION)
                    .scopeId(10L)
                    .title("テストアンケート — 掲示板")
                    .body("")
                    .sourceType("SURVEY")
                    .sourceId(SURVEY_ID)
                    .build();
            ThreadResponse threadResponse = new ThreadResponse(
                    1L, null, "ORGANIZATION", 10L, null,
                    "テストアンケート — 掲示板", "", "NORMAL", "ALL",
                    false, false, false, null, 0, 0, null,
                    "SURVEY", SURVEY_ID, null, null
            );
            given(surveyBulletinThreadService.findBySurveyId(SURVEY_ID)).willReturn(Optional.of(threadEntity));
            given(bulletinMapper.toThreadResponse(threadEntity)).willReturn(threadResponse);

            // when
            ResponseEntity<ApiResponse<ThreadResponse>> response = surveyThreadController.getSurveyThread(SURVEY_ID);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getData()).isEqualTo(threadResponse);
        }

        @Test
        @DisplayName("スレッドが存在しない場合は 404 を返す")
        void スレッド未存在時は404を返す() {
            // given
            given(surveyBulletinThreadService.findBySurveyId(SURVEY_ID)).willReturn(Optional.empty());

            // when
            ResponseEntity<ApiResponse<ThreadResponse>> response = surveyThreadController.getSurveyThread(SURVEY_ID);

            // then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
