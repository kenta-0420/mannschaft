package com.mannschaft.app.directmail.controller;

import com.mannschaft.app.directmail.dto.SesNotificationRequest;
import com.mannschaft.app.directmail.service.SesWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SES Webhookコントローラー。バウンス・苦情・開封通知を受け付ける（認証不要）。
 */
@RestController
@RequestMapping("/api/v1/webhooks/ses")
@Tag(name = "SES Webhook", description = "F09.6 SESバウンス/苦情通知")
@RequiredArgsConstructor
public class SesWebhookController {

    private final SesWebhookService sesWebhookService;

    /**
     * SES通知を処理する。
     */
    @PostMapping
    @Operation(summary = "SES通知受信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "処理完了")
    public ResponseEntity<Void> handleNotification(@RequestBody SesNotificationRequest request) {
        sesWebhookService.handleNotification(request);
        return ResponseEntity.ok().build();
    }
}
