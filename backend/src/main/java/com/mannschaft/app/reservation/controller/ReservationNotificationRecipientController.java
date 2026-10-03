package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reservation.dto.CreateNotificationRecipientRequest;
import com.mannschaft.app.reservation.dto.NotificationRecipientListResponse;
import com.mannschaft.app.reservation.dto.NotificationRecipientResponse;
import com.mannschaft.app.reservation.dto.UpdateNotificationRecipientRequest;
import com.mannschaft.app.reservation.service.ReservationNotificationRecipientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 予約通知メール宛先コントローラー（機能D）。
 *
 * <p>チーム単位の予約通知メール宛先の CRUD を提供する。全操作は管理者・副管理者限定
 * （{@code @PreAuthorize} の self-gate。設計 §2/§6・#2099 のマージ順に依存しない）。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/reservation-notification-recipients")
@Tag(name = "予約通知メール宛先", description = "F03.4 機能D 予約通知メール宛先（フリーミアム件数ゲート）")
@RequiredArgsConstructor
public class ReservationNotificationRecipientController {

    private final ReservationNotificationRecipientService recipientService;

    /**
     * 宛先一覧＋フリーミアム状態を取得する。
     */
    @GetMapping
    @Operation(summary = "予約通知メール宛先 一覧（フリーミアム状態付き）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<NotificationRecipientListResponse>> listRecipients(
            @PathVariable Long teamId) {
        return ResponseEntity.ok(ApiResponse.of(recipientService.listRecipients(teamId)));
    }

    /**
     * 宛先を追加する（フリーミアム件数ゲート・重複 409）。
     */
    @PostMapping
    @Operation(summary = "予約通知メール宛先 追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<NotificationRecipientResponse>> addRecipient(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateNotificationRecipientRequest request) {
        NotificationRecipientResponse response =
                recipientService.addRecipient(teamId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 宛先を部分更新する（{@code label} / {@code isEnabled}）。
     */
    @PatchMapping("/{recipientId}")
    @Operation(summary = "予約通知メール宛先 更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<NotificationRecipientResponse>> updateRecipient(
            @PathVariable Long teamId,
            @PathVariable UUID recipientId,
            @Valid @RequestBody UpdateNotificationRecipientRequest request) {
        return ResponseEntity.ok(ApiResponse.of(
                recipientService.updateRecipient(teamId, recipientId, request)));
    }

    /**
     * 宛先を削除する（物理削除）。
     */
    @DeleteMapping("/{recipientId}")
    @Operation(summary = "予約通知メール宛先 削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<Void> deleteRecipient(
            @PathVariable Long teamId,
            @PathVariable UUID recipientId) {
        recipientService.deleteRecipient(teamId, recipientId);
        return ResponseEntity.noContent().build();
    }
}
