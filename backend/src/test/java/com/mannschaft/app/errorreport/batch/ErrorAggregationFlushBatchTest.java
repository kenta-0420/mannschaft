package com.mannschaft.app.errorreport.batch;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.service.ErrorReportAggregator;
import com.mannschaft.app.errorreport.service.ErrorReportAggregator.AggregatedEntry;
import com.mannschaft.app.errorreport.service.ErrorReportNotifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F10.6 §5.6-② — {@link ErrorAggregationFlushBatch} の単体テスト。
 *
 * <p>{@code @Scheduled} のテストは時刻進行を必要とするが、本実装では実行ロジックを
 * {@link ErrorAggregationFlushBatch#doFlush()} に切り出しているため、
 * Awaitility 等の時間進行を使わずに直接呼び出して検証する（より高速・決定的）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorAggregationFlushBatch 単体テスト (F10.6 §5.6-②)")
class ErrorAggregationFlushBatchTest {

    @Mock
    private ErrorReportAggregator aggregator;
    @Mock
    private ErrorReportNotifier notifier;

    @InjectMocks
    private ErrorAggregationFlushBatch batch;

    @Test
    @DisplayName("バッファが空の場合は notifier を呼ばない")
    void emptyBuffer_doesNotNotify() {
        ReflectionTestUtils.setField(batch, "enabled", true);
        given(aggregator.drainAndClear()).willReturn(Map.of());

        batch.doFlush();

        verify(notifier, never()).notifyAggregatedSummary(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("occurrenceCount=1 の entry のみの場合は notifier を呼ばない（初回発火は別経路で送信済み）")
    void onlyFirstOccurrences_areSkipped() throws Exception {
        ReflectionTestUtils.setField(batch, "enabled", true);
        AggregatedEntry single = newEntry("h1", "m1", ErrorReportSeverity.HIGH, 1L);
        given(aggregator.drainAndClear()).willReturn(Map.of("h1", single));

        batch.doFlush();

        verify(notifier, never()).notifyAggregatedSummary(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("occurrenceCount>=2 の entry が含まれる場合、notifier に集約 Map が渡される")
    void buffered_entriesAreSentToNotifier() throws Exception {
        ReflectionTestUtils.setField(batch, "enabled", true);
        AggregatedEntry e1 = newEntry("h1", "m1", ErrorReportSeverity.HIGH, 5L);
        AggregatedEntry e2 = newEntry("h2", "m2", ErrorReportSeverity.MEDIUM, 1L); // skipped
        AggregatedEntry e3 = newEntry("h3", "m3", ErrorReportSeverity.CRITICAL, 12L);
        given(aggregator.drainAndClear()).willReturn(Map.of("h1", e1, "h2", e2, "h3", e3));

        batch.doFlush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, AggregatedEntry>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notifier).notifyAggregatedSummary(captor.capture());
        Map<String, AggregatedEntry> sent = captor.getValue();
        // h2 (count=1) はフィルタされ、h1 と h3 のみ送信される
        assertThat(sent).containsOnlyKeys("h1", "h3");
        assertThat(sent.get("h1").occurrenceCount()).isEqualTo(5L);
        assertThat(sent.get("h3").occurrenceCount()).isEqualTo(12L);
    }

    @Test
    @DisplayName("enabled=false の場合は drainAndClear / notifier ともに呼ばれない")
    void disabled_skipsEverything() {
        ReflectionTestUtils.setField(batch, "enabled", false);

        batch.flush();

        verify(aggregator, never()).drainAndClear();
        verify(notifier, never()).notifyAggregatedSummary(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("doFlush 中に notifier が例外を投げても flush() は伝搬させない（warn ログのみ）")
    void exceptionInNotifier_isSwallowedByOuterFlush() throws Exception {
        ReflectionTestUtils.setField(batch, "enabled", true);
        given(aggregator.drainAndClear()).willThrow(new RuntimeException("DB down"));

        // 例外が伝搬しないこと。
        batch.flush();
    }

    /**
     * package-private な AggregatedEntry コンストラクタをリフレクションで呼び、count を任意に設定する。
     */
    private static AggregatedEntry newEntry(String hash, String msg, ErrorReportSeverity sev, long count) throws Exception {
        Constructor<AggregatedEntry> ctor = AggregatedEntry.class
                .getDeclaredConstructor(String.class, String.class, ErrorReportSeverity.class, Instant.class);
        ctor.setAccessible(true);
        AggregatedEntry e = ctor.newInstance(hash, msg, sev, Instant.now());
        // 初期 count=1。recordOccurrence を count-1 回呼んで指定値まで進める。
        for (long i = 1; i < count; i++) {
            java.lang.reflect.Method m = AggregatedEntry.class.getDeclaredMethod("recordOccurrence", Instant.class);
            m.setAccessible(true);
            m.invoke(e, Instant.now());
        }
        return e;
    }
}
