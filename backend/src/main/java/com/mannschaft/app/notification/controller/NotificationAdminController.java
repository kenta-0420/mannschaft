package com.mannschaft.app.notification.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.notification.dto.NotificationStatsResponse;
import com.mannschaft.app.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知管理者コントローラー。管理者向けの通知統計APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@Tag(name = "通知管理(管理者)", description = "F04.3 管理者向け通知統計")
@RequiredArgsConstructor
public class NotificationAdminController {

    private final NotificationService notificationService;

    /**
     * 通知統計を取得する。
     */
    @GetMapping("/stats")
    @Operation(summary = "通知統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<NotificationStatsResponse>> getStats() {
        NotificationStatsResponse response = notificationService.getStats();
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
