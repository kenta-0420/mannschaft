package com.mannschaft.app.line.controller;

import com.mannschaft.app.line.service.LineWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * LINE Webhook受信コントローラー（認証不要）。
 */
@RestController
@RequestMapping("/api/v1/line/webhook")
@RequiredArgsConstructor
public class LineWebhookController {

    private final LineWebhookService lineWebhookService;

    /**
     * LINE Webhookイベントを受信する。
     */
    @PostMapping("/{webhookSecret}")
    @ResponseStatus(HttpStatus.OK)
    public void receiveWebhook(
            @PathVariable String webhookSecret,
            @RequestBody String requestBody) {
        lineWebhookService.handleWebhook(webhookSecret, requestBody);
    }
}
