package com.mannschaft.app.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Webhook 冪等性ゲートの単体テスト（恒久 no-op 根治の証明）。
 *
 * <p>冪等判定のセマンティクスを検証する:
 * <ul>
 *   <li>新規 event_id → INSERT して再処理可（true）</li>
 *   <li>{@code PROCESSED}/{@code IGNORED}（確定済み・真の重複）→ スキップ（false）</li>
 *   <li>{@code RECEIVED}（処理中クラッシュ）/{@code FAILED}（dispatch 失敗）→ 再処理可（true）</li>
 *   <li>並行受信の UNIQUE 競合 → 勝者の状態を読み直して再判定</li>
 * </ul>
 * {@link com.mannschaft.app.payment.connect.ConnectWebhookService} の dispatch 失敗時 FAILED 記録と
 * 組み合わさり、Stripe 再送で処理が回復することを保証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookIdempotencyService 単体テスト（冪等判定の根治）")
class WebhookIdempotencyServiceTest {

    @Mock private StripeWebhookEventRepository repository;

    @InjectMocks private WebhookIdempotencyService service;

    private StripeWebhookEventEntity entityWith(WebhookProcessStatus status) {
        return StripeWebhookEventEntity.builder()
                .eventId("evt_1")
                .type("account.updated")
                .livemode(false)
                .processStatus(status)
                .build();
    }

    @Test
    @DisplayName("新規 event_id → RECEIVED を INSERT して true（処理へ進む）")
    void freshEventInsertsAndReturnsTrue() {
        given(repository.findByEventId("evt_1")).willReturn(Optional.empty());

        boolean result = service.tryBegin("evt_1", "account.updated", false);

        assertThat(result).isTrue();
        verify(repository).saveAndFlush(any(StripeWebhookEventEntity.class));
    }

    @Test
    @DisplayName("PROCESSED 済（真の重複）→ false（スキップ・INSERT しない）")
    void processedEventSkips() {
        given(repository.findByEventId("evt_1"))
                .willReturn(Optional.of(entityWith(WebhookProcessStatus.PROCESSED)));

        boolean result = service.tryBegin("evt_1", "account.updated", false);

        assertThat(result).isFalse();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("IGNORED 済（確定済み）→ false（スキップ）")
    void ignoredEventSkips() {
        given(repository.findByEventId("evt_1"))
                .willReturn(Optional.of(entityWith(WebhookProcessStatus.IGNORED)));

        boolean result = service.tryBegin("evt_1", "account.updated", false);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("根治: FAILED 行 → true（再処理を許可。Stripe 再送で回復・恒久 no-op を防ぐ）")
    void failedEventAllowsReprocess() {
        given(repository.findByEventId("evt_1"))
                .willReturn(Optional.of(entityWith(WebhookProcessStatus.FAILED)));

        boolean result = service.tryBegin("evt_1", "account.updated", false);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("根治: RECEIVED 行（処理中クラッシュ）→ true（再処理を許可）")
    void receivedEventAllowsReprocess() {
        given(repository.findByEventId("evt_1"))
                .willReturn(Optional.of(entityWith(WebhookProcessStatus.RECEIVED)));

        boolean result = service.tryBegin("evt_1", "account.updated", false);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("並行受信 UNIQUE 競合 → 勝者が PROCESSED 確定済みなら false（真の重複はスキップ）")
    void concurrentInsertProcessedSkips() {
        // 最初の existence チェックは未存在 → INSERT 試行で UNIQUE 競合 → 読み直しで PROCESSED
        given(repository.findByEventId("evt_1"))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(entityWith(WebhookProcessStatus.PROCESSED)));
        given(repository.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("unique"));

        boolean result = service.tryBegin("evt_1", "account.updated", false);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("並行受信 UNIQUE 競合 → 勝者がまだ RECEIVED なら true（未確定は再処理を許可）")
    void concurrentInsertReceivedAllowsReprocess() {
        given(repository.findByEventId("evt_1"))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(entityWith(WebhookProcessStatus.RECEIVED)));
        given(repository.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("unique"));

        boolean result = service.tryBegin("evt_1", "account.updated", false);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("markFailed → 行を FAILED に更新（再処理可能な状態を残す）")
    void markFailedUpdatesStatus() {
        StripeWebhookEventEntity entity = entityWith(WebhookProcessStatus.RECEIVED);
        given(repository.findByEventId("evt_1")).willReturn(Optional.of(entity));

        service.markFailed("evt_1");

        assertThat(entity.getProcessStatus()).isEqualTo(WebhookProcessStatus.FAILED);
        assertThat(entity.getProcessedAt()).isNotNull();
        verify(repository, times(1)).save(entity);
    }
}
