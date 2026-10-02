package com.mannschaft.app.mail.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.EmailTemplateRenderer;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.UuidV7;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * F09.18 メール配信 outbox Service 実装。
 *
 * <p>設計書 §7.1 (enqueue) / §7.2 (processOne) の振る舞いを実装する。
 * テンプレート種別 → レンダリングメソッドの分岐は本クラス内 switch で行う
 * (Phase 18-a では VERIFICATION / PASSWORD_RESET のみ動作確認、
 * 残 12 種は Phase 18-b/c で追加)。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailOutboxServiceImpl implements EmailOutboxService {

    /** payload_json の上限 (バイト)。VARBINARY(8192) - GCM オーバーヘッド余裕。 */
    static final int PAYLOAD_MAX_BYTES = 8000;

    /**
     * メールアドレス検証用の簡易正規表現。
     * RFC 5322 完全準拠ではないが、SES 送信前の基本的なバリデーションには十分。
     * Jakarta Bean Validation の {@code @Email} と同等の挙動を狙う。
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$"
    );

    private final EmailOutboxRepository repository;
    private final EncryptionService encryption;
    private final EmailTemplateRenderer renderer;
    private final EmailTransport emailTransport;
    private final SesExceptionClassifier classifier;
    private final IdempotencyKeyGenerator keyGen;
    private final MeterRegistry meterRegistry;
    private final EmailOutboxMicrometerMetrics metrics;
    private final ObjectMapper objectMapper;
    /** CMP-260920-1040 §12: 多値 INSERT で outbox へ一括登録するための JDBC 直叩き経路。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * CMP-260920-1040 §12: {@code email_outbox} への多値 INSERT の列並び。{@code id} は UUIDv7
     * （アプリ側で採番。BINARY(16)）。{@code status}/{@code retry_count} は既定値を明示的に束縛する
     * （多値 INSERT は列指定 DEFAULT に頼らず全列を埋める）。{@code created_at}/{@code updated_at}/
     * {@code next_attempt_at} は Java の壁時計ではなく SQL リテラル {@code UTC_TIMESTAMP()} に委ねる
     * （{@code NotificationBulkFanoutService#bulkInsertReturningIds} と同じ理由。本アプリの DB 格納基準は
     * UTC 壁時計であり、Java 側で LocalDateTime.now() を束縛すると JST 分ずれる）。
     */
    private static final String OUTBOX_INSERT_COLUMNS =
            "id, template_kind, locale, to_address, to_address_hash, payload_json, "
            + "source_domain, source_event_id, user_id, organization_id, idempotency_key, "
            + "status, retry_count, next_attempt_at, created_at, updated_at";
    private static final String OUTBOX_ROW_PLACEHOLDERS =
            "(?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP())";
    private static final int OUTBOX_COLS_PER_ROW = 13;

    // -----------------------------------------------------------------------
    // enqueue
    // -----------------------------------------------------------------------

    @Override
    @Transactional
    public UUID enqueue(EmailOutboxRequest request) {
        validateRequest(request);

        // payload を JSON 化 → サイズチェック → 暗号化
        byte[] payloadEncrypted = encryptPayload(request.payloadVars());

        // 宛先暗号化 + HMAC (HMAC は hex 文字列で返るのでバイト化)
        byte[] toAddressEncrypted = encryption.encryptBytes(
                request.toAddress().getBytes(StandardCharsets.UTF_8)
        );
        byte[] toAddressHash = HexFormat.of().parseHex(encryption.hmac(request.toAddress()));

        // 冪等キー
        String idempotencyKey = request.idempotencyKey() != null
                ? request.idempotencyKey()
                : keyGen.generate(request.userId(), request.templateKind(), request.sourceEventId());

        EmailOutboxEntity entity = EmailOutboxEntity.prepareForEnqueue(
                EmailOutboxEntity.builder()
                        .templateKind(request.templateKind())
                        .locale(request.locale())
                        .toAddress(toAddressEncrypted)
                        .toAddressHash(toAddressHash)
                        .payloadJson(payloadEncrypted)
                        .sourceDomain(request.sourceDomain())
                        .sourceEventId(request.sourceEventId())
                        .userId(request.userId())
                        .organizationId(request.organizationId())
                        .idempotencyKey(idempotencyKey)
                        .build()
        );

        try {
            EmailOutboxEntity saved = repository.saveAndFlush(entity);
            meterRegistry.counter("email_outbox.enqueued",
                    "template", request.templateKind(),
                    "source", request.sourceDomain()).increment();
            return saved.getId();
        } catch (DataIntegrityViolationException ex) {
            // UNIQUE 違反 (idempotency_key 重複)
            throw new EmailOutboxValidationException(
                    "EMAIL_OUTBOX_004",
                    "Duplicate idempotency key: " + idempotencyKey,
                    ex
            );
        }
    }

    // -----------------------------------------------------------------------
    // enqueueAll（CMP-260920-1040 §12）
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>呼び出し側（{@code ConfirmableFanoutChunkSink}）の既存トランザクションに<b>参加</b>する
     * （{@code @Transactional} を付けない。REQUIRES_NEW にすると呼び出し側のチャンクトランザクションと
     * 別コミットになり、「受信者行 ↔ outbox 行」の両方があるか両方ないかの不変条件（AC-46）が崩れる）。</p>
     */
    @Override
    public List<UUID> enqueueAll(List<EmailOutboxRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        for (EmailOutboxRequest request : requests) {
            validateRequest(request);
        }

        List<UUID> ids = new ArrayList<>(requests.size());
        StringBuilder sqlBuilder = new StringBuilder(
                "INSERT INTO email_outbox (" + OUTBOX_INSERT_COLUMNS + ") VALUES ");
        Object[] args = new Object[requests.size() * OUTBOX_COLS_PER_ROW];
        int a = 0;
        for (int i = 0; i < requests.size(); i++) {
            if (i > 0) {
                sqlBuilder.append(',');
            }
            sqlBuilder.append(OUTBOX_ROW_PLACEHOLDERS);

            EmailOutboxRequest request = requests.get(i);
            UUID id = UuidV7.generate();
            ids.add(id);

            byte[] payloadEncrypted = encryptPayload(request.payloadVars());
            byte[] toAddressEncrypted = encryption.encryptBytes(
                    request.toAddress().getBytes(StandardCharsets.UTF_8));
            byte[] toAddressHash = HexFormat.of().parseHex(encryption.hmac(request.toAddress()));
            String idempotencyKey = request.idempotencyKey() != null
                    ? request.idempotencyKey()
                    : keyGen.generate(request.userId(), request.templateKind(), request.sourceEventId());

            args[a++] = uuidToBytes(id);
            args[a++] = request.templateKind();
            args[a++] = request.locale();
            args[a++] = toAddressEncrypted;
            args[a++] = toAddressHash;
            args[a++] = payloadEncrypted;
            args[a++] = request.sourceDomain();
            args[a++] = request.sourceEventId();
            args[a++] = request.userId();
            args[a++] = request.organizationId();
            args[a++] = idempotencyKey;
            args[a++] = EmailOutboxStatus.PENDING.name();
            args[a++] = 0;
            // next_attempt_at / created_at / updated_at は SQL リテラル UTC_TIMESTAMP() が値を作る（束縛しない）。
        }
        jdbcTemplate.update(sqlBuilder.toString(), args);

        meterRegistry.counter("email_outbox.enqueued_bulk").increment(requests.size());
        return ids;
    }

    /** UUID を BINARY(16) 用のバイト列（MSB+LSB）へ変換する。 */
    private static byte[] uuidToBytes(UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        return buffer.array();
    }

    // -----------------------------------------------------------------------
    // processOne
    // -----------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processOne(UUID id) {
        EmailOutboxEntity row = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("EmailOutbox not found: " + id));

        if (row.getStatusAsEnum() != EmailOutboxStatus.PENDING) {
            // 既に別 Worker / 別バッチが取得済。SKIP LOCKED で取り合いを抑止しているが安全網。
            return;
        }

        row.markSending();
        repository.save(row);

        try {
            String toAddress = new String(
                    encryption.decryptBytes(row.getToAddress()),
                    StandardCharsets.UTF_8
            );
            Map<String, String> payloadVars = decodePayload(row.getPayloadJson());
            RenderedEmail rendered = renderTemplate(row.getTemplateKind(), row.getLocale(), payloadVars);

            Instant sendStart = Instant.now();
            String messageId = emailTransport.send(toAddress, rendered.subject(), rendered.html());
            Duration sendDuration = Duration.between(sendStart, Instant.now());

            row.markSent(messageId);
            repository.save(row);
            meterRegistry.counter("email_outbox.sent",
                    "template", row.getTemplateKind()).increment();
            metrics.recordSendDuration(sendDuration, row.getTemplateKind());

        } catch (RuntimeException ex) {
            if (classifier.isPermanent(ex)) {
                row.markDeadLetter(ex);
                repository.save(row);
                meterRegistry.counter("email_outbox.dead_letter",
                        "template", row.getTemplateKind()).increment();
                notifyDeadLetter(row, ex);
            } else {
                row.applyBackoff(ex);
                repository.save(row);
                meterRegistry.counter("email_outbox.transient_failure",
                        "template", row.getTemplateKind()).increment();
            }
        }
    }

    // -----------------------------------------------------------------------
    // 内部処理
    // -----------------------------------------------------------------------

    /**
     * リクエストのバリデーション (EMAIL_OUTBOX_001..003)。
     * idempotency_key 重複 (EMAIL_OUTBOX_004) は INSERT 時の UNIQUE 違反で検出する。
     */
    private void validateRequest(EmailOutboxRequest request) {
        if (request == null) {
            throw new EmailOutboxValidationException("EMAIL_OUTBOX_002", "Request is null");
        }
        if (request.templateKind() == null || request.templateKind().isBlank()) {
            throw new EmailOutboxValidationException("EMAIL_OUTBOX_002", "templateKind is required");
        }
        if (request.locale() == null || request.locale().isBlank()) {
            throw new EmailOutboxValidationException("EMAIL_OUTBOX_002", "locale is required");
        }
        if (request.toAddress() == null || !EMAIL_PATTERN.matcher(request.toAddress()).matches()) {
            throw new EmailOutboxValidationException(
                    "EMAIL_OUTBOX_001",
                    "Invalid email address format: " + request.toAddress()
            );
        }
        if (request.sourceDomain() == null || request.sourceDomain().isBlank()) {
            throw new EmailOutboxValidationException("EMAIL_OUTBOX_002", "sourceDomain is required");
        }
    }

    /** payload JSON 化 → サイズチェック → 暗号化。 */
    private byte[] encryptPayload(Map<String, String> payloadVars) {
        if (payloadVars == null || payloadVars.isEmpty()) {
            return null;
        }
        byte[] jsonBytes;
        try {
            jsonBytes = objectMapper.writeValueAsBytes(payloadVars);
        } catch (JsonProcessingException ex) {
            throw new EmailOutboxValidationException(
                    "EMAIL_OUTBOX_002",
                    "payloadVars serialization failed",
                    ex
            );
        }
        if (jsonBytes.length > PAYLOAD_MAX_BYTES) {
            throw new EmailOutboxValidationException(
                    "EMAIL_OUTBOX_003",
                    "Payload size exceeds the limit (8000 bytes): " + jsonBytes.length
            );
        }
        return encryption.encryptBytes(jsonBytes);
    }

    /** 暗号化済 payload を復号して Map<String,String> に戻す。 */
    private Map<String, String> decodePayload(byte[] encrypted) {
        if (encrypted == null) {
            return Map.of();
        }
        byte[] decrypted = encryption.decryptBytes(encrypted);
        try {
            return objectMapper.readValue(decrypted, new TypeReference<Map<String, String>>() {});
        } catch (Exception ex) {
            // 復号後の JSON パース失敗は永久失敗扱い (FAILED 相当)。
            // ただし呼び出し側で SesExceptionClassifier に通すと一時失敗扱いされるので、
            // ここでは RuntimeException を投げ直して processOne 側で FAILED 化する余地を残す。
            // Phase 18-a では一時失敗扱い (バックオフ) でも実害なし。TODO: FAILED 専用例外導入。
            throw new IllegalStateException("payload_json decode failed", ex);
        }
    }

    /**
     * テンプレ種別ごとにレンダリング。
     *
     * <p>VERIFICATION / PASSWORD_RESET は既存 Thymeleaf テンプレを使用。
     * 残 12 種（Phase 18-b/c 移行分）は呼び出し元が subject/body を組み立て
     * payloadVars に詰めてくる「スルー方式」で処理する。</p>
     */
    private RenderedEmail renderTemplate(String templateKind, String localeStr, Map<String, String> vars) {
        Locale locale = Locale.forLanguageTag(localeStr);
        return switch (templateKind) {
            case "VERIFICATION" -> {
                String html = renderer.renderVerificationEmail(
                        vars.getOrDefault("displayName", ""),
                        vars.getOrDefault("verifyUrl", ""),
                        locale
                );
                String subject = renderer.resolveMessage("email.verification.subject", locale);
                yield new RenderedEmail(subject, html);
            }
            case "PASSWORD_RESET" -> {
                String html = renderer.renderPasswordResetEmail(
                        vars.getOrDefault("resetUrl", ""),
                        locale
                );
                String subject = renderer.resolveMessage("email.password-reset.subject", locale);
                yield new RenderedEmail(subject, html);
            }
            // Phase 18-b/c スルー方式: 呼び出し元が subject/body を組み立てて payloadVars に詰める
            case "RESERVATION_EMERGENCY_CLOSURE",
                 "ANALYTICS_KPI_MONTHLY",
                 "ANALYTICS_SUMMARY",
                 "ADVERTISING_INVOICE_OVERDUE",
                 "ADVERTISING_REPORT",
                 "ERROR_REPORT_WEEKLY",
                 "NOTIFICATION_CONFIRM",
                 "GDPR_EXPORT_READY",
                 "GDPR_EXPORT_FAILED",
                 "GDPR_WITHDRAWAL_REMINDER",
                 "RESERVATION_EMERGENCY_REMINDER",
                 "RESERVATION_EMERGENCY_UNCONFIRMED",
                 "RESERVATION_RECEIVED_NOTIFY",
                 "GUARDIANSHIP_PROGRESSION_NOTICE",
                 "DIRECT_MAIL_AD",
                 "PROVISIONING_ADMIN_INVITE" -> {
                String subject = vars.get("subject");
                String htmlBody = vars.get("body");
                if (subject == null || htmlBody == null) {
                    throw new EmailOutboxValidationException(
                            "EMAIL_OUTBOX_002",
                            templateKind + " には subject と body が必要"
                    );
                }
                yield new RenderedEmail(subject, htmlBody);
            }
            default -> throw new IllegalStateException(
                    "Unsupported templateKind: " + templateKind
            );
        };
    }

    /**
     * DEAD_LETTER 到達時の SYSTEM_ADMIN 通知。
     * TODO: Phase 18-a では log.error() で代用。
     *   - F10.6 ErrorReport 起票 (errorCode=EMAIL_OUTBOX_DEAD_LETTER)
     *   - F04.3 PushNotification (SYSTEM_ADMIN ロール / priority=HIGH)
     */
    private void notifyDeadLetter(EmailOutboxEntity row, Throwable ex) {
        log.error("EmailOutbox DEAD_LETTER: id={} template={} source={} cause={}",
                row.getId(), row.getTemplateKind(), row.getSourceDomain(),
                ex.getClass().getSimpleName(), ex);
    }

    /** テンプレレンダリング結果。 */
    record RenderedEmail(String subject, String html) {
    }
}
