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

/**
 * プッシュ購読コントローラー。Web Push購読の登録・解除APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/push-subscriptions")
@Tag(name = "プッシュ購読", description = "F04.3 プッシュ購読管理")
@RequiredArgsConstructor
public class PushSubscriptionController {

    private final PushSubscriptionService pushSubscriptionService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * プッシュ購読を登録する。
     */
    @PostMapping
    @Operation(summary = "プッシュ購読登録")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "登録成功")
    public ResponseEntity<ApiResponse<Long>> subscribe(
            @Valid @RequestBody PushSubscriptionRequest request) {
        PushSubscriptionEntity entity = pushSubscriptionService.subscribe(getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(entity.getId()));
    }

    /**
     * プッシュ購読を解除する。
     */
    @DeleteMapping
    @Operation(summary = "プッシュ購読解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "解除成功")
    public ResponseEntity<Void> unsubscribe(@RequestParam String endpoint) {
        pushSubscriptionService.unsubscribe(getCurrentUserId(), endpoint);
        return ResponseEntity.noContent().build();
    }
}
