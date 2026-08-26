package com.mannschaft.app.notification.confirmable.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
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
import com.mannschaft.app.common.BusinessException;
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
 * F04.9 チーム確認通知コントローラー。
 *
 * <p>確認通知の送信・一覧・詳細・キャンセル・リマインド再送・受信者一覧・確認APIを提供する。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/confirmable-notifications")
@Tag(name = "チーム確認通知", description = "F04.9 チーム確認通知 CRUD・ステータス管理")
@RequiredArgsConstructor
public class TeamConfirmableNotificationController {

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
    @Operation(summary = "確認通知送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "送信成功")
    public ResponseEntity<ApiResponse<ConfirmableNotificationResponse>> send(
            @PathVariable Long teamId,
            @Valid @RequestBody ConfirmableNotificationCreateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // 認可根治 Wave3-B12notif: 通知送信は管理操作（受信者へ強制配信）。
        accessControlService.checkAdminOrAbove(currentUserId, teamId, ScopeType.TEAM.name());
        ConfirmableNotificationEntity entity = notificationService.send(
                ScopeType.TEAM,
                teamId,
                request.getTitle(),
                request.getBody(),
                request.getPriority(),
                request.getDeadlineAtAsJst(),
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
     * チームの確認通知一覧を取得する（作成日時降順）。
     */
    @GetMapping
    @Operation(summary = "確認通知一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ConfirmableNotificationResponse>>> list(
            @PathVariable Long teamId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // 認可根治 Wave3-B12notif: 一覧は閲覧系のため checkMembership（非メンバーの BOLA 一覧取得を根治）。
        accessControlService.checkMembership(currentUserId, teamId, ScopeType.TEAM.name());
        List<ConfirmableNotificationEntity> entities =
                notificationService.listByScope(ScopeType.TEAM, teamId);
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
     * <p>スコープ整合チェック：通知のスコープがリクエストの teamId と一致することを確認する。</p>
     */
    @GetMapping("/{notificationId}")
    @Operation(summary = "確認通知詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ConfirmableNotificationDetailResponse>> getDetail(
            @PathVariable Long teamId,
            @PathVariable Long notificationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ConfirmableNotificationEntity entity = notificationService.getDetail(notificationId);

        // スコープ整合チェック（BOLA対策: notificationId が path の teamId 配下かを突合。不一致は404秘匿）
        if (!ScopeType.TEAM.equals(entity.getScopeType()) || !teamId.equals(entity.getScopeId())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.SCOPE_MISMATCH);
        }
        // 認可根治 Wave3-B12notif: 閲覧系は checkMembership（非メンバーの詳細窃視を根治）。
        accessControlService.checkMembership(currentUserId, teamId, ScopeType.TEAM.name());

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
    @Operation(summary = "確認通知キャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "キャンセル成功")
    public ResponseEntity<Void> cancel(
            @PathVariable Long teamId,
            @PathVariable Long notificationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ConfirmableNotificationEntity entity = notificationService.getDetail(notificationId);

        // スコープ整合チェック（BOLA対策: notificationId が path の teamId 配下かを突合。不一致は404秘匿）
        if (!ScopeType.TEAM.equals(entity.getScopeType()) || !teamId.equals(entity.getScopeId())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.SCOPE_MISMATCH);
        }
        // 認可根治 Wave3-B12notif: キャンセルは管理操作のため checkAdminOrAbove。
        accessControlService.checkAdminOrAbove(currentUserId, teamId, ScopeType.TEAM.name());

        notificationService.cancel(notificationId, currentUserId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 未確認受信者にリマインドを再送する。
     *
     * <p>ACTIVE 状態かつ未確認受信者が存在する場合にのみ再送を実行する。</p>
     */
    @PostMapping("/{notificationId}/resend-reminder")
    @Operation(summary = "リマインド再送")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "再送成功")
    public ResponseEntity<Void> resendReminder(
            @PathVariable Long teamId,
            @PathVariable Long notificationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ConfirmableNotificationEntity entity = notificationService.getDetail(notificationId);

        // スコープ整合チェック（BOLA対策: notificationId が path の teamId 配下かを突合。不一致は404秘匿）
        if (!ScopeType.TEAM.equals(entity.getScopeType()) || !teamId.equals(entity.getScopeId())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.SCOPE_MISMATCH);
        }
        // 認可根治 Wave3-B12notif: リマインド再送は管理操作のため checkAdminOrAbove。
        accessControlService.checkAdminOrAbove(currentUserId, teamId, ScopeType.TEAM.name());

        notificationService.resendReminder(notificationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 確認通知の受信者一覧を取得する。
     *
     * <p><b>F04.9 Phase D 認可分岐</b>:
     * <ul>
     *   <li>ADMIN+ → 全件返す（HIDDEN を含むすべての公開範囲で閲覧可）</li>
     *   <li>非 ADMIN かつ {@code unconfirmedVisibility = HIDDEN} → 403</li>
     *   <li>非 ADMIN かつ {@code unconfirmedVisibility = CREATOR_AND_ADMIN} → 403（既存挙動）</li>
     *   <li>非 ADMIN かつ {@code unconfirmedVisibility = ALL_MEMBERS} かつ
     *       呼び出しユーザーが当通知の受信者である場合 → 未確認者のみ返す
     *       （confirmedAt / confirmedVia / excludedAt は NULL マスク）</li>
     *   <li>それ以外 → 403</li>
     * </ul>
     * </p>
     */
    @GetMapping("/{notificationId}/recipients")
    @Operation(summary = "受信者一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ConfirmableNotificationRecipientResponse>>> getRecipients(
            @PathVariable Long teamId,
            @PathVariable Long notificationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ConfirmableNotificationEntity notification = notificationService.getDetail(notificationId);

        // スコープ整合チェック（BOLA対策: notificationId が path の teamId 配下かを突合。不一致は404秘匿）
        // ADMIN であっても他 scope の notificationId で受信者一覧を覗ける副次 BOLA を根治（Wave3-B12notif）。
        if (!ScopeType.TEAM.equals(notification.getScopeType()) || !teamId.equals(notification.getScopeId())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.SCOPE_MISMATCH);
        }

        // ADMIN+ なら全件返す（既存挙動）
        if (accessControlService.isAdminOrAbove(currentUserId, teamId, ScopeType.TEAM.name())) {
            List<ConfirmableNotificationRecipientEntity> recipients =
                    notificationService.getRecipients(notificationId);
            List<ConfirmableNotificationRecipientResponse> responses =
                    mapper.toRecipientResponseList(recipients);
            return ResponseEntity.ok(ApiResponse.of(responses));
        }

        // 非 ADMIN は ALL_MEMBERS かつ受信者本人のみ閲覧可（Service 層で認可判定 + マスク前データ取得）
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
     *
     * <p><b>認可（{@link AuthorizedInService} 付与の根拠・認可根治戦役 Wave7 監査済）</b>:
     * パス変数 {@code teamId} は自身ではスコープ判定に用いない（本 EP の実処理は
     * notificationId のみで完結する）。認可の実体は
     * {@code ConfirmableNotificationConfirmService#confirm(Long, Long)} が受信者一覧
     * （{@code ConfirmableNotificationRecipientRepository#findByConfirmableNotificationId}）から
     * {@code recipient.getUser().getId().equals(userId)} で<b>呼び出しユーザー自身の受信者行のみ</b>を
     * 特定し、該当しない場合は {@code RECIPIENT_NOT_FOUND} を投げる構造にある。
     * このため他人宛の確認通知を確認済みにすることは構造上できない自己スコープ EP であり、
     * {@code teamId} の実スコープと notificationId の実スコープが仮に食い違っていても、確認できるのは
     * 常に呼び出しユーザー自身の受信者行のみで権限昇格は発生しない。
     * データ依存でない構造的な自己スコープ認可のため白名簿クラス呼び出しを持たず、
     * 本マーカーで監査済であることを明示する。</p>
     */
    @PostMapping("/{notificationId}/confirm")
    @Operation(summary = "確認通知を確認済みにする")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "確認成功")
    @AuthorizedInService
    public ResponseEntity<Void> confirm(
            @PathVariable Long teamId,
            @PathVariable Long notificationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        notificationService.confirm(notificationId, currentUserId);
        return ResponseEntity.noContent().build();
    }
}
