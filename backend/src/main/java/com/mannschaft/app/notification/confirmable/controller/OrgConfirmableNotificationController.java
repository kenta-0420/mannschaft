package com.mannschaft.app.notification.confirmable.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationCreateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDetailResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationRecipientResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationResponse;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.mapper.ConfirmableNotificationMapper;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * F04.9 組織確認通知コントローラー。
 *
 * <p>確認通知の送信・一覧・詳細・キャンセル・リマインド再送・受信者一覧・確認APIを提供する。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/confirmable-notifications")
@Tag(name = "組織確認通知", description = "F04.9 組織確認通知 CRUD・ステータス管理")
@RequiredArgsConstructor
public class OrgConfirmableNotificationController {

    private final ConfirmableNotificationService notificationService;
    private final ConfirmableNotificationRecipientRepository recipientRepository;
    private final ConfirmableNotificationMapper mapper;
    private final AccessControlService accessControlService;

    /**
     * 確認通知を送信する。
     *
     * <p>受信者への確認トークン付与・リマインド設定解決を行い、F04.3通知基盤に引き渡す。</p>
     */
    @PostMapping
    @Operation(summary = "確認通知送信（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "送信成功")
    public ResponseEntity<ApiResponse<ConfirmableNotificationResponse>> send(
            @PathVariable Long orgId,
            @Valid @RequestBody ConfirmableNotificationCreateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ConfirmableNotificationEntity entity = notificationService.send(
                ScopeType.ORGANIZATION,
                orgId,
                request.getTitle(),
                request.getBody(),
                request.getPriority(),
                request.getDeadlineAt(),
                request.getFirstReminderMinutes(),
                request.getSecondReminderMinutes(),
                request.getActionUrl(),
                request.getTemplateId(),
                request.getUnconfirmedVisibility(),
                currentUserId,
                request.getRecipientUserIds());

        ConfirmableNotificationResponse response = mapper.toResponse(entity);
        // confirmedCount は送信直後なので0
        response.setConfirmedCount(0L);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 組織の確認通知一覧を取得する（作成日時降順）。
     */
    @GetMapping
    @Operation(summary = "確認通知一覧取得（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ConfirmableNotificationResponse>>> list(
            @PathVariable Long orgId) {
        List<ConfirmableNotificationEntity> entities =
                notificationService.listByScope(ScopeType.ORGANIZATION, orgId);
        List<ConfirmableNotificationResponse> responses = entities.stream()
                .map(entity -> {
                    ConfirmableNotificationResponse res = mapper.toResponse(entity);
                    // 確認済み受信者数をリポジトリから取得してセット
                    long confirmedCount = recipientRepository
                            .countByConfirmableNotificationIdAndIsConfirmedTrue(entity.getId());
                    res.setConfirmedCount(confirmedCount);
                    return res;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 確認通知の詳細を取得する。
     *
     * <p>スコープ整合チェック：通知のスコープがリクエストの orgId と一致することを確認する。</p>
     */
    @GetMapping("/{notificationId}")
    @Operation(summary = "確認通知詳細取得（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ConfirmableNotificationDetailResponse>> getDetail(
            @PathVariable Long orgId,
            @PathVariable Long notificationId) {
        ConfirmableNotificationEntity entity = notificationService.getDetail(notificationId);

        // スコープ整合チェック
        if (!ScopeType.ORGANIZATION.equals(entity.getScopeType()) || !orgId.equals(entity.getScopeId())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.SCOPE_MISMATCH);
        }

        ConfirmableNotificationDetailResponse response = mapper.toDetailResponse(entity);
        long confirmedCount = recipientRepository
                .countByConfirmableNotificationIdAndIsConfirmedTrue(notificationId);
        response.setConfirmedCount(confirmedCount);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 確認通知をキャンセルする。
     *
     * <p>ACTIVE 状態の通知のみキャンセル可能。完了・期限切れ済みはエラー。</p>
     */
    @PatchMapping("/{notificationId}/cancel")
    @Operation(summary = "確認通知キャンセル（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "キャンセル成功")
    public ResponseEntity<Void> cancel(
            @PathVariable Long orgId,
            @PathVariable Long notificationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        notificationService.cancel(notificationId, currentUserId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 未確認受信者にリマインドを再送する。
     *
     * <p>ACTIVE 状態かつ未確認受信者が存在する場合にのみ再送を実行する。</p>
     */
    @PostMapping("/{notificationId}/resend-reminder")
    @Operation(summary = "リマインド再送（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "再送成功")
    public ResponseEntity<Void> resendReminder(
            @PathVariable Long orgId,
            @PathVariable Long notificationId) {
        notificationService.resendReminder(notificationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 確認通知の受信者一覧を取得する。
     *
     * <p><b>F04.9 Phase D 認可分岐</b>:
     * <ul>
     *   <li>ADMIN+ → 全件返す</li>
     *   <li>非 ADMIN かつ {@code unconfirmedVisibility = ALL_MEMBERS} かつ受信者本人 → 未確認者のみ返す（マスク）</li>
     *   <li>それ以外 → 403</li>
     * </ul>
     * </p>
     */
    @GetMapping("/{notificationId}/recipients")
    @Operation(summary = "受信者一覧取得（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ConfirmableNotificationRecipientResponse>>> getRecipients(
            @PathVariable Long orgId,
            @PathVariable Long notificationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        if (accessControlService.isAdminOrAbove(currentUserId, orgId, ScopeType.ORGANIZATION.name())) {
            List<ConfirmableNotificationRecipientEntity> recipients =
                    notificationService.getRecipients(notificationId);
            List<ConfirmableNotificationRecipientResponse> responses =
                    mapper.toRecipientResponseList(recipients);
            return ResponseEntity.ok(ApiResponse.of(responses));
        }

        List<ConfirmableNotificationRecipientEntity> unconfirmed =
                notificationService.getRecipientsForMember(notificationId, currentUserId);
        List<ConfirmableNotificationRecipientResponse> responses =
                mapper.toRecipientPublicResponseList(unconfirmed);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * ログインユーザーが確認通知を確認済みにする（MEMBER以上）。
     *
     * <p>ACTIVE 状態の通知に対して自分自身の確認のみ可能。</p>
     */
    @PostMapping("/{notificationId}/confirm")
    @Operation(summary = "確認通知を確認済みにする（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "確認成功")
    public ResponseEntity<Void> confirm(
            @PathVariable Long orgId,
            @PathVariable Long notificationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        notificationService.confirm(notificationId, currentUserId);
        return ResponseEntity.noContent().build();
    }
}
