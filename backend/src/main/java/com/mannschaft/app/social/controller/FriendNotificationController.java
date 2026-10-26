package com.mannschaft.app.social.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.social.dto.FriendNotificationDeliveryResponse;
import com.mannschaft.app.social.dto.FriendNotificationSendRequest;
import com.mannschaft.app.social.service.FriendNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * フレンド通知 REST コントローラ（F01.5 Phase 2）。
 *
 * <ul>
 *   <li>{@code GET  /api/v1/teams/{id}/friend-notifications} — フレンド受信通知一覧</li>
 *   <li>{@code POST /api/v1/teams/{id}/friend-notifications/send} — フレンドへ通知送信</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/teams/{id}/friend-notifications")
@Tag(name = "フレンド通知", description = "F01.5 Phase 2 フレンドチーム通知送受信")
@RequiredArgsConstructor
public class FriendNotificationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final FriendNotificationService friendNotificationService;

    /**
     * フレンド受信通知一覧を取得する。
     *
     * @param id     チーム ID
     * @param isRead 既読フィルタ（null = 全件）
     * @param page   ページ番号（0始まり）
     * @param size   ページサイズ（最大100）
     * @return 通知一覧ページ
     */
    @GetMapping
    @Operation(summary = "フレンド受信通知一覧", description = "フレンドチームから届いた通知一覧を取得する（管理者専用）")
    public ResponseEntity<ApiResponse<Page<NotificationResponse>>> listFriendNotifications(
            @PathVariable Long id,
            @RequestParam(required = false) Boolean isRead,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safeSize = Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(page, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Long userId = SecurityUtils.getCurrentUserId();
        Page<NotificationResponse> result =
                friendNotificationService.listFriendNotifications(id, userId, isRead, pageable);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * フレンドチームへ通知を送信する（202 Accepted）。
     *
     * @param id      チーム ID
     * @param request 送信リクエスト
     * @return 配信サマリ
     */
    @PostMapping("/send")
    @Operation(summary = "フレンドへ通知送信", description = "フレンドチームまたはフォルダへ通知を送信する（202 Accepted）")
    public ResponseEntity<ApiResponse<FriendNotificationDeliveryResponse>> sendFriendNotification(
            @PathVariable Long id,
            @Valid @RequestBody FriendNotificationSendRequest request) {

        Long userId = SecurityUtils.getCurrentUserId();
        FriendNotificationDeliveryResponse response =
                friendNotificationService.sendFriendNotification(id, userId, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(response));
    }
}
