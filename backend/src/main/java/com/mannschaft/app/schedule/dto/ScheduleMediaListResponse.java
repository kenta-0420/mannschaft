package com.mannschaft.app.schedule.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * スケジュールメディア一覧レスポンス DTO。
 */
@Getter
@Builder
public class ScheduleMediaListResponse {

    /** メディアリスト */
    @JsonProperty("items")
    private List<ScheduleMediaResponse> items;

    /** 総件数 */
    @JsonProperty("totalCount")
    private long totalCount;

    /** 現在のページ番号（1始まり） */
    @JsonProperty("page")
    private int page;

    /** 1ページあたりの件数 */
    @JsonProperty("size")
    private int size;

    /** 次ページが存在するか */
    @JsonProperty("hasNext")
    private boolean hasNext;
}
