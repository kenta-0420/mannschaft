package com.mannschaft.app.moderation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.moderation.dto.CreateReportRequest;
import com.mannschaft.app.moderation.dto.ReportResponse;
import com.mannschaft.app.moderation.service.ContentReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ContentReportController} の単体テスト。
 *
 * <p>ContentReportController#createReport の自己スコープ性を固定する契約テスト。
 * 通報作成の {@code reportedBy} は常に {@code SecurityUtils.getCurrentUserId()} が渡され、
 * リクエストボディで通報者本人を偽装する余地が構造的に無い
 * （通報対象 {@code targetId} は任意のコンテンツを指せるが、それは通報機能の意図した挙動である）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContentReportController 単体テスト")
class ContentReportControllerTest {

    @Mock
    private ContentReportService reportService;

    @InjectMocks
    private ContentReportController controller;

    private static final Long USER_ID = 100L;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUpSecurityUtils() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDownSecurityUtils() {
        securityUtils.close();
    }

    @Test
    @DisplayName("createReport は reportedBy に SecurityUtils.getCurrentUserId() のみを渡す")
    void createReport_boundToCurrentUserOnly() {
        CreateReportRequest request = new CreateReportRequest(
                "TIMELINE_POST", 1L, "SPAM", null, null, null, null, null);
        ReportResponse response = Mockito.mock(ReportResponse.class);
        given(reportService.createReport(any(CreateReportRequest.class), eq(USER_ID)))
                .willReturn(response);

        ResponseEntity<ApiResponse<ReportResponse>> result = controller.createReport(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // 他人の userId を通報者として渡す経路が存在しないことの裏取り。
        verify(reportService).createReport(request, USER_ID);
    }
}
