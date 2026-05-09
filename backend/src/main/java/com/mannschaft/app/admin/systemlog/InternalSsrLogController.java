package com.mannschaft.app.admin.systemlog;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 */
@Slf4j
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

        // トークン検証
        if (!internalToken.equals(token)) {
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
}
