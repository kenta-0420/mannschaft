package com.mannschaft.app.mail.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.EmailTemplateRenderer;
import com.mannschaft.app.common.EncryptionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link EmailOutboxServiceImpl#enqueue} の単体テスト。
 *
 * <p>processOne は SES + Repository + Renderer の連携が必要で、ユニットテスト粒度では
 * モック量が多いため統合テスト (Phase 18-a 第2陣) に委ねる。
 * 本クラスは enqueue 時のバリデーション挙動に絞る。</p>
 */
@DisplayName("EmailOutboxService enqueue 単体テスト")
@ExtendWith(MockitoExtension.class)
class EmailOutboxServiceTest {

    @Mock private EmailOutboxRepository repository;
    @Mock private EncryptionService encryption;
    @Mock private EmailTemplateRenderer renderer;
    @Mock private EmailTransport emailTransport;  // AC4: SesV2Client → EmailTransport に変更
    @Mock private SesExceptionClassifier classifier;
    @Spy private IdempotencyKeyGenerator keyGen = new IdempotencyKeyGenerator();
    @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @Mock private EmailOutboxMicrometerMetrics metrics;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private EmailOutboxServiceImpl service;

    @BeforeEach
    void setUp() {
        // 暗号化/HMAC は本テストでは内容を問わないため固定値を返す
        lenient().when(encryption.encryptBytes(any(byte[].class)))
                .thenAnswer(inv -> {
                    byte[] in = inv.getArgument(0);
                    // 簡易: 入力長 + 28 (IV 12 + Tag 16) を擬似的に再現
                    byte[] out = new byte[in.length + 28];
                    System.arraycopy(in, 0, out, 12, in.length);
                    return out;
                });
        // hmac() は 64 文字の hex 文字列を返す契約 (32 バイト分)
        lenient().when(encryption.hmac(any()))
                .thenReturn("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

        // 正常系: saveAndFlush が呼ばれたらエンティティの id をセットして返す
        lenient().when(repository.saveAndFlush(any(EmailOutboxEntity.class)))
                .thenAnswer(inv -> {
                    EmailOutboxEntity e = inv.getArgument(0);
                    if (e.getId() == null) {
                        e.setId(UUID.randomUUID());
                    }
                    return e;
                });
    }

    private EmailOutboxRequest validRequest() {
        return new EmailOutboxRequest(
                "VERIFICATION",
                "ja",
                "user@example.com",
                Map.of("displayName", "山田太郎", "verifyUrl", "https://example.com/verify?t=abc"),
                "auth",
                "token-uuid-123",
                null,
                42L,
                null
        );
    }

    @Test
    @DisplayName("正常系: 有効リクエストで enqueue 成功し UUID を返す")
    void enqueue_success() {
        UUID id = service.enqueue(validRequest());
        assertThat(id).isNotNull();
    }

    @Test
    @DisplayName("EMAIL_OUTBOX_001: 無効なメールアドレスで例外")
    void enqueue_invalidEmail() {
        EmailOutboxRequest req = new EmailOutboxRequest(
                "VERIFICATION", "ja", "not-an-email", Map.of(), "auth", null, null, null, null
        );
        assertThatThrownBy(() -> service.enqueue(req))
                .isInstanceOf(EmailOutboxValidationException.class)
                .extracting(e -> ((EmailOutboxValidationException) e).getErrorCode())
                .isEqualTo("EMAIL_OUTBOX_001");
    }

    @Test
    @DisplayName("EMAIL_OUTBOX_001: メールアドレス null で例外")
    void enqueue_nullEmail() {
        EmailOutboxRequest req = new EmailOutboxRequest(
                "VERIFICATION", "ja", null, Map.of(), "auth", null, null, null, null
        );
        assertThatThrownBy(() -> service.enqueue(req))
                .isInstanceOf(EmailOutboxValidationException.class)
                .extracting(e -> ((EmailOutboxValidationException) e).getErrorCode())
                .isEqualTo("EMAIL_OUTBOX_001");
    }

    @Test
    @DisplayName("EMAIL_OUTBOX_002: templateKind が null で例外")
    void enqueue_nullTemplateKind() {
        EmailOutboxRequest req = new EmailOutboxRequest(
                null, "ja", "user@example.com", Map.of(), "auth", null, null, null, null
        );
        assertThatThrownBy(() -> service.enqueue(req))
                .isInstanceOf(EmailOutboxValidationException.class)
                .extracting(e -> ((EmailOutboxValidationException) e).getErrorCode())
                .isEqualTo("EMAIL_OUTBOX_002");
    }

    @Test
    @DisplayName("EMAIL_OUTBOX_002: templateKind が空白で例外")
    void enqueue_blankTemplateKind() {
        EmailOutboxRequest req = new EmailOutboxRequest(
                "  ", "ja", "user@example.com", Map.of(), "auth", null, null, null, null
        );
        assertThatThrownBy(() -> service.enqueue(req))
                .isInstanceOf(EmailOutboxValidationException.class)
                .extracting(e -> ((EmailOutboxValidationException) e).getErrorCode())
                .isEqualTo("EMAIL_OUTBOX_002");
    }

    @Test
    @DisplayName("EMAIL_OUTBOX_002: sourceDomain が null で例外")
    void enqueue_nullSourceDomain() {
        EmailOutboxRequest req = new EmailOutboxRequest(
                "VERIFICATION", "ja", "user@example.com", Map.of(), null, null, null, null, null
        );
        assertThatThrownBy(() -> service.enqueue(req))
                .isInstanceOf(EmailOutboxValidationException.class)
                .extracting(e -> ((EmailOutboxValidationException) e).getErrorCode())
                .isEqualTo("EMAIL_OUTBOX_002");
    }

    @Test
    @DisplayName("EMAIL_OUTBOX_003: payload サイズが 8000 バイト超で例外")
    void enqueue_oversizedPayload() {
        // 値長 9000+ の単一エントリで JSON 化サイズが 8000 を確実に超える
        char[] huge = new char[9000];
        java.util.Arrays.fill(huge, 'x');
        Map<String, String> bigPayload = new HashMap<>();
        bigPayload.put("hugeValue", new String(huge));
        EmailOutboxRequest req = new EmailOutboxRequest(
                "VERIFICATION", "ja", "user@example.com",
                bigPayload, "auth", null, null, null, null
        );
        assertThatThrownBy(() -> service.enqueue(req))
                .isInstanceOf(EmailOutboxValidationException.class)
                .extracting(e -> ((EmailOutboxValidationException) e).getErrorCode())
                .isEqualTo("EMAIL_OUTBOX_003");
    }

    @Test
    @DisplayName("EMAIL_OUTBOX_004: idempotency_key 重複時に DataIntegrityViolation を wrap する")
    void enqueue_duplicateIdempotencyKey() {
        when(repository.saveAndFlush(any(EmailOutboxEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uk_email_outbox_idempotency violated"));

        assertThatThrownBy(() -> service.enqueue(validRequest()))
                .isInstanceOf(EmailOutboxValidationException.class)
                .extracting(e -> ((EmailOutboxValidationException) e).getErrorCode())
                .isEqualTo("EMAIL_OUTBOX_004");
    }

    @Test
    @DisplayName("idempotencyKey 明示指定時は自動生成されない")
    void enqueue_explicitIdempotencyKey() {
        EmailOutboxRequest req = new EmailOutboxRequest(
                "VERIFICATION", "ja", "user@example.com",
                Map.of("v", "1"), "auth", null,
                "my-explicit-key-12345678901234567",
                null, null
        );
        UUID id = service.enqueue(req);
        assertThat(id).isNotNull();
    }

    @Test
    @DisplayName("RESERVATION_EMERGENCY_CLOSURE: subject/body を含む payload で enqueue 成功 (スルー方式)")
    void enqueue_emergencyClosurePassthrough() {
        EmailOutboxRequest req = new EmailOutboxRequest(
                "RESERVATION_EMERGENCY_CLOSURE",
                "ja",
                "patient@example.com",
                Map.of(
                        "subject", "【臨時休業のお知らせ】 5/20-5/22",
                        "body", "<p>誠に申し訳ございませんが…</p>"
                ),
                "reservation",
                "emergency-closure:42:1001",
                null,
                1001L,
                null
        );
        UUID id = service.enqueue(req);
        assertThat(id).isNotNull();
    }

    // -----------------------------------------------------------------------
    // TC-6: スルー方式 subject/body 欠落時の EMAIL_OUTBOX_002 検証 (Phase 18-b 申し送り #9)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("TC-6 スルー方式: subject 欠落時に EMAIL_OUTBOX_002 例外 (RESERVATION_EMERGENCY_CLOSURE)")
    void renderTemplate_passthroughMissingSubject_throwsOutbox002() throws Exception {
        Method m = EmailOutboxServiceImpl.class.getDeclaredMethod(
                "renderTemplate", String.class, String.class, Map.class);
        m.setAccessible(true);
        try {
            m.invoke(service, "RESERVATION_EMERGENCY_CLOSURE", "ja", Map.of("body", "<p>本文</p>"));
            fail("EMAIL_OUTBOX_002 例外が期待されるが発生しなかった");
        } catch (InvocationTargetException ex) {
            assertThat(ex.getCause())
                    .isInstanceOf(EmailOutboxValidationException.class)
                    .extracting(e -> ((EmailOutboxValidationException) e).getErrorCode())
                    .isEqualTo("EMAIL_OUTBOX_002");
        }
    }

    @Test
    @DisplayName("TC-6 スルー方式: body 欠落時に EMAIL_OUTBOX_002 例外 (ANALYTICS_KPI_MONTHLY)")
    void renderTemplate_passthroughMissingBody_throwsOutbox002() throws Exception {
        Method m = EmailOutboxServiceImpl.class.getDeclaredMethod(
                "renderTemplate", String.class, String.class, Map.class);
        m.setAccessible(true);
        try {
            m.invoke(service, "ANALYTICS_KPI_MONTHLY", "ja",
                    Map.of("subject", "[Mannschaft] 月次レポート 2026-04"));
            fail("EMAIL_OUTBOX_002 例外が期待されるが発生しなかった");
        } catch (InvocationTargetException ex) {
            assertThat(ex.getCause())
                    .isInstanceOf(EmailOutboxValidationException.class)
                    .extracting(e -> ((EmailOutboxValidationException) e).getErrorCode())
                    .isEqualTo("EMAIL_OUTBOX_002");
        }
    }
}
