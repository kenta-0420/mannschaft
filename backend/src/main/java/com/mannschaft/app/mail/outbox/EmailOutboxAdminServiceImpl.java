package com.mannschaft.app.mail.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.dto.EmailOutboxDetailResponse;
import com.mannschaft.app.admin.dto.EmailOutboxMetricsResponse;
import com.mannschaft.app.admin.dto.EmailOutboxSummaryResponse;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * F09.18 Phase 18-d: SYSTEM_ADMIN 向け outbox 管理サービス実装。
 *
 * <p>PII（to_address / payload_json）はこのサービス内でのみ復号する。
 * 閲覧・操作のたびに監査ログを非同期記録する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailOutboxAdminServiceImpl implements EmailOutboxAdminService {

    private final EmailOutboxRepository outboxRepository;
    private final EncryptionService encryptionService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @Override
    public Page<EmailOutboxSummaryResponse> listOutbox(
            String status, String sourceDomain,
            LocalDateTime fromDate, LocalDateTime toDate,
            Pageable pageable) {
        return outboxRepository
                .findByFilters(status, sourceDomain, fromDate, toDate, pageable)
                .map(this::toSummary);
    }

    @Override
    public EmailOutboxDetailResponse getOutboxDetail(
            UUID id, Long operatorUserId, String ipAddress, String userAgent) {
        EmailOutboxEntity entity = outboxRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("outbox エントリが存在しません: " + id));

        String decryptedToAddress = decryptToAddress(entity);
        Map<String, String> payloadVars = decryptPayloadVars(entity);

        // 監査ログ: SYSTEM_ADMIN が閲覧した事実を記録（PII 自体はログに含めない）
        auditLogService.record(
                "SYSTEM_ADMIN_EMAIL_OUTBOX_VIEWED",
                operatorUserId, null, null, null,
                ipAddress, userAgent, null,
                "{\"outboxId\":\"" + id + "\"}");

        return new EmailOutboxDetailResponse(
                entity.getId(),
                entity.getStatus(),
                entity.getTemplateKind(),
                entity.getSourceDomain(),
                entity.getSourceEventId(),
                entity.getLocale(),
                decryptedToAddress,
                payloadVars,
                entity.getRetryCount(),
                entity.getSesMessageId(),
                entity.getCreatedAt(),
                entity.getNextAttemptAt(),
                entity.getSentAt(),
                entity.getLastError());
    }

    @Override
    @Transactional
    public void retryDeadLetter(UUID id, Long operatorUserId, String ipAddress, String userAgent) {
        EmailOutboxEntity entity = outboxRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("outbox エントリが存在しません: " + id));

        if (!EmailOutboxStatus.DEAD_LETTER.name().equals(entity.getStatus())) {
            throw new IllegalStateException("状態が DEAD_LETTER ではありません: " + entity.getStatus());
        }

        entity.markPendingForRetry();
        outboxRepository.save(entity);

        auditLogService.record(
                "SYSTEM_ADMIN_EMAIL_OUTBOX_RETRIED",
                operatorUserId, null, null, null,
                ipAddress, userAgent, null,
                "{\"outboxId\":\"" + id + "\"}");

        log.warn("SYSTEM_ADMIN が outbox エントリをリトライキューに戻した: id={}, operator={}",
                id, operatorUserId);
    }

    @Override
    @Transactional
    public void cancelPending(UUID id, Long operatorUserId, String ipAddress, String userAgent) {
        EmailOutboxEntity entity = outboxRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("outbox エントリが存在しません: " + id));

        if (!EmailOutboxStatus.PENDING.name().equals(entity.getStatus())) {
            throw new IllegalStateException("状態が PENDING ではありません: " + entity.getStatus());
        }

        entity.markCancelled();
        outboxRepository.save(entity);

        auditLogService.record(
                "SYSTEM_ADMIN_EMAIL_OUTBOX_CANCELLED",
                operatorUserId, null, null, null,
                ipAddress, userAgent, null,
                "{\"outboxId\":\"" + id + "\"}");

        log.warn("SYSTEM_ADMIN が outbox エントリをキャンセルした: id={}, operator={}",
                id, operatorUserId);
    }

    @Override
    public EmailOutboxMetricsResponse getMetrics() {
        long pending = outboxRepository.countByStatus(EmailOutboxStatus.PENDING.name());
        long sending = outboxRepository.countByStatus(EmailOutboxStatus.SENDING.name());
        long deadLetter = outboxRepository.countByStatus(EmailOutboxStatus.DEAD_LETTER.name());
        long failed = outboxRepository.countByStatus(EmailOutboxStatus.FAILED.name());
        long cancelled = outboxRepository.countByStatus(EmailOutboxStatus.CANCELLED.name());

        LocalDateTime since24h = LocalDateTime.now().minusHours(24);
        long sent24h = outboxRepository.countByStatusSince(EmailOutboxStatus.SENT.name(), since24h);
        long deadLetter24h = outboxRepository.countByStatusSince(EmailOutboxStatus.DEAD_LETTER.name(), since24h);
        long total24h = sent24h + deadLetter24h;
        // 送信実績ゼロなら成功率は null（意味のない 0.0 を返さない設計）
        Double successRate24h = total24h > 0 ? (double) sent24h / total24h : null;

        Long oldestPendingAgeSeconds = outboxRepository
                .findFirstByStatusOrderByCreatedAtAsc(EmailOutboxStatus.PENDING.name())
                .map(e -> Duration.between(e.getCreatedAt(), LocalDateTime.now()).getSeconds())
                .orElse(null);

        return new EmailOutboxMetricsResponse(
                pending, sending, deadLetter, failed, cancelled,
                successRate24h, oldestPendingAgeSeconds);
    }

    // -----------------------------------------------------------------------
    // 内部ヘルパー
    // -----------------------------------------------------------------------

    private EmailOutboxSummaryResponse toSummary(EmailOutboxEntity e) {
        return new EmailOutboxSummaryResponse(
                e.getId(),
                e.getStatus(),
                e.getTemplateKind(),
                e.getSourceDomain(),
                e.getSourceEventId(),
                e.getLocale(),
                e.getRetryCount(),
                e.getCreatedAt(),
                e.getNextAttemptAt(),
                e.getSentAt(),
                e.getLastError());
    }

    /** to_address を復号する。null（GDPR パージ済）の場合は null を返す。 */
    private String decryptToAddress(EmailOutboxEntity entity) {
        if (entity.getToAddress() == null) {
            return null;
        }
        byte[] plain = encryptionService.decryptBytes(entity.getToAddress());
        return new String(plain, StandardCharsets.UTF_8);
    }

    /**
     * payload_json を復号して Map に変換する。
     * null（30 日パージ済）の場合は null を返す。
     */
    private Map<String, String> decryptPayloadVars(EmailOutboxEntity entity) {
        if (entity.getPayloadJson() == null) {
            return null;
        }
        try {
            byte[] plain = encryptionService.decryptBytes(entity.getPayloadJson());
            String json = new String(plain, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("payload_json の復号に失敗した: {}", e.getMessage());
            return null;
        }
    }
}
