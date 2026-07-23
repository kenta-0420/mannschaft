package com.mannschaft.app.activity;

import com.mannschaft.app.activity.controller.ActivityPublicController;
import com.mannschaft.app.activity.dto.ActivityRecordResponse;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.service.ActivityResultService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
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
 * {@link ActivityPublicController} の単体テスト。
 * F06.4 SNS シェア用 ID 直引きエンドポイント {@code GET /api/v1/public/activities/{id}} を検証する。
 *
 * <p>DTO 化（{@code ActivityRecordResponse}）に伴い、Entity 直返し前提だったアサートを
 * {@code ActivityMapper} 経由の DTO 前提に書き換えている。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityPublicController 単体テスト")
class ActivityPublicControllerTest {

    @Mock
    private ActivityResultService activityService;

    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;

    @Mock
    private ActivityMapper activityMapper;

    @InjectMocks
    private ActivityPublicController controller;

    private static final Long ACTIVITY_ID = 42L;

    @Nested
    @DisplayName("getPublicActivityById — GET /api/v1/public/activities/{id}")
    class GetPublicActivityById {

        @Test
        @DisplayName("正常系: visibility=PUBLIC の記録は 200 と本文を返す")
        void 公開記録_200返却() {
            ActivityResultEntity entity = ActivityResultEntity.builder()
                    .visibility(ActivityVisibility.PUBLIC)
                    .title("公開練習記録")
                    .build();
            ActivityRecordResponse dto = ActivityRecordResponse.builder()
                    .visibility(ActivityVisibility.PUBLIC.name())
                    .title("公開練習記録")
                    .build();
            given(activityService.findPublicActivityById(ACTIVITY_ID))
                    .willReturn(Optional.of(entity));
            given(activityMapper.toActivityRecordResponse(entity)).willReturn(dto);

            ResponseEntity<ApiResponse<ActivityRecordResponse>> response =
                    controller.getPublicActivityById(ACTIVITY_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getData()).isEqualTo(dto);
        }

        @Test
        @DisplayName("異常系: visibility=MEMBERS_ONLY の記録は 404 を返す")
        void 非公開記録_404返却() {
            given(activityService.findPublicActivityById(ACTIVITY_ID))
                    .willReturn(Optional.empty());

            ResponseEntity<ApiResponse<ActivityRecordResponse>> response =
                    controller.getPublicActivityById(ACTIVITY_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: 存在しない ID は 404 を返す")
        void 存在しないId_404返却() {
            given(activityService.findPublicActivityById(9999L))
                    .willReturn(Optional.empty());

            ResponseEntity<ApiResponse<ActivityRecordResponse>> response =
                    controller.getPublicActivityById(9999L);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
