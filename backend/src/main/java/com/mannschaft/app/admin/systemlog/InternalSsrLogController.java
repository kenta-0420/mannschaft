package com.mannschaft.app.admin.systemlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.security.AuthorizedInService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nuxt フロントエンドから SSR エラーを受け付ける内部エンドポイント。
 * 認証は {@code X-Internal-Token} ヘッダーによるトークン検証で行う。
 * SecurityConfig で {@code permitAll()} に設定しているが、このコントローラーが自前でトークン検証する。
 *
 * <p><b>認可根拠（{@link AuthorizedInService} クラス付与・全 1 EP が該当）</b>:
 * {@link #receiveSsrLog} は本ファイルの {@code isValidToken} で
 * {@code X-Internal-Token} ヘッダを設定値（{@code mannschaft.system-log.internal-token}）と
 * {@code MessageDigest.isEqual} により<b>定数時間比較</b>し、不一致なら 403 で中断する。
 * 認可根治戦役 Wave5 監査済。</p>
 */
@Slf4j
@AuthorizedInService
@RestController
@RequestMapping("/api/internal")
@Tag(name = "内部 - SSR ログ", description = "F10.6 Nuxt SSR エラー受信 API（内部トークン認証）")
public class InternalSsrLogController {

    private final SystemLogService systemLogService;
    private final SystemLogPiiMasker piiMasker;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public InternalSsrLogController(
            SystemLogService systemLogService,
            SystemLogPiiMasker piiMasker,
            ObjectMapper objectMapper,
            @Value("${mannschaft.system-log.internal-token:dev-internal-token}") String internalToken) {
        this.systemLogService = systemLogService;
        this.piiMasker = piiMasker;
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    /**
     * SSR エラーを受け付けてバッファに追加する。
     * {@code X-Internal-Token} ヘッダーが一致しない場合は 403 を返す。
     *
     * @param token          内部認証トークン
     * @param ssrErrorRequest SSR エラーリクエスト
     * @return 202 Accepted または 403 Forbidden
     */
    @PostMapping("/ssr-logs")
    @Operation(summary = "SSR エラー受信", description = "Nuxt フロントエンドから SSR エラーを受け取り R2 バッファに追加する")
    public ResponseEntity<Void> receiveSsrLog(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody SsrErrorRequest ssrErrorRequest) {

        // トークン検証（定数時間比較・タイミング攻撃対策）
        if (!isValidToken(token)) {
            log.warn("SSR ログ受信: 不正なトークン（{} 文字）", token != null ? token.length() : 0);
            return ResponseEntity.status(403).build();
        }

        // message と stack に PII マスキングを適用したリクエストを再構築
        SsrErrorRequest maskedRequest = new SsrErrorRequest(
                ssrErrorRequest.level(),
                piiMasker.mask(ssrErrorRequest.message()),
                piiMasker.mask(ssrErrorRequest.stack()),
                ssrErrorRequest.path(),
                ssrErrorRequest.timestamp(),
                ssrErrorRequest.userAgent()
        );

        // JSONL 形式にシリアライズしてバッファに追加
        try {
            String jsonLine = objectMapper.writeValueAsString(maskedRequest);
            systemLogService.appendSsrError(jsonLine);
        } catch (Exception e) {
            log.error("SSR エラーの JSONL シリアライズ失敗", e);
            return ResponseEntity.internalServerError().build();
        }

        return ResponseEntity.accepted().build();
    }

    /**
     * 内部トークンを<b>定数時間</b>で照合する（タイミング攻撃対策）。
     *
     * <p>{@code String.equals} は最初の不一致バイトで短絡するため、比較に要する時間から
     * 正解トークンの先頭一致長が漏れ、総当りコストが指数から線形に落ちる。他の webhook 経路
     * （{@code LineWebhookService} / {@code GoogleCalendarWebhookService}）と同じく
     * {@link MessageDigest#isEqual} によるバイト比較へ揃える。</p>
     *
     * <p>{@code MessageDigest.isEqual} は長さが異なる場合は早期に false を返す（長さは秘匿しない）が、
     * 同一長のバイト列同士は全バイトを走査するため内容の先頭一致長は漏れない。
     * {@code token} が null の場合は比較せず false（{@code getBytes} の NPE 回避）。</p>
     *
     * @param token リクエストの {@code X-Internal-Token} ヘッダ値（未指定なら null）
     * @return トークンが一致すれば true
     */
    private boolean isValidToken(String token) {
        if (token == null || internalToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
