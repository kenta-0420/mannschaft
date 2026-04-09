package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * F02.5 行動メモ 終業時まとめ投稿レスポンス。
 *
 * <p>設計書 §4 に従い、新規作成された {@code timeline_post_id} と集計件数、対象日を返す。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishDailyResponse {

    @JsonProperty("timeline_post_id")
    private Long timelinePostId;

    @JsonProperty("memo_count")
    private int memoCount;

    @JsonProperty("memo_date")
    private LocalDate memoDate;
}
