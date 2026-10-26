package com.mannschaft.app.schedule.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

/**
 * キープ（日付未定の予定）作成リクエストDTO（F03.17 §4.2）。
 *
 * <p><b>ADHD 要件（唯一の必須入力）</b>: {@code title} 以外は全て任意。
 * {@code memo}/{@code candidateDates} を欠落・{@code null} のまま送っても 400 にならない。
 * バリデーションは bean validation ではなく {@code ScheduleKeepService} で行う
 * （エラーコードを {@code SCHEDULE_KEEP_002}/{@code _003}/{@code _004} に厳密に分けるため）。</p>
 */
@Getter
public class CreateScheduleKeepRequest {

    private final String title;
    private final String memo;
    private final List<String> candidateDates;

    @JsonCreator
    public CreateScheduleKeepRequest(
            @JsonProperty("title") String title,
            @JsonProperty("memo") String memo,
            @JsonProperty("candidateDates") List<String> candidateDates) {
        this.title = title;
        this.memo = memo;
        this.candidateDates = candidateDates;
    }
}
