package com.mannschaft.app.notification.confirmable.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.confirmable.dto.PublicConfirmationResponse;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F04.9 トークン経由の確認エンドポイント（認証不要）。
 *
 * <p>メール・LINE などの外部チャネルから送信された確認URLのリンク先として機能する。
 * {@code /api/v1/public/**} は SecurityConfig で {@code permitAll()} 設定済み。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "公開確認API", description = "F04.9 トークン経由の確認通知確認（認証不要）")
@RequiredArgsConstructor
public class PublicConfirmationController {

    private final ConfirmableNotificationService notificationService;

    /**
     * 確認トークンを使って確認通知を確認済みにする（認証不要）。
     *
     * <p>成功時は 200 + PublicConfirmationResponse(success=true)。
     * トークンが無効・期限切れ・キャンセル済みの場合は 400 エラーを返す。</p>
     *
     * @param token 確認トークン（UUID文字列、36文字）
     */
    @PostMapping("/confirm/{token}")
    @Operation(summary = "トークン経由で確認通知を確認する（認証不要）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "確認成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "トークンが無効または確認済み")
    public ResponseEntity<ApiResponse<PublicConfirmationResponse>> confirmByToken(
            @PathVariable String token) {
        // BusinessException は GlobalExceptionHandler が 400 に変換する
        notificationService.confirmByToken(token);

        PublicConfirmationResponse response = PublicConfirmationResponse.builder()
                .success(true)
                .message("確認が完了しました")
                .build();
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
