package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.NotificationStatsResponse;
import com.mannschaft.app.admin.service.NotificationStatsService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * システム管理者向け通知配信統計コントローラー。
 */
@RestController
@RequestMapping("/api/v1/system-admin/notification-stats")
@Tag(name = "システム管理 - 通知統計", description = "F10.1 通知配信統計参照API")
@RequiredArgsConstructor
public class SystemAdminNotificationStatsController {

    private final NotificationStatsService notificationStatsService;

    /**
     * 通知配信統計を取得する。
     */
    @GetMapping
    @Operation(summary = "通知配信統計取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<NotificationStatsResponse>>> getStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String channel) {
        List<NotificationStatsResponse> stats;
        if (channel != null && !channel.isBlank()) {
            stats = notificationStatsService.getStatsByChannel(channel, from, to);
        } else {
            stats = notificationStatsService.getStats(from, to);
        }
        return ResponseEntity.ok(ApiResponse.of(stats));
    }
}
