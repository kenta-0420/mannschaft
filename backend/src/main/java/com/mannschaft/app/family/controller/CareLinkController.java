package com.mannschaft.app.family.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.family.dto.CareLinkNotifySettingsRequest;
import com.mannschaft.app.family.dto.CareLinkResponse;
import com.mannschaft.app.family.dto.InviteRecipientRequest;
import com.mannschaft.app.family.dto.InviteWatcherRequest;
import com.mannschaft.app.family.service.CareLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ケアリンクコントローラー（認証済みユーザー操作）。
 * 自分に紐付くケアリンクの管理 API を提供する。F03.12。
 */
@RestController
@RequestMapping("/api/v1/me/care-links")
@Tag(name = "ケアリンク", description = "F03.12 ケア対象者イベント参加見守り通知システム")
@RequiredArgsConstructor
public class CareLinkController {

    private final CareLinkService careLinkService;

    /**
     * 自分がケア対象者として登録されているアクティブな見守り者一覧を取得する。
     */
    @GetMapping("/watchers")
    @Operation(summary = "見守り者一覧取得（ケア対象者視点）")
    public ResponseEntity<ApiResponse<List<CareLinkResponse>>> getActiveWatchers() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                careLinkService.getActiveLinksForCareRecipient(currentUserId)));
    }

    /**
     * 自分が見守る対象者一覧（ウォッチ中）を取得する。
     */
    @GetMapping("/recipients")
    @Operation(summary = "ケア対象者一覧取得（見守り者視点）")
    public ResponseEntity<ApiResponse<List<CareLinkResponse>>> getActiveRecipients() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                careLinkService.getActiveLinksForWatcher(currentUserId)));
    }

    /**
     * 保留中の招待一覧を取得する。
     */
    @GetMapping("/invitations")
    @Operation(summary = "保留中招待一覧取得")
    public ResponseEntity<ApiResponse<List<CareLinkResponse>>> getPendingInvitations() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                careLinkService.getPendingInvitationsForUser(currentUserId)));
    }

    /**
     * ケア対象者が見守り者を招待する。
     */
    @PostMapping("/invite-watcher")
    @Operation(summary = "見守り者招待（ケア対象者から）")
    public ResponseEntity<ApiResponse<CareLinkResponse>> inviteWatcher(
            @Valid @RequestBody InviteWatcherRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(
                careLinkService.inviteWatcher(currentUserId, request)));
    }

    /**
     * 見守り者がケア対象者を招待する。
     */
    @PostMapping("/invite-recipient")
    @Operation(summary = "ケア対象者招待（見守り者から）")
    public ResponseEntity<ApiResponse<CareLinkResponse>> inviteRecipient(
            @Valid @RequestBody InviteRecipientRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(
                careLinkService.inviteCareRecipient(currentUserId, request)));
    }

    /**
     * ケアリンクの通知設定を更新する。
     */
    @PatchMapping("/{linkId}")
    @Operation(summary = "ケアリンク通知設定更新")
    public ResponseEntity<ApiResponse<CareLinkResponse>> updateNotifySettings(
            @PathVariable Long linkId,
            @RequestBody CareLinkNotifySettingsRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                careLinkService.updateNotifySettings(linkId, currentUserId, request)));
    }

    /**
     * ケアリンクを解除する。
     */
    @DeleteMapping("/{linkId}")
    @Operation(summary = "ケアリンク解除")
    public ResponseEntity<Void> revokeLink(@PathVariable Long linkId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        careLinkService.revokeLink(linkId, currentUserId);
        return ResponseEntity.noContent().build();
    }
}
