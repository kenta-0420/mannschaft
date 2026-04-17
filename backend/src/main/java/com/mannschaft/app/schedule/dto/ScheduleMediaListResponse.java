package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * スケジュールメディア一覧レスポンス DTO。
 * F03.12 カレンダー予定メディア管理。
 */
@Getter
@Builder
public class ScheduleMediaListResponse {

    /** メディア一覧 */
    private List<ScheduleMediaResponse> items;

    /** 総件数 */
    private long totalCount;

    /** 現在ページ番号（1始まり） */
    private int page;

    /** ページサイズ */
    private int size;

    /** 次ページ存在フラグ */
    private boolean hasNext;
}
