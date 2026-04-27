package com.mannschaft.app.survey;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.survey.controller.SurveyResultController;
import com.mannschaft.app.survey.dto.RemindResponse;
import com.mannschaft.app.survey.service.SurveyRemindService;
import com.mannschaft.app.survey.service.SurveyResultService;
import com.mannschaft.app.survey.service.SurveyService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * {@link SurveyResultController} の単体テスト。
 *
 * <p>F05.4 督促 API の HTTP レスポンス（200 / 例外時の挙動）を中心に、
 * Service への委譲・SecurityUtils 経由の認証ユーザーID取得を検証する。
 * 401（未認証）・403/400/404 への HTTP マッピングは {@code GlobalExceptionHandler} の責務であり、
 * 単体テストでは Service が投げる例外がそのまま伝播することを確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyResultController 単体テスト")
class SurveyResultControllerTest {

    @Mock
    private SurveyResultService resultService;

    @Mock
    private SurveyService surveyService;

    @Mock
    private SurveyRemindService remindService;

    @InjectMocks
    private SurveyResultController controller;

    private static final Long SURVEY_ID = 100L;
    private static final Long USER_ID = 10L;

    @Nested
    @DisplayName("remind POST /surveys/{surveyId}/remind")
    class Remind {

        @Test
        @DisplayName("POST_remind_正常系_200OKとレスポンスにremindedCountが含まれる")
        void POST_remind_正常系_200OK() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                RemindResponse remindResponse =
                        new RemindResponse(SURVEY_ID, 5, 2, "5名にリマインド送信しました");
                given(remindService.remind(SURVEY_ID, USER_ID)).willReturn(remindResponse);

                // When
                ResponseEntity<ApiResponse<RemindResponse>> result = controller.remind(SURVEY_ID);

                // Then
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody()).isNotNull();
                assertThat(result.getBody().getData()).isNotNull();
                assertThat(result.getBody().getData().surveyId()).isEqualTo(SURVEY_ID);
                assertThat(result.getBody().getData().remindedCount()).isEqualTo(5);
                assertThat(result.getBody().getData().remainingRemindQuota()).isEqualTo(2);
                verify(remindService).remind(SURVEY_ID, USER_ID);
            }
        }

        @Test
        @DisplayName("POST_remind_認可失敗_BusinessException SURVEY_014を伝播")
        void POST_remind_認可失敗_例外伝播() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given: Service が REMIND_PERMISSION_DENIED を投げる
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                willThrow(new BusinessException(SurveyErrorCode.REMIND_PERMISSION_DENIED))
                        .given(remindService).remind(SURVEY_ID, USER_ID);

                // When & Then: 例外が伝播する（実 HTTP 403 マッピングは GlobalExceptionHandler 担当）
                assertThatThrownBy(() -> controller.remind(SURVEY_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                                .isEqualTo(SurveyErrorCode.REMIND_PERMISSION_DENIED));
            }
        }

        @Test
        @DisplayName("POST_remind_状態エラー_BusinessException INVALID_SURVEY_STATUSを伝播")
        void POST_remind_状態エラー_例外伝播() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                willThrow(new BusinessException(SurveyErrorCode.INVALID_SURVEY_STATUS))
                        .given(remindService).remind(SURVEY_ID, USER_ID);

                // When & Then
                assertThatThrownBy(() -> controller.remind(SURVEY_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                                .isEqualTo(SurveyErrorCode.INVALID_SURVEY_STATUS));
            }
        }

        @Test
        @DisplayName("POST_remind_クールダウン中_BusinessException REMIND_COOLDOWN_NOT_ELAPSEDを伝播")
        void POST_remind_クールダウン中_例外伝播() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                willThrow(new BusinessException(SurveyErrorCode.REMIND_COOLDOWN_NOT_ELAPSED))
                        .given(remindService).remind(SURVEY_ID, USER_ID);

                // When & Then
                assertThatThrownBy(() -> controller.remind(SURVEY_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                                .isEqualTo(SurveyErrorCode.REMIND_COOLDOWN_NOT_ELAPSED));
            }
        }

        @Test
        @DisplayName("POST_remind_アンケート存在しない_BusinessException SURVEY_NOT_FOUNDを伝播")
        void POST_remind_アンケート存在しない_例外伝播() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                // Given
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                willThrow(new BusinessException(SurveyErrorCode.SURVEY_NOT_FOUND))
                        .given(remindService).remind(SURVEY_ID, USER_ID);

                // When & Then
                assertThatThrownBy(() -> controller.remind(SURVEY_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                                .isEqualTo(SurveyErrorCode.SURVEY_NOT_FOUND));
            }
        }
    }
}
