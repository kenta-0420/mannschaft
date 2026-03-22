package com.mannschaft.app.notification.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.dto.SnoozeRequest;
import com.mannschaft.app.notification.dto.UnreadCountResponse;
import com.mannschaft.app.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 通知コントローラー。ログインユーザーの通知管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "通知", description = "F04.3 通知管理")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;


    /**
     * 通知一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "通知一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<NotificationResponse>> listNotifications(Pageable pageable) {
        Page<NotificationResponse> page = notificationService.listNotifications(SecurityUtils.getCurrentUserId(), pageable);
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(page.getContent(), meta));
    }

    /**
     * 未読通知件数を取得する。
     */
    @GetMapping("/unread-count")
    @Operation(summary = "未読通知件数")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> getUnreadCount() {
        UnreadCountResponse response = notificationService.getUnreadCount(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 通知を既読にする。
     */
    @PostMapping("/{notificationId}/read")
    @Operation(summary = "通知既読")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "既読成功")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsRead(
            @PathVariable Long notificationId) {
        NotificationResponse response = notificationService.markAsRead(SecurityUtils.getCurrentUserId(), notificationId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 通知を未読に戻す。
     */
    @PostMapping("/{notificationId}/unread")
    @Operation(summary = "通知未読戻し")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "未読戻し成功")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsUnread(
            @PathVariable Long notificationId) {
        NotificationResponse response = notificationService.markAsUnread(SecurityUtils.getCurrentUserId(), notificationId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 通知をスヌーズする。
     */
    @PostMapping("/{notificationId}/snooze")
    @Operation(summary = "通知スヌーズ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "スヌーズ成功")
    public ResponseEntity<ApiResponse<NotificationResponse>> snoozeNotification(
            @PathVariable Long notificationId,
            @Valid @RequestBody SnoozeRequest request) {
        NotificationResponse response = notificationService.snoozeNotification(
                SecurityUtils.getCurrentUserId(), notificationId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 全通知を既読にする。
     */
    @PostMapping("/read-all")
    @Operation(summary = "全件既読")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "全件既読成功")
    public ResponseEntity<ApiResponse<Integer>> markAllAsRead() {
        int count = notificationService.markAllAsRead(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(count));
    }
}
