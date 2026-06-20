package com.mannschaft.app.mail.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.EmailTemplateRenderer;
import com.mannschaft.app.common.EncryptionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * F09.18: AC3 EmailOutboxServiceImpl#processOne の EmailTransport 経由挙動テスト。
 *
 * <p>SesV2Client の直接 mock ではなく {@link EmailTransport} の mock を通じて、
 * 送信成功 / 永久失敗 / 一時失敗の各経路を検証する。</p>
 */
@DisplayName("AC3: EmailOutboxServiceImpl#processOne - EmailTransport 経由挙動テスト")
@ExtendWith(MockitoExtension.class)
class EmailOutboxProcessOneTest {

    @Mock private EmailOutboxRepository repository;
    @Mock private EncryptionService encryption;
    @Mock private EmailTemplateRenderer renderer;
    @Mock private EmailTransport emailTransport;   // AC3: SesV2Client ではなく EmailTransport
    @Mock private SesExceptionClassifier classifier;
    @Spy private IdempotencyKeyGenerator keyGen = new IdempotencyKeyGenerator();
    @Spy private io.micrometer.core.instrument.MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @Mock private EmailOutboxMicrometerMetrics metrics;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private EmailOutboxServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(encryption.decryptBytes(any(byte[].class))).thenAnswer(inv -> {
            byte[] encrypted = inv.getArgument(0);
            if (encrypted.length <= 28) return new byte[0];
            byte[] decrypted = new byte[encrypted.length - 28];
            System.arraycopy(encrypted, 12, decrypted, 0, decrypted.length);
            return decrypted;
        });
        lenient().when(renderer.renderVerificationEmail(any(), any(), any())).thenReturn("<html>...</html>");
        lenient().when(renderer.resolveMessage(any(), any())).thenReturn("認証メール件名");
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // -----------------------------------------------------------------------
    // AC3-1: EmailTransport.send 成功 → SENT・messageId 保存
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC3-1: EmailTransport.send 成功時に行が SENT になり messageId が保存される")
    void processOne_success_marksSentWithMessageId() {
        UUID id = UUID.randomUUID();
        EmailOutboxEntity entity = buildPendingVerificationEntity(id);

        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(emailTransport.send(anyString(), anyString(), anyString()))
                .thenReturn("transport-msg-001");

        service.processOne(id);

        ArgumentCaptor<EmailOutboxEntity> captor = ArgumentCaptor.forClass(EmailOutboxEntity.class);
        verify(repository, atLeast(2)).save(captor.capture());
        List<EmailOutboxEntity> saved = captor.getAllValues();

        assertThat(saved.get(saved.size() - 1).getStatus())
                .as("送信成功後は SENT になること")
                .isEqualTo("SENT");

        assertThat(saved.get(saved.size() - 1).getSesMessageId())
                .as("EmailTransport が返した messageId が保存されること")
                .isEqualTo("transport-msg-001");
    }

    // -----------------------------------------------------------------------
    // AC3-2: 永久失敗例外 → DEAD_LETTER
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC3-2: EmailTransport.send が永久失敗例外を投げたら DEAD_LETTER になる")
    void processOne_permanentFailure_marksDeadLetter() {
        UUID id = UUID.randomUUID();
        EmailOutboxEntity entity = buildPendingVerificationEntity(id);

        when(repository.findById(id)).thenReturn(Optional.of(entity));
        // MessageRejectedException（永久失敗の代表例）を EmailTransport が投げる
        RuntimeException permanentEx = MessageRejectedException.builder()
                .message("Message rejected: address not verified")
                .build();
        when(emailTransport.send(anyString(), anyString(), anyString()))
                .thenThrow(permanentEx);
        when(classifier.isPermanent(permanentEx)).thenReturn(true);

        service.processOne(id);

        ArgumentCaptor<EmailOutboxEntity> captor = ArgumentCaptor.forClass(EmailOutboxEntity.class);
        verify(repository, atLeast(2)).save(captor.capture());
        List<EmailOutboxEntity> saved = captor.getAllValues();

        assertThat(saved.get(saved.size() - 1).getStatus())
                .as("永久失敗例外の場合 DEAD_LETTER になること")
                .isEqualTo("DEAD_LETTER");
    }

    // -----------------------------------------------------------------------
    // AC3-3: 一時失敗例外 → backoff (PENDING 維持・retryCount 増加)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC3-3: EmailTransport.send が一時失敗例外を投げたら backoff (PENDING 維持・retryCount 増加)")
    void processOne_transientFailure_appliesBackoff() {
        UUID id = UUID.randomUUID();
        EmailOutboxEntity entity = buildPendingVerificationEntity(id);

        when(repository.findById(id)).thenReturn(Optional.of(entity));
        RuntimeException transientEx = SesV2Exception.builder()
                .message("AWS SDK credentials not found")
                .build();
        when(emailTransport.send(anyString(), anyString(), anyString()))
                .thenThrow(transientEx);
        when(classifier.isPermanent(transientEx)).thenReturn(false);

        service.processOne(id);

        assertThat(entity.getStatus())
                .as("一時失敗後は PENDING のままであること（DEAD_LETTER でない）")
                .isEqualTo("PENDING");

        assertThat(entity.getRetryCount())
                .as("一時失敗後にリトライカウントが増えること")
                .isGreaterThan(0);
    }

    // -----------------------------------------------------------------------
    // ヘルパー
    // -----------------------------------------------------------------------

    private EmailOutboxEntity buildPendingVerificationEntity(UUID id) {
        byte[] emailBytes = "test@example.com".getBytes(StandardCharsets.UTF_8);
        byte[] fakeToAddress = new byte[emailBytes.length + 28];
        System.arraycopy(emailBytes, 0, fakeToAddress, 12, emailBytes.length);

        byte[] fakePayload;
        try {
            byte[] jsonBytes = new ObjectMapper().writeValueAsBytes(
                    Map.of("verifyUrl", "http://localhost:3000/verify-email?token=abc123",
                            "displayName", "テストユーザー")
            );
            fakePayload = new byte[jsonBytes.length + 28];
            System.arraycopy(jsonBytes, 0, fakePayload, 12, jsonBytes.length);
        } catch (Exception e) {
            byte[] minJson = "{}".getBytes(StandardCharsets.UTF_8);
            fakePayload = new byte[minJson.length + 28];
            System.arraycopy(minJson, 0, fakePayload, 12, minJson.length);
        }

        EmailOutboxEntity entity = EmailOutboxEntity.builder()
                .templateKind("VERIFICATION")
                .locale("ja")
                .toAddress(fakeToAddress)
                .toAddressHash(new byte[32])
                .payloadJson(fakePayload)
                .sourceDomain("auth")
                .sourceEventId("register:999")
                .idempotencyKey("test-idempotency-key-" + id)
                .retryCount(0)
                .nextAttemptAt(java.time.LocalDateTime.now().minusSeconds(1))
                .build();
        entity.setId(id);
        EmailOutboxEntity.prepareForEnqueue(entity);
        return entity;
    }
}
