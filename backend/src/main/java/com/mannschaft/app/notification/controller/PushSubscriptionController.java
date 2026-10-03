package com.mannschaft.app.notification.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.notification.dto.PushSubscriptionRequest;
import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import com.mannschaft.app.notification.service.PushSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;

/**
 * プッシュ購読コントローラー。Web Push購読の登録・解除APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/push-subscriptions")
@Tag(name = "プッシュ購読", description = "F04.3 プッシュ購読管理")
@RequiredArgsConstructor
public class PushSubscriptionController {

    private final PushSubscriptionService pushSubscriptionService;


    /**
     * プッシュ購読を登録する。
     */
    @SelfScopedEndpoint("pushSubscriptionService.subscribe が作成する購読の userId は常に"
            + "SecurityUtils.getCurrentUserId() で固定され、リクエストで所有者を指定する余地が無い"
            + "（PushSubscriptionService.java:39）")
    @PostMapping
    @Operation(summary = "プッシュ購読登録")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "登録成功")
    public ResponseEntity<ApiResponse<Long>> subscribe(
            @Valid @RequestBody PushSubscriptionRequest request) {
        PushSubscriptionEntity entity = pushSubscriptionService.subscribe(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(entity.getId()));
    }

    /**
     * プッシュ購読を解除する。
     */
    // 認可根治戦役 Wave4 ロットD: pushSubscriptionService.unsubscribe は endpoint で検索した
    // エンティティの userId を SecurityUtils.getCurrentUserId() と直接比較し、不一致なら
    // SUBSCRIPTION_NOT_FOUND として拒否してから削除する（PushSubscriptionService.java:63-65）。
    // 到達可能性そのものは存在する（判定を外せば他人へ届く）比較実装のため SelfScopedEndpoint の
    // 対象外とし、AuthorizedInService で認可済みの所在を明示する。
    @AuthorizedInService
    @DeleteMapping
    @Operation(summary = "プッシュ購読解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "解除成功")
    public ResponseEntity<Void> unsubscribe(@RequestParam String endpoint) {
        pushSubscriptionService.unsubscribe(SecurityUtils.getCurrentUserId(), endpoint);
        return ResponseEntity.noContent().build();
    }
}
