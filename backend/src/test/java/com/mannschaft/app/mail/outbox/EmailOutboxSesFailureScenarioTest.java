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
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * F09.18 TC-5: 2026-05-18 SES 失敗事故再現テスト。
 *
 * <p>2026-05-18 11:49、hideharu215@yahoo.co.jp の新規ユーザー登録時に
 * AWS SES 認証情報未設定で認証メール送信が失敗し、raw token が永久喪失した事故を再現する。
 * F09.18 Phase 18-a/b で実装した outbox 基盤と致命的 4 caller 移行が、
 * この事故シナリオを根治していることを検証する。</p>
 *
 * <p>検証シナリオ:</p>
 * <ol>
 *   <li>SES 失敗時でもトークン（verifyUrl）は outbox に保持される</li>
 *   <li>SES 復旧後に processOne() でメールが送信され SENT になる</li>
 *   <li>SES 失敗 → backoff → 再試行で最終的に SENT になる</li>
 * </ol>
 */
@DisplayName("F09.18 TC-5: 2026-05-18 SES失敗事故再現テスト")
@ExtendWith(MockitoExtension.class)
class EmailOutboxSesFailureScenarioTest {

    @Mock private EmailOutboxRepository repository;
    @Mock private EncryptionService encryption;
    @Mock private EmailTemplateRenderer renderer;
    @Mock private EmailTransport emailTransport;  // AC4: SesV2Client → EmailTransport に変更
    @Mock private SesExceptionClassifier classifier;
    @Spy private IdempotencyKeyGenerator keyGen = new IdempotencyKeyGenerator();
    @Spy private io.micrometer.core.instrument.MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @Mock private EmailOutboxMicrometerMetrics metrics;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private EmailOutboxServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(encryption.encryptBytes(any(byte[].class)))
                .thenAnswer(inv -> {
                    byte[] in = inv.getArgument(0);
                    // 簡易: IV(12) + データ + Tag(16) の計 28 バイトオーバーヘッドを模倣
                    byte[] out = new byte[in.length + 28];
                    System.arraycopy(in, 0, out, 12, in.length);
                    return out;
                });
        lenient().when(encryption.hmac(any()))
                .thenReturn("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        lenient().when(repository.saveAndFlush(any(EmailOutboxEntity.class)))
                .thenAnswer(inv -> {
                    EmailOutboxEntity e = inv.getArgument(0);
                    if (e.getId() == null) e.setId(UUID.randomUUID());
                    return e;
                });
    }

    // -----------------------------------------------------------------------
    // テスト 1: SES 失敗時でもトークンは outbox に保持される
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("2026-05-18事故再現: SES失敗時でもトークンは outbox に保持される")
    void ses_failure_token_is_preserved_in_outbox() {
        // (1) enqueue → PENDING に積まれる
        UUID outboxId = service.enqueue(new EmailOutboxRequest(
                "VERIFICATION", "ja", "hideharu215@yahoo.co.jp",
                Map.of("displayName", "hideみharu", "verifyUrl", "http://localhost:3000/verify-email?token=abc123"),
                "auth",
                "register:999",
                null, 999L, null
        ));

        // (2) enqueue 後に repository.saveAndFlush が呼ばれた
        ArgumentCaptor<EmailOutboxEntity> savedCaptor = ArgumentCaptor.forClass(EmailOutboxEntity.class);
        verify(repository).saveAndFlush(savedCaptor.capture());
        EmailOutboxEntity savedEntity = savedCaptor.getValue();

        // トークンが暗号化保存されている（payload が null でない）
        assertThat(savedEntity.getPayloadJson())
                .as("SES 未設定でも verifyUrl トークンが暗号化 payload として保存されていること")
                .isNotNull();

        // PENDING 状態で保持されている（即時喪失しない）
        assertThat(savedEntity.getStatus())
                .as("enqueue 直後は PENDING で保持されること（旧実装では sendEmail() 失敗で即消滅していた）")
                .isEqualTo("PENDING");

        // enqueue は ID を返す（後からポーリングで拾える）
        assertThat(outboxId)
                .as("outbox ID が返ること（復旧バッチが対象を特定できる）")
                .isNotNull();
    }

    // -----------------------------------------------------------------------
    // テスト 2: SES 復旧後に processOne で SENT に遷移する
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("2026-05-18事故再現: SES復旧後 processOne で SENT に遷移する")
    void ses_recovery_delivers_email_and_marks_sent() {
        // エンティティを PENDING 状態で作成
        UUID id = UUID.randomUUID();
        EmailOutboxEntity entity = buildPendingVerificationEntity(id);

        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(encryption.decryptBytes(any())).thenAnswer(inv -> {
            // 簡易: encryptBytes の逆 (offset 12 から元のバイト列)
            byte[] encrypted = inv.getArgument(0);
            if (encrypted.length <= 28) return new byte[0];
            byte[] decrypted = new byte[encrypted.length - 28];
            System.arraycopy(encrypted, 12, decrypted, 0, decrypted.length);
            return decrypted;
        });
        when(renderer.renderVerificationEmail(any(), any(), any())).thenReturn("<html>...</html>");
        when(renderer.resolveMessage(any(), any())).thenReturn("認証メール件名");
        // AC4: sesClient.sendEmail → emailTransport.send に変更
        when(emailTransport.send(any(), any(), any())).thenReturn("ses-msg-001");

        service.processOne(id);

        // エンティティが SENT になったことを verify
        ArgumentCaptor<EmailOutboxEntity> captor = ArgumentCaptor.forClass(EmailOutboxEntity.class);
        verify(repository, atLeast(2)).save(captor.capture());
        List<EmailOutboxEntity> savedStates = captor.getAllValues();

        // 最後の save が SENT 状態
        assertThat(savedStates.get(savedStates.size() - 1).getStatus())
                .as("SES 復旧後は最終的に SENT になること")
                .isEqualTo("SENT");

        // EmailTransport に実際に send が呼ばれた
        verify(emailTransport, times(1)).send(any(), any(), any());
    }

