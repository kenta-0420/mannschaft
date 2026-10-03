package com.mannschaft.app.line.controller;

import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
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
 *
 * <p><b>認可根拠（{@link AuthorizedInService} クラス付与・全 1 EP が該当）</b>:
 * {@link #receiveWebhook} は {@code LineWebhookService.java:71} の
 * {@code verifySignature(config, signature, requestBody)} を通り、
 * {@code LineWebhookService.java:122} の {@code MessageDigest.isEqual(...)} で
 * channel secret による HMAC-SHA256 署名（{@code X-Line-Signature}）を<b>定数時間比較</b>する。
 * 検証失敗時はイベント処理を行わない（LINE の無限リトライ回避のため HTTP は常に 200 を返すが、
 * 副作用は発生しない）。認可根治戦役 Wave5 監査済。</p>
 */
@AuthorizedInService
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
    @AlwaysReachable(category = AlwaysReachableCategory.PLATFORM_INFRA,
            reason = "LINEからの外部通知をGate状態にかかわらず受領するため")
    @PostMapping("/{webhookSecret}")
    @ResponseStatus(HttpStatus.OK)
    public void receiveWebhook(
            @PathVariable String webhookSecret,
            @RequestHeader(value = "X-Line-Signature", required = false) String signature,
            @RequestBody String requestBody) {
        lineWebhookService.handleWebhook(webhookSecret, signature, requestBody);
    }
}
