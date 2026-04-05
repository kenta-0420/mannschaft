package com.mannschaft.app.advertising.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 広告セグメント抽出レスポンス。
 * チーム単位のセグメント情報を返す。
 */
@Getter
@RequiredArgsConstructor
public class AdSegmentResponse {

    private final Long teamId;
    private final String teamName;
    private final String template;
    private final String prefecture;
    private final String city;
    private final long memberCount;
    private final long scheduleCountLast30Days;
    private final String topVenueName;
}
