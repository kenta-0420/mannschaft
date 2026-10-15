package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.service.ErrorReportAggregator.AggregatedEntry;
import com.mannschaft.app.errorreport.service.ErrorReportAggregator.AggregationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F10.6 §5.6-① — {@link ErrorReportAggregator} の単体テスト。
 *
 * <p>初回は FIRST_OCCURRENCE、2 通目以降は BUFFERED、上限到達は BUFFER_FULL を返すこと、
 * {@link ErrorReportAggregator#drainAndClear()} で内部状態が正しくリセットされることを検証する。</p>
 */
@DisplayName("ErrorReportAggregator 単体テスト (F10.6 §5.6-①)")
class ErrorReportAggregatorTest {

    @Test
    @DisplayName("初回発火は FIRST_OCCURRENCE を返す")
    void firstOccurrence_returnsFirstOccurrence() {
        ErrorReportAggregator aggregator = new ErrorReportAggregator(100);
        AggregationResult result = aggregator.addOccurrence("hash-1", "msg", ErrorReportSeverity.HIGH);
        assertThat(result).isEqualTo(AggregationResult.FIRST_OCCURRENCE);
        assertThat(aggregator.currentBufferSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("2 通目以降の同一 hash は BUFFERED を返し、occurrenceCount がインクリメントされる")
    void secondOccurrence_returnsBuffered_andIncrements() {
        ErrorReportAggregator aggregator = new ErrorReportAggregator(100);
        aggregator.addOccurrence("hash-2", "msg", ErrorReportSeverity.HIGH);
        AggregationResult r2 = aggregator.addOccurrence("hash-2", "msg", ErrorReportSeverity.HIGH);
        AggregationResult r3 = aggregator.addOccurrence("hash-2", "msg", ErrorReportSeverity.HIGH);
        assertThat(r2).isEqualTo(AggregationResult.BUFFERED);
        assertThat(r3).isEqualTo(AggregationResult.BUFFERED);

        Map<String, AggregatedEntry> drained = aggregator.drainAndClear();
        assertThat(drained).hasSize(1);
        assertThat(drained.get("hash-2").occurrenceCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("上限到達後は BUFFER_FULL を返し、occurrenceCount は上限で頭打ちになる")
    void overLimit_returnsBufferFull() {
        ErrorReportAggregator aggregator = new ErrorReportAggregator(3); // 上限 3 でテスト
        // 1回目 FIRST + 2回目, 3回目 BUFFERED で上限到達 (count=3)
        assertThat(aggregator.addOccurrence("h", "m", ErrorReportSeverity.HIGH))
                .isEqualTo(AggregationResult.FIRST_OCCURRENCE);
        assertThat(aggregator.addOccurrence("h", "m", ErrorReportSeverity.HIGH))
                .isEqualTo(AggregationResult.BUFFERED);
        assertThat(aggregator.addOccurrence("h", "m", ErrorReportSeverity.HIGH))
                .isEqualTo(AggregationResult.BUFFERED);
        // 4 回目以降は BUFFER_FULL
        assertThat(aggregator.addOccurrence("h", "m", ErrorReportSeverity.HIGH))
                .isEqualTo(AggregationResult.BUFFER_FULL);
        assertThat(aggregator.addOccurrence("h", "m", ErrorReportSeverity.HIGH))
                .isEqualTo(AggregationResult.BUFFER_FULL);

        Map<String, AggregatedEntry> drained = aggregator.drainAndClear();
        assertThat(drained.get("h").occurrenceCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("複数 hash を独立に集約できる")
    void multipleHashes_areTrackedIndependently() {
        ErrorReportAggregator aggregator = new ErrorReportAggregator(100);
        aggregator.addOccurrence("h1", "m1", ErrorReportSeverity.HIGH);
        aggregator.addOccurrence("h2", "m2", ErrorReportSeverity.MEDIUM);
        aggregator.addOccurrence("h1", "m1", ErrorReportSeverity.HIGH);
        aggregator.addOccurrence("h2", "m2", ErrorReportSeverity.MEDIUM);
        aggregator.addOccurrence("h2", "m2", ErrorReportSeverity.MEDIUM);

        Map<String, AggregatedEntry> drained = aggregator.drainAndClear();
        assertThat(drained).hasSize(2);
        assertThat(drained.get("h1").occurrenceCount()).isEqualTo(2L);
        assertThat(drained.get("h2").occurrenceCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("drainAndClear 後はバッファが空になり、次回発火は再び FIRST_OCCURRENCE")
    void drainAndClear_resetsBuffer() {
        ErrorReportAggregator aggregator = new ErrorReportAggregator(100);
        aggregator.addOccurrence("h", "m", ErrorReportSeverity.HIGH);
        aggregator.addOccurrence("h", "m", ErrorReportSeverity.HIGH);
        aggregator.drainAndClear();

        assertThat(aggregator.currentBufferSize()).isZero();
        AggregationResult next = aggregator.addOccurrence("h", "m", ErrorReportSeverity.HIGH);
        assertThat(next).isEqualTo(AggregationResult.FIRST_OCCURRENCE);
    }

    @Test
    @DisplayName("errorHash が null/空の場合は BUFFER_FULL を返し、内部状態を変更しない")
    void emptyHash_isRejected() {
        ErrorReportAggregator aggregator = new ErrorReportAggregator(100);
        assertThat(aggregator.addOccurrence(null, "m", ErrorReportSeverity.HIGH))
                .isEqualTo(AggregationResult.BUFFER_FULL);
        assertThat(aggregator.addOccurrence("", "m", ErrorReportSeverity.HIGH))
                .isEqualTo(AggregationResult.BUFFER_FULL);
        assertThat(aggregator.addOccurrence("   ", "m", ErrorReportSeverity.HIGH))
                .isEqualTo(AggregationResult.BUFFER_FULL);
        assertThat(aggregator.currentBufferSize()).isZero();
    }
}
