package com.mannschaft.app.schedule.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * キープ → 予定 変換のレスポンスDTO（F03.17 §4.5）。
 *
 * <p>変換は「キープの状態遷移」と「予定の新規生成」の2つの結果を同時に生むため、
 * 呼び出し側が追加の GET を打たずに両方を受け取れるよう {@code keep} と {@code schedule} を並べる
 * （FE は変換直後に予定へ遷移できる＝§4.5.3 の「2タップ以内」を API 往復で崩さない）。</p>
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConvertScheduleKeepResponse {

    /** 変換後のキープ（{@code status=SCHEDULED}・{@code convertedScheduleId} 非 null）。 */
    private final ScheduleKeepResponse keep;

    /** 変換で新規生成された予定の要約。 */
    private final ConvertedScheduleDto schedule;

    /**
     * 変換先 {@code schedules} の要約。
     *
     * <p>予定の完全な表現は既存の schedule API が返す。ここでは変換直後の遷移に足りる最小限
     * （ID・タイトル・日時）だけを載せ、{@code schedules} のレスポンス契約を二重管理しない。</p>
     */
    @Getter
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConvertedScheduleDto {
        private final Long id;
        private final String title;
        private final LocalDateTime startAt;
        private final LocalDateTime endAt;
        private final Boolean allDay;
    }
}
