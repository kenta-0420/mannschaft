package com.mannschaft.app.mail.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.dto.EmailOutboxDetailResponse;
import com.mannschaft.app.admin.dto.EmailOutboxMetricsResponse;
import com.mannschaft.app.admin.dto.EmailOutboxSummaryResponse;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EmailOutboxAdminServiceImpl} の単体テスト (F09.18 Phase 18-d)。
 *
 * <p>Repository / EncryptionService / AuditLogService をモック化し、
 * サービスのビジネスロジック（状態ガード・PII復号・監査ログ呼び出し）を検証する。</p>
 */
@DisplayName("EmailOutboxAdminService 単体テスト")
@ExtendWith(MockitoExtension.class)
class EmailOutboxAdminServiceTest {

    @Mock
    private EmailOutboxRepository outboxRepository;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private AuditLogService auditLogService;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private EmailOutboxAdminServiceImpl service;

    private static final UUID TEST_ID = UUID.randomUUID();
    private static final Long OPERATOR_ID = 1L;
    private static final String IP = "127.0.0.1";
    private static final String UA = "test-agent";

    @BeforeEach
    void setUp() {
        // デフォルトでは何もスタブしない（Mockito strict mode = UnnecessaryStubbingException を回避）
    }

    // -----------------------------------------------------------------------
    // listOutbox
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("listOutbox_noFilters_returnsPage: フィルターなしで findByFilters が呼ばれ DTO に変換される")
    void listOutbox_noFilters_returnsPage() {
        EmailOutboxEntity entity = buildEntity(EmailOutboxStatus.PENDING.name());
        PageImpl<EmailOutboxEntity> page = new PageImpl<>(List.of(entity));
        when(outboxRepository.findByFilters(isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(page);

        Page<EmailOutboxSummaryResponse> result =
                service.listOutbox(null, null, null, null, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).status()).isEqualTo(EmailOutboxStatus.PENDING.name());
    }

    @Test
    @DisplayName("listOutbox_withStatusFilter_passesStatus: status フィルターが Repository に伝播する")
    void listOutbox_withStatusFilter_passesStatus() {
        when(outboxRepository.findByFilters(eq("DEAD_LETTER"), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.listOutbox("DEAD_LETTER", null, null, null, PageRequest.of(0, 10));

        verify(outboxRepository).findByFilters(eq("DEAD_LETTER"), isNull(), isNull(), isNull(), any());
    }

    // -----------------------------------------------------------------------
    // getOutboxDetail
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getDetail_exists_returnsDecryptedPayload: to_address と payloadVars が復号されて返る")
    void getDetail_exists_returnsDecryptedPayload() throws Exception {
        String expectedEmail = "user@example.com";
        String expectedJson = "{\"name\":\"Alice\"}";
        byte[] encryptedAddress = "enc_addr".getBytes(StandardCharsets.UTF_8);
        byte[] encryptedPayload = "enc_payload".getBytes(StandardCharsets.UTF_8);

        EmailOutboxEntity entity = buildEntity(EmailOutboxStatus.PENDING.name());
        entity.setToAddress(encryptedAddress);
        entity.setPayloadJson(encryptedPayload);

        when(outboxRepository.findById(TEST_ID)).thenReturn(Optional.of(entity));
        when(encryptionService.decryptBytes(encryptedAddress))
                .thenReturn(expectedEmail.getBytes(StandardCharsets.UTF_8));
        when(encryptionService.decryptBytes(encryptedPayload))
                .thenReturn(expectedJson.getBytes(StandardCharsets.UTF_8));

        EmailOutboxDetailResponse response =
                service.getOutboxDetail(TEST_ID, OPERATOR_ID, IP, UA);

        assertThat(response.toAddress()).isEqualTo(expectedEmail);
        assertThat(response.payloadVars()).containsEntry("name", "Alice");
    }

    @Test
    @DisplayName("getDetail_payloadNull_returnsNullPayloadVars: payload_json が null の場合 payloadVars も null")
    void getDetail_payloadNull_returnsNullPayloadVars() {
        byte[] encryptedAddress = "enc_addr".getBytes(StandardCharsets.UTF_8);
        EmailOutboxEntity entity = buildEntity(EmailOutboxStatus.SENT.name());
        entity.setToAddress(encryptedAddress);
        // payload_json は null のまま（30日パージ済みを模倣）

        when(outboxRepository.findById(TEST_ID)).thenReturn(Optional.of(entity));
        when(encryptionService.decryptBytes(encryptedAddress))
                .thenReturn("user@example.com".getBytes(StandardCharsets.UTF_8));

        EmailOutboxDetailResponse response =
                service.getOutboxDetail(TEST_ID, OPERATOR_ID, IP, UA);

        assertThat(response.payloadVars()).isNull();
    }

    @Test
    @DisplayName("getDetail_notFound_throwsNoSuchElement: 存在しない UUID は NoSuchElementException")
    void getDetail_notFound_throwsNoSuchElement() {
        when(outboxRepository.findById(TEST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOutboxDetail(TEST_ID, OPERATOR_ID, IP, UA))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("getDetail_recordsAuditLog: VIEWED イベントが auditLogService.record で呼ばれる")
    void getDetail_recordsAuditLog() {
        EmailOutboxEntity entity = buildEntity(EmailOutboxStatus.PENDING.name());
        entity.setToAddress("enc".getBytes(StandardCharsets.UTF_8));

        when(outboxRepository.findById(TEST_ID)).thenReturn(Optional.of(entity));
        when(encryptionService.decryptBytes(any(byte[].class)))
                .thenReturn("u@e.com".getBytes(StandardCharsets.UTF_8));

        service.getOutboxDetail(TEST_ID, OPERATOR_ID, IP, UA);

        verify(auditLogService).record(
                eq("SYSTEM_ADMIN_EMAIL_OUTBOX_VIEWED"),
                eq(OPERATOR_ID), isNull(), isNull(), isNull(),
                eq(IP), eq(UA), isNull(), anyString());
    }

    // -----------------------------------------------------------------------
    // retryDeadLetter
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("retryDeadLetter_deadLetterState_changesPending: DEAD_LETTER → PENDING に遷移し save が呼ばれる")
    void retryDeadLetter_deadLetterState_changesPending() {
        EmailOutboxEntity entity = buildEntity(EmailOutboxStatus.DEAD_LETTER.name());
        when(outboxRepository.findById(TEST_ID)).thenReturn(Optional.of(entity));

        service.retryDeadLetter(TEST_ID, OPERATOR_ID, IP, UA);

        assertThat(entity.getStatus()).isEqualTo(EmailOutboxStatus.PENDING.name());
        verify(outboxRepository).save(entity);
        verify(auditLogService).record(
                eq("SYSTEM_ADMIN_EMAIL_OUTBOX_RETRIED"),
                eq(OPERATOR_ID), isNull(), isNull(), isNull(),
                eq(IP), eq(UA), isNull(), anyString());
    }

    @Test
    @DisplayName("retryDeadLetter_nonDeadLetterState_throws: PENDING の行はリトライ不可で IllegalStateException")
    void retryDeadLetter_nonDeadLetterState_throws() {
        EmailOutboxEntity entity = buildEntity(EmailOutboxStatus.PENDING.name());
        when(outboxRepository.findById(TEST_ID)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.retryDeadLetter(TEST_ID, OPERATOR_ID, IP, UA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEAD_LETTER ではありません");
    }

    // -----------------------------------------------------------------------
    // cancelPending
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("cancelPending_pendingState_changesCancelled: PENDING → CANCELLED に遷移し save が呼ばれる")
    void cancelPending_pendingState_changesCancelled() {
        EmailOutboxEntity entity = buildEntity(EmailOutboxStatus.PENDING.name());
        when(outboxRepository.findById(TEST_ID)).thenReturn(Optional.of(entity));

        service.cancelPending(TEST_ID, OPERATOR_ID, IP, UA);

        assertThat(entity.getStatus()).isEqualTo(EmailOutboxStatus.CANCELLED.name());
        verify(outboxRepository).save(entity);
        verify(auditLogService).record(
                eq("SYSTEM_ADMIN_EMAIL_OUTBOX_CANCELLED"),
                eq(OPERATOR_ID), isNull(), isNull(), isNull(),
                eq(IP), eq(UA), isNull(), anyString());
    }

    @Test
    @DisplayName("cancelPending_nonPendingState_throws: SENT の行はキャンセル不可で IllegalStateException")
    void cancelPending_nonPendingState_throws() {
        EmailOutboxEntity entity = buildEntity(EmailOutboxStatus.SENT.name());
        when(outboxRepository.findById(TEST_ID)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.cancelPending(TEST_ID, OPERATOR_ID, IP, UA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING ではありません");
    }

    // -----------------------------------------------------------------------
    // getMetrics
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getMetrics_returnsAggregated: countByStatus 5回 + countByStatusSince 2回 + findFirst が呼ばれ集計値が正しい")
    void getMetrics_returnsAggregated() {
        when(outboxRepository.countByStatus(EmailOutboxStatus.PENDING.name())).thenReturn(5L);
        when(outboxRepository.countByStatus(EmailOutboxStatus.SENDING.name())).thenReturn(2L);
        when(outboxRepository.countByStatus(EmailOutboxStatus.DEAD_LETTER.name())).thenReturn(1L);
        when(outboxRepository.countByStatus(EmailOutboxStatus.FAILED.name())).thenReturn(0L);
        when(outboxRepository.countByStatus(EmailOutboxStatus.CANCELLED.name())).thenReturn(3L);

        // 直近 24h: SENT=80, DEAD_LETTER=20 → 成功率=0.8
        when(outboxRepository.countByStatusSince(eq(EmailOutboxStatus.SENT.name()), any(LocalDateTime.class)))
                .thenReturn(80L);
        when(outboxRepository.countByStatusSince(eq(EmailOutboxStatus.DEAD_LETTER.name()), any(LocalDateTime.class)))
                .thenReturn(20L);

        // 最古 PENDING: createdAt = 60秒前（@Setter がないため ReflectionTestUtils で設定）
        EmailOutboxEntity oldest = buildEntity(EmailOutboxStatus.PENDING.name());
        ReflectionTestUtils.setField(oldest, "createdAt", LocalDateTime.now().minusSeconds(60));
        when(outboxRepository.findFirstByStatusOrderByCreatedAtAsc(EmailOutboxStatus.PENDING.name()))
                .thenReturn(Optional.of(oldest));

        EmailOutboxMetricsResponse metrics = service.getMetrics();

        assertThat(metrics.queueDepthPending()).isEqualTo(5L);
        assertThat(metrics.queueDepthSending()).isEqualTo(2L);
        assertThat(metrics.queueDepthDeadLetter()).isEqualTo(1L);
        assertThat(metrics.queueDepthFailed()).isEqualTo(0L);
        assertThat(metrics.queueDepthCancelled()).isEqualTo(3L);
        assertThat(metrics.successRate24h()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.01));
        assertThat(metrics.oldestPendingAgeSeconds()).isGreaterThanOrEqualTo(55L);
    }

    // -----------------------------------------------------------------------
    // テストフィクスチャ
    // -----------------------------------------------------------------------

    /** テスト用 EmailOutboxEntity を Builder で生成する。 */
    private EmailOutboxEntity buildEntity(String status) {
        EmailOutboxEntity entity = EmailOutboxEntity.builder()
                .templateKind("VERIFICATION")
                .locale("ja")
                .toAddress("enc_addr".getBytes(StandardCharsets.UTF_8))
                .toAddressHash("hash".getBytes(StandardCharsets.UTF_8))
                .sourceDomain("auth")
                .idempotencyKey(UUID.randomUUID().toString().replace("-", ""))
                .build();
        entity.setId(TEST_ID);
        // ステータスを内部メソッドで設定（Builder では status フィールドが null になるため）
        switch (status) {
            case "DEAD_LETTER" -> entity.markDeadLetter(new RuntimeException("test"));
            case "SENT" -> entity.markSent("ses-msg-id");
            case "CANCELLED" -> entity.markCancelled();
            case "SENDING" -> entity.markSending();
            // PENDING は @PrePersist がないためリフレクションで直接セット
            default -> ReflectionTestUtils.setField(entity, "status", EmailOutboxStatus.PENDING.name());
        }
        // createdAt / nextAttemptAt は @PrePersist が DB 保存時に設定するフィールドのため
        // テスト環境では ReflectionTestUtils で直接注入する
        if (entity.getCreatedAt() == null) {
            ReflectionTestUtils.setField(entity, "createdAt", LocalDateTime.now());
        }
        if (entity.getNextAttemptAt() == null) {
            ReflectionTestUtils.setField(entity, "nextAttemptAt", LocalDateTime.now());
        }
        return entity;
    }
}
