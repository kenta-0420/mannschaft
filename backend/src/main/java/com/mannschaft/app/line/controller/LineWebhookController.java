package com.mannschaft.app.line.controller;

import com.mannschaft.app.line.service.LineWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
     *
     * <p>{@code X-Line-Signature} は channel secret による HMAC-SHA256 署名であり、
     * Service 層で検証する。LINE は 200 以外のレスポンスを無限にリトライするため、
     * 署名不一致・ヘッダ欠落・設定不在のいずれの場合も常に 200 を返却する。</p>
     */
    @PostMapping("/{webhookSecret}")
    @ResponseStatus(HttpStatus.OK)
    public void receiveWebhook(
            @PathVariable String webhookSecret,
            @RequestHeader(value = "X-Line-Signature", required = false) String signature,
            @RequestBody String requestBody) {
        lineWebhookService.handleWebhook(webhookSecret, signature, requestBody);
    }
}
