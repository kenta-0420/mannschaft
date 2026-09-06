package com.mannschaft.app.payment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    /** 固定時計。確定時刻の検証を壁時計に依存させないため（{@code Clock} は本番も注入される）。 */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-05T04:05:06Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime EXPECTED_PROCESSED_AT =
            LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

    @Mock private StripeWebhookEventRepository repository;

    private WebhookIdempotencyService service;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;
    private Level originalLevel;

    @BeforeEach
    void setUpService() {
        service = new WebhookIdempotencyService(repository, FIXED_CLOCK);

        // ログ検証はロガーのレベルを自分で設定しないと、他テストの設定次第で拾えず偽 green になる。
        serviceLogger = (Logger) org.slf4j.LoggerFactory.getLogger(WebhookIdempotencyService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
        originalLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDownLogger() {
        // 同一 fork の後続テストへレベル変更を漏らさない（漏らすと他テストのログ検証が静かに壊れる）。
        serviceLogger.setLevel(originalLevel);
        serviceLogger.detachAppender(logAppender);
        logAppender.stop();
    }

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

    /**
     * {@code markFailed} / {@code markProcessed} の検証。
     *
     * <p><b>なぜエンティティを直接見ないのか</b>: 以前は
     * 「{@code findByEventId} で読んで setter を呼び {@code save} する」という<b>内部手順</b>を
     * 検証していた。しかし受信記録の INSERT は {@code REQUIRES_NEW} の別トランザクションで
     * コミットされるため、業務トランザクション（MySQL 既定の REPEATABLE READ）のスナップショットからは
     * その行が見えず、読み込み経由では確定が一度も走らないことがあった。根治として UPDATE 文へ変えたところ、
     * 振る舞い（行が FAILED になる）は同じなのに旧テストだけが壊れた＝内部手順に結合していた。
     * そこで検証対象を<b>振る舞い</b>（どの行を・どの状態へ・いつ確定するか）へ寄せる。</p>
     */
    @Test
    @DisplayName("markFailed → 当該 event_id の行を FAILED へ確定する（再処理可能な状態を残す）")
    void markFailedUpdatesStatus() {
        given(repository.updateProcessStatus(anyString(), any(), any())).willReturn(1);

        service.markFailed("evt_1");

        verify(repository, times(1))
                .updateProcessStatus("evt_1", WebhookProcessStatus.FAILED, EXPECTED_PROCESSED_AT);
        // FAILED が「再処理可能な状態」であること自体は failedEventAllowsReprocess が測っている。
    }

    @Test
    @DisplayName("markProcessed → 当該 event_id の行を指定状態へ確定する")
    void markProcessedUpdatesStatus() {
        given(repository.updateProcessStatus(anyString(), any(), any())).willReturn(1);

        service.markProcessed("evt_1", WebhookProcessStatus.PROCESSED);

        verify(repository, times(1))
                .updateProcessStatus("evt_1", WebhookProcessStatus.PROCESSED, EXPECTED_PROCESSED_AT);
        assertThat(logAppender.list).as("正常時は警告を出さない").isEmpty();
    }

    @Test
    @DisplayName("確定対象の行が無い（更新0件）は握り潰さず WARN に残す")
    void markProcessedWarnsWhenNoRowUpdated() {
        given(repository.updateProcessStatus(anyString(), any(), any())).willReturn(0);

        service.markProcessed("evt_1", WebhookProcessStatus.PROCESSED);

        assertThat(logAppender.list)
                .as("受信記録が無いのに確定しようとした異常を黙って捨てない")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("evt_1");
                });
    }

    @Test
    @DisplayName("markFailed も更新0件なら WARN に残す")
    void markFailedWarnsWhenNoRowUpdated() {
        given(repository.updateProcessStatus(anyString(), any(), any())).willReturn(0);

        service.markFailed("evt_1");

        assertThat(logAppender.list)
                .anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }
}