    // -----------------------------------------------------------------------
    // テスト 3: SES 失敗 → backoff → 再試行で最終的に SENT
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("2026-05-18事故再現: SES失敗→backoff→再試行で最終的にSENT")
    void ses_failure_then_backoff_then_retry_success() {
        UUID id = UUID.randomUUID();
        EmailOutboxEntity entity = buildPendingVerificationEntity(id);

        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(encryption.decryptBytes(any())).thenAnswer(inv -> {
            byte[] encrypted = inv.getArgument(0);
            if (encrypted.length <= 28) return new byte[0];
            byte[] decrypted = new byte[encrypted.length - 28];
            System.arraycopy(encrypted, 12, decrypted, 0, decrypted.length);
            return decrypted;
        });
        when(renderer.renderVerificationEmail(any(), any(), any())).thenReturn("<html>...</html>");
        when(renderer.resolveMessage(any(), any())).thenReturn("認証メール件名");

        // 1回目: SES 失敗 → 2回目: 成功 の順で設定（STRICT_STUBS と再スタブ問題を回避）
        // AC4: sesClient.sendEmail → emailTransport.send が例外を投げる→成功の形に変更
        RuntimeException transientEx = SesV2Exception.builder().message("連接エラー").build();
        // 1回目は例外、2回目は成功を返すよう順番に設定
        when(emailTransport.send(any(), any(), any()))
                .thenThrow(transientEx)
                .thenReturn("ses-retry-001");
        when(classifier.isPermanent(any())).thenReturn(false);

        service.processOne(id);

        // バックオフ後: retryCount が増えて PENDING に戻る
        assertThat(entity.getRetryCount())
                .as("SES 失敗後にリトライカウントが増えること")
                .isGreaterThan(0);

        // payloadJson は破棄されていない（token 保持が事故根治の核心）
        assertThat(entity.getPayloadJson())
                .as("SES 失敗後も verifyUrl トークンが payload に保持されていること（旧実装では喪失していた）")
                .isNotNull();

        // ステータスが PENDING のままであること（DEAD_LETTER でも FAILED でもない）
        assertThat(entity.getStatus())
                .as("一時失敗後は PENDING でリトライ待ちになること")
                .isEqualTo("PENDING");

        // 2回目: entity を PENDING に戻してから SES 成功でリトライ
        entity.markPendingForRetry();

        service.processOne(id);

        assertThat(entity.getStatus())
                .as("SES 復旧後の再試行で SENT になること")
                .isEqualTo("SENT");
    }

    // -----------------------------------------------------------------------
    // ヘルパーメソッド
    // -----------------------------------------------------------------------

    /**
     * PENDING 状態の VERIFICATION エンティティを構築する。
     * 2026-05-18 事故時の hideharu215@yahoo.co.jp 新規登録シナリオを模倣する。
     *
     * <p>注意: toAddress / payloadJson は暗号化済みバイト列として保持されるが、
     * テストではモックした decryptBytes が「offset 12 から (length - 28) バイトを抽出」する実装。
     * toAddress は email バイト列を offset 12 に詰める。
     * payloadJson は有効な JSON バイト列を offset 12 に詰め、decryptBytes で取り出したとき
     * JSON パースが成功するよう設計する。</p>
     */
    private EmailOutboxEntity buildPendingVerificationEntity(UUID id) {
        // toAddress: 暗号化バイト列 (offset 12 に email バイト)
        byte[] emailBytes = "hideharu215@yahoo.co.jp".getBytes(StandardCharsets.UTF_8);
        // fakeEncrypted: IV(12) + email(23) + tag(16) = 51 バイト
        byte[] fakeToAddress = new byte[emailBytes.length + 28];
        System.arraycopy(emailBytes, 0, fakeToAddress, 12, emailBytes.length);

        // payloadJson: 暗号化バイト列
        // decryptBytes モックは「offset 12 から (length - 28) バイトを抽出」するので
        // 有効な JSON バイト列を offset 12 に詰める必要がある
        byte[] fakePayload;
        try {
            // 実際の JSON バイト列を作成
            byte[] jsonBytes = new ObjectMapper().writeValueAsBytes(
                    Map.of("verifyUrl", "http://localhost:3000/verify-email?token=abc123",
                            "displayName", "test")
            );
            // fakePayload: IV(12) + jsonBytes + tag(16) = 12 + jsonBytes.length + 16
            fakePayload = new byte[jsonBytes.length + 28];
            System.arraycopy(jsonBytes, 0, fakePayload, 12, jsonBytes.length);
        } catch (Exception e) {
            // フォールバック: 最小の有効 JSON
            byte[] minJson = "{}".getBytes(StandardCharsets.UTF_8);
            fakePayload = new byte[minJson.length + 28];
            System.arraycopy(minJson, 0, fakePayload, 12, minJson.length);
        }

        // EmailOutboxEntity は status/retryCount/nextAttemptAt に @Setter がないため builder を使う
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
        // prepareForEnqueue で PENDING ステータスを設定
        EmailOutboxEntity.prepareForEnqueue(entity);
        return entity;
    }
}
