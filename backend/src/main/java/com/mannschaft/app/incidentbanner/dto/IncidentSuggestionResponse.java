package com.mannschaft.app.incidentbanner.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 障害告知バナーの検知候補レスポンス（シスアド用）。
 *
 * <p>エラーテレメトリ（CRITICAL/HIGH × NEW/INVESTIGATING/REOPENED）から
 * 機械的に検知した「バナー化候補」を管理者に提示する。検知は気づきであり、
 * 実際の公開は人が決める（ハイブリッド方式）。</p>
 */
@Getter
@Builder
public class IncidentSuggestionResponse {

    /** 検知元のページパターン（ワイルドカード化済み）。 */
    private final String pagePattern;

    /** 重要度（CRITICAL / HIGH）。 */
    private final String severity;

    /** 発生回数。 */
    private final long occurrenceCount;

    /** 影響ユーザー数。 */
    private final long affectedUserCount;

    /** 初回発生日時。 */
    private final LocalDateTime since;
}
