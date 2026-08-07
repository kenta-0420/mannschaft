package com.mannschaft.app.moderation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.moderation.dto.CreateReportRequest;
import com.mannschaft.app.moderation.dto.ReportResponse;
import com.mannschaft.app.moderation.service.ContentReportService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * コンテンツ通報コントローラー（ユーザー用）。コンテンツ通報APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "コンテンツ通報", description = "F04.5 ユーザーによるコンテンツ通報")
@RequiredArgsConstructor
public class ContentReportController {

    private final ContentReportService reportService;


    /**
     * コンテンツを通報する。
     *
     * <p><b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * 通報者（{@code reportedBy}）は常に {@code SecurityUtils.getCurrentUserId()} が渡され、
     * リクエストボディで通報者本人を偽装する経路が構造的に無い（対象コンテンツ {@code targetId}
     * は通報機能の意図どおり任意を指せる。ContentReportController#createReport）。</p>
     *
     * <p>認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "reportService.createReport の reportedBy は SecurityUtils.getCurrentUserId() のみで"
                    + "束縛される（ContentReportController#createReport）")
    @PostMapping
    @Operation(summary = "コンテンツ通報")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "通報成功")
    public ResponseEntity<ApiResponse<ReportResponse>> createReport(
            @Valid @RequestBody CreateReportRequest request) {
        ReportResponse response = reportService.createReport(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
