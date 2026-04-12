package com.mannschaft.app.notification.confirmable.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationRecipientResponse;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.mapper.ConfirmableNotificationMapper;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F04.9 確認通知受信者コントローラー（個人向け）。
 *
 * <p>認証済みユーザーが自分宛ての保留中（未確認）確認通知を確認するためのエンドポイントを提供する。</p>
 */
@RestController
@RequestMapping("/api/v1/me/confirmable-notifications")
@Tag(name = "確認通知（個人）", description = "F04.9 ログインユーザーの保留中確認通知一覧")
@RequiredArgsConstructor
public class ConfirmableNotificationRecipientController {

    private final ConfirmableNotificationService notificationService;
    private final ConfirmableNotificationMapper mapper;

    /**
     * ログインユーザーの保留中（未確認・除外なし）確認通知一覧を取得する。
     *
     * <p>認証済みユーザーであればロールを問わずアクセス可能。</p>
     */
    @GetMapping("/pending")
    @Operation(summary = "保留中確認通知一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ConfirmableNotificationRecipientResponse>>> listPending() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        List<ConfirmableNotificationRecipientEntity> recipients =
                notificationService.listPending(currentUserId);
        List<ConfirmableNotificationRecipientResponse> responses =
                mapper.toRecipientResponseList(recipients);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }
}
