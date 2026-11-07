package com.mannschaft.app.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.event.MfaVerificationFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 2FA ログイン検証の失敗を監査 {@code MFA_FAILED} として<b>失敗 TX から独立して</b>記録する（CMP-261012-0910 AC-49）。
 *
 * <h2>なぜ別 Bean・REQUIRES_NEW なのか</h2>
 * <p>MFA 検証の失敗は {@code BusinessException} の送出で応答するため、業務 TX（{@code Auth2faService#validateMfaLogin}）
 * はロールバックされる。既存の監査配線（{@code AuditLogEventListener} の {@code @TransactionalEventListener(AFTER_COMMIT)}）
 * に載せるとコミットが起きずリスナーが発火せず、総当たりの痕跡が監査から消える。そこで失敗時に本 Bean を
 * <b>別 Bean 経由（プロキシを通す）・{@link Propagation#REQUIRES_NEW}</b> で同期に呼び、監査行を独立した TX で
 * コミットしてから業務 TX をロールバックさせる。</p>
 *
 * <p>同一クラス内の自己呼び出しではプロキシを通らず REQUIRES_NEW が効かないため、{@code Auth2faService} から分けている。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MfaFailureAuditRecorder {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuditLogService auditLogService;

    /**
     * 失敗を監査に記録する（独立 TX でコミット）。
     *
     * @param event 失敗イベント
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(MfaVerificationFailedEvent event) {
        auditLogService.recordSync(
                AuditEventType.MFA_FAILED.name(),
                event.getUserId(),
                null,
                null,
                null,
                event.getIpAddress(),
                event.getUserAgent(),
                null,
                metadata(event));
    }

    private static String metadata(MfaVerificationFailedEvent event) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", event.getMethod() == null ? null : event.getMethod().name());
        m.put("attempt", event.getAttemptCount());
        try {
            return JSON.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            log.error("MFA_FAILED 監査メタデータの JSON 化に失敗", e);
            return null;
        }
    }
}
