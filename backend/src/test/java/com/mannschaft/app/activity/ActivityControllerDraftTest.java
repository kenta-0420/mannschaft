package com.mannschaft.app.activity;

import com.mannschaft.app.activity.controller.ActivityController;
import com.mannschaft.app.activity.dto.ActivityRecordResponse;
import com.mannschaft.app.activity.dto.CreateDraftActivityRequest;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.service.ActivityResultService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;

/**
 * {@link ActivityController} の下書き（DRAFT）対応エンドポイントの契約テスト（F06.4 / AC-8・AC-9）。
 *
 * <p>POST /api/v1/activities/draft（下書き作成）と POST /api/v1/activities/{id}/publish（公開）を
 * コントローラ直接呼び出しで検証する（{@link SecurityUtils} は MockedStatic で固定）。</p>
 *
 * <p>DTO 化（{@code ActivityRecordResponse}）に伴い、Entity 直返し前提だったアサートを
 * {@code ActivityMapper} 経由の DTO 前提に書き換えている。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityController 下書き対応 契約テスト")
class ActivityControllerDraftTest {

    @Mock
    private ActivityResultService activityService;

    @Mock
    private ActivityMapper activityMapper;

    @InjectMocks
    private ActivityController controller;

    private static final Long USER_ID = 1L;
    private static final Long SCOPE_ID = 100L;
    private static final Long ACTIVITY_ID = 42L;

    @Nested
    @DisplayName("POST /api/v1/activities/draft（AC-8）")
    class CreateDraft {

        @Test
        @DisplayName("AC-8 正常系: 最小項目のDRAFT作成が201で返る")
        void 下書き作成_201() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                CreateDraftActivityRequest request = new CreateDraftActivityRequest(
                        "下書き", LocalDate.now(), null, null, null, null, null, null);
                ActivityResultEntity saved = ActivityResultEntity.builder()
                        .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title("下書き")
                        .status(ActivityStatus.DRAFT).build();
                ActivityRecordResponse dto = ActivityRecordResponse.builder()
                        .scopeType(ActivityScopeType.TEAM.name()).scopeId(SCOPE_ID).title("下書き")
                        .status(ActivityStatus.DRAFT.name()).build();
                given(activityService.createDraftActivity(
                        eq(USER_ID), eq(ActivityScopeType.TEAM), eq(SCOPE_ID), any(CreateDraftActivityRequest.class)))
                        .willReturn(saved);
                given(activityMapper.toActivityRecordResponse(saved)).willReturn(dto);

                ResponseEntity<ApiResponse<ActivityRecordResponse>> response =
                        controller.createDraftActivity("TEAM", SCOPE_ID, request);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().getData().getStatus()).isEqualTo(ActivityStatus.DRAFT.name());
            }
        }
    }

    @Nested
    @DisplayName("POST /api/v1/activities/{id}/publish（AC-9）")
    class Publish {

        @Test
        @DisplayName("AC-9 正常系: publishが200でPUBLISHEDを返す")
        void 公開_200() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                ActivityResultEntity published = ActivityResultEntity.builder()
                        .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title("公開")
                        .status(ActivityStatus.PUBLISHED).build();
                ActivityRecordResponse dto = ActivityRecordResponse.builder()
                        .scopeType(ActivityScopeType.TEAM.name()).scopeId(SCOPE_ID).title("公開")
                        .status(ActivityStatus.PUBLISHED.name()).build();
                given(activityService.publishActivity(ACTIVITY_ID, USER_ID)).willReturn(published);
                given(activityMapper.toActivityRecordResponse(published)).willReturn(dto);

                ResponseEntity<ApiResponse<ActivityRecordResponse>> response =
                        controller.publishActivity(ACTIVITY_ID);

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().getData().getStatus()).isEqualTo(ActivityStatus.PUBLISHED.name());
            }
        }

        @Test
        @DisplayName("AC-9 異常系: 既に公開済みのpublishはACTIVITY_021を伝播する")
        void 公開_既に公開済み_例外伝播() {
            try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
                mocked.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                given(activityService.publishActivity(ACTIVITY_ID, USER_ID))
                        .willThrow(new BusinessException(ActivityErrorCode.INVALID_ACTIVITY_STATUS));

                assertThatThrownBy(() -> controller.publishActivity(ACTIVITY_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                                .isEqualTo("ACTIVITY_021"));
            }
        }
    }
}
