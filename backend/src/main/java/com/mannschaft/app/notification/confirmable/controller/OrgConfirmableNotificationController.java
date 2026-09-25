package com.mannschaft.app.notification.confirmable.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationCreateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDetailResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationRecipientResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationSendAcceptedResponse;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
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
     * F04.9 §2 が定める確認通知の送信権限（CMP-260909-1141）。
     *
     * <p>書き込み系（送信・キャンセル・リマインド再送・設定更新・テンプレート CRUD）は
     * 「ADMIN、または本権限を持つ DEPUTY_ADMIN」で認可する。カタログ登録と DEPUTY_ADMIN への
     * 既定付与（{@code is_default=1}）は
     * {@code V216.20260918083734__add_send_notification_permission.sql} が行う。
     * 閲覧系は従来どおり {@code checkMembership} のままである。</p>
     */
    private static final String SEND_NOTIFICATION = "SEND_NOTIFICATION";

    /**
     * 確認通知を送信する（CMP-260920-1040 軍議第8版確定稿 §3.3・AC-19）。
     *
     * <p>宛先は {@code targets} / {@code recipientGroupId} / 省略（既定＝配下すべて）で指定する
     * （公開 API の {@code recipientUserIds} は廃止・AC-11）。本体・targets・fanoutジョブを同一
     * トランザクションで作成し、受信者行は作らずに <b>202 Accepted</b> を返す（AC-19）。
     * 実配信は裏ワーカー（fan-out）が担う。</p>
     */
    @PostMapping
    @Operation(summary = "確認通知送信（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "受付成功（非同期配信）")
    public ResponseEntity<ApiResponse<ConfirmableNotificationSendAcceptedResponse>> send(
            @PathVariable Long orgId,
            @Valid @RequestBody ConfirmableNotificationCreateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // 認可根治 Wave3-B12notif → CMP-260909-1141: 通知送信は管理操作（受信者へ強制配信＋ORGはクレジット消費を伴う）。
        // 設計書 F04.9 §2 のとおり「ADMIN、または SEND_NOTIFICATION を持つ DEPUTY_ADMIN」で判定する。
        accessControlService.checkAdminOrHasPermissionInScope(
                currentUserId, orgId, ScopeType.ORGANIZATION.name(), SEND_NOTIFICATION);
        ConfirmableNotificationSendAcceptedResponse response = notificationService.sendAsync(
                ScopeType.ORGANIZATION, orgId, request, currentUserId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(response));
    }

    /**
     * 組織の確認通知一覧を取得する（作成日時降順）。
     */
    @GetMapping
    @Operation(summary = "確認通知一覧取得（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ConfirmableNotificationResponse>>> list(
            @PathVariable Long orgId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // 認可根治 Wave3-B12notif: 一覧は閲覧系のため checkMembership（非メンバーの BOLA 一覧取得を根治）。
        accessControlService.checkMembership(currentUserId, orgId, ScopeType.ORGANIZATION.name());
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
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ConfirmableNotificationEntity entity = notificationService.getDetail(notificationId);

        // スコープ整合チェック（BOLA対策: notificationId が path の orgId 配下かを突合。不一致は404秘匿）
        if (!ScopeType.ORGANIZATION.equals(entity.getScopeType()) || !orgId.equals(entity.getScopeId())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.SCOPE_MISMATCH);
        }
        // 認可根治 Wave3-B12notif: 閲覧系は checkMembership（非メンバーの詳細窃視を根治）。
        accessControlService.checkMembership(currentUserId, orgId, ScopeType.ORGANIZATION.name());

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
        ConfirmableNotificationEntity entity = notificationService.getDetail(notificationId);

        // スコープ整合チェック（BOLA対策: notificationId が path の orgId 配下かを突合。不一致は404秘匿）
        if (!ScopeType.ORGANIZATION.equals(entity.getScopeType()) || !orgId.equals(entity.getScopeId())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.SCOPE_MISMATCH);
        }
        // 認可根治 Wave3-B12notif → CMP-260909-1141: キャンセルは管理操作のため SEND_NOTIFICATION で判定。
        accessControlService.checkAdminOrHasPermissionInScope(
                currentUserId, orgId, ScopeType.ORGANIZATION.name(), SEND_NOTIFICATION);

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
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ConfirmableNotificationEntity entity = notificationService.getDetail(notificationId);

        // スコープ整合チェック（BOLA対策: notificationId が path の orgId 配下かを突合。不一致は404秘匿）
        if (!ScopeType.ORGANIZATION.equals(entity.getScopeType()) || !orgId.equals(entity.getScopeId())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.SCOPE_MISMATCH);
        }
        // 認可根治 Wave3-B12notif → CMP-260909-1141: リマインド再送は管理操作のため SEND_NOTIFICATION で判定。
        accessControlService.checkAdminOrHasPermissionInScope(
                currentUserId, orgId, ScopeType.ORGANIZATION.name(), SEND_NOTIFICATION);

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
        ConfirmableNotificationEntity notification = notificationService.getDetail(notificationId);

        // スコープ整合チェック（BOLA対策: notificationId が path の orgId 配下かを突合。不一致は404秘匿）
        // ADMIN であっても他 scope の notificationId で受信者一覧を覗ける副次 BOLA を根治（Wave3-B12notif）。
        if (!ScopeType.ORGANIZATION.equals(notification.getScopeType()) || !orgId.equals(notification.getScopeId())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.SCOPE_MISMATCH);
        }

        if (accessControlService.isAdminOrAbove(currentUserId, orgId, ScopeType.ORGANIZATION.name())) {
            // CMP-260920-1040是正: getRecipients は退会者でも500化しないネイティブ投影から
            // 直接DTOを返すため、mapper を経由しない（家老の検出・殿の確認）。
            List<ConfirmableNotificationRecipientResponse> responses =
                    notificationService.getRecipients(notificationId);
            return ResponseEntity.ok(ApiResponse.of(responses));
        }

        // CMP-260920-1040是正: getRecipientsForMember はネイティブ投影から直接マスク済みDTOを返すため、
        // mapper を経由しない（家老の検出・殿の確認。退会者を含んでも500化しない）。
        List<ConfirmableNotificationRecipientResponse> responses =
                notificationService.getRecipientsForMember(notificationId, currentUserId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * CMP-260920-1040: 受信者一覧をページングして取得する（軍議第8版確定稿 §9.5・AC-30・AC-59・AC-60）。
     *
     * <p>件数・viewerRole はサービス層が明示的に返す（FE 側での推測は撤去する・§9.5）。
     * 既存の全件版 {@link #getRecipients} とは別 URL とし、FE 側の移行を段階的に行えるようにする。</p>
     */
    @GetMapping("/{notificationId}/recipients/page")
    @Operation(summary = "受信者一覧取得（ページング・組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationRecipientPageResponse>>
            getRecipientsPage(
                    @PathVariable Long orgId,
                    @PathVariable Long notificationId,
                    @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
                    @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int size,
                    @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean unconfirmedOnly) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ConfirmableNotificationEntity notification = notificationService.getDetail(notificationId);
        if (!ScopeType.ORGANIZATION.equals(notification.getScopeType()) || !orgId.equals(notification.getScopeId())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.SCOPE_MISMATCH);
        }
        // 閲覧自体は checkMembership 相当（ADMIN/CREATOR/MEMBERの判定はサービス層で行う。§9.5）。
        accessControlService.checkMembership(currentUserId, orgId, ScopeType.ORGANIZATION.name());
        return ResponseEntity.ok(ApiResponse.of(
                notificationService.getRecipientsPage(notificationId, currentUserId, page, size, unconfirmedOnly)));
    }

    /**
     * ログインユーザーが確認通知を確認済みにする（MEMBER以上）。
     *
     * <p>ACTIVE 状態の通知に対して自分自身の確認のみ可能。</p>
     *
     * <p><b>認可（{@link AuthorizedInService} 付与の根拠・認可根治戦役 Wave7 監査済）</b>:
     * パス変数 {@code orgId} は自身ではスコープ判定に用いない（本 EP の実処理は
     * notificationId のみで完結する）。認可の実体は
     * {@code ConfirmableNotificationConfirmService#confirm(Long, Long)} が受信者一覧
     * （{@code ConfirmableNotificationRecipientRepository#findByConfirmableNotificationId}）から
     * {@code recipient.getUser().getId().equals(userId)} で<b>呼び出しユーザー自身の受信者行のみ</b>を
     * 特定し、該当しない場合は {@code RECIPIENT_NOT_FOUND} を投げる構造にある。
     * このため他人宛の確認通知を確認済みにすることは構造上できない自己スコープ EP であり、
     * {@code orgId} の実スコープと notificationId の実スコープが仮に食い違っていても、確認できるのは
     * 常に呼び出しユーザー自身の受信者行のみで権限昇格は発生しない。
     * データ依存でない構造的な自己スコープ認可のため白名簿クラス呼び出しを持たず、
     * 本マーカーで監査済であることを明示する。</p>
     */
    @PostMapping("/{notificationId}/confirm")
    @Operation(summary = "確認通知を確認済みにする（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "確認成功")
    @AuthorizedInService
    public ResponseEntity<Void> confirm(
            @PathVariable Long orgId,
            @PathVariable Long notificationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        notificationService.confirm(notificationId, currentUserId);
        return ResponseEntity.noContent().build();
    }
}
