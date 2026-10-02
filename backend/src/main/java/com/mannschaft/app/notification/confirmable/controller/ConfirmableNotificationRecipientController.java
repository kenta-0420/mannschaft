package com.mannschaft.app.notification.confirmable.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationRecipientResponse;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.mapper.ConfirmableNotificationMapper;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
     *
     * <p><b>認可（{@link AuthorizedInService} 付与の根拠・認可根治戦役 Wave7 監査済）</b>:
     * 本 EP はパス変数もリクエストボディも持たず、対象ユーザーはリクエストから受け取らない。
     * サーバ側で確定した {@link SecurityUtils#getCurrentUserId()} を
     * {@code ConfirmableNotificationQueryService#listPending(Long)} 経由で
     * {@code ConfirmableNotificationRecipientRepository#findByUserIdAndIsConfirmedFalseAndExcludedAtIsNull}
     * の検索条件に固定して渡すため、他人の受信者行は構造上取得できない自己スコープ EP である。
     * 未ログインは {@code SecurityUtils.getCurrentUserId()} が 401 を投げる。
     * データ依存でない構造的な自己スコープ認可のため白名簿クラス呼び出しを持たず、
     * 本マーカーで監査済であることを明示する。</p>
     */
    @GetMapping("/pending")
    @Operation(summary = "保留中確認通知一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<List<ConfirmableNotificationRecipientResponse>>> listPending() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        List<ConfirmableNotificationRecipientEntity> recipients =
                notificationService.listPending(currentUserId);
        List<ConfirmableNotificationRecipientResponse> responses =
                mapper.toRecipientResponseList(recipients);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * ログインユーザーが自分宛ての確認通知を確認済みにする。
     *
     * <p>スコープ別 URL を持たない個人・プラットフォーム通知でも利用できる自己スコープ EP。
     * 認可は {@code ConfirmableNotificationConfirmService} が通知の受信者行を現在ユーザー ID で
     * 照合するため、他人宛ての通知を確認することはできない。</p>
     */
    @PostMapping("/{notificationId}/confirm")
    @Operation(summary = "自分宛ての確認通知を確認済みにする")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "確認成功")
    @AuthorizedInService
    public ResponseEntity<Void> confirm(@PathVariable Long notificationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        notificationService.confirm(notificationId, currentUserId);
        return ResponseEntity.noContent().build();
    }
}
