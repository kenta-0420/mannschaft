package com.mannschaft.app.schedule.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * キープを予定へ変換するリクエストDTO（F03.17 §4.5）。
 *
 * <p>{@code startAt} のみが必須。{@code allDay} は<b>省略時 true</b>（§4.5.3 の
 * 「時刻入力を強制しない」を成立させるため、既定を終日にする）。</p>
 *
 * <p>本 DTO は<b>リクエストボディ経由＝Jackson 経路</b>で束縛される。クエリパラメータ経路
 * （{@code ConversionService}）とは独立であり、片方の日時変換を直しても他方には届かない
 * （memory {@code project_datetime_binding_path_asymmetry}）。</p>
 *
 * <p>全フィールド final の複数コンストラクタ DTO は Jackson が生成子を見つけられず POST が 500 に
 * なるため、{@link JsonCreator} を明示する（memory
 * {@code feedback_dto_all_final_multi_constructor_jackson_no_creators}）。</p>
 */
@Getter
public class ConvertScheduleKeepRequest {

    /** 確定した開始日時（必須。{@code schedules.start_at} が NOT NULL のため）。 */
    private final LocalDateTime startAt;

    /** 終了日時（任意。{@code schedules.end_at} は NULL 許容）。 */
    private final LocalDateTime endAt;

    /** 終日か（省略時 {@code true}）。true のとき {@code startAt} の時刻を 00:00:00 に正規化する。 */
    private final Boolean allDay;

    @JsonCreator
    public ConvertScheduleKeepRequest(
            @JsonProperty("startAt") LocalDateTime startAt,
            @JsonProperty("endAt") LocalDateTime endAt,
            @JsonProperty("allDay") Boolean allDay) {
        this.startAt = startAt;
        this.endAt = endAt;
        this.allDay = allDay;
    }
}
