package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

/**
 * F22.1 第二波: 統合「要対応」集計レスポンス DTO。
 *
 * <p>回覧板（circulation）・アンケート（survey）・出席確認（attendance）の 3 ドメインを
 * 跨ぐ読み取り集計を 1 つの JSON に集約する。各区分は独立して縮退可能で、
 * 1 ドメインが例外を投げても当該区分のみ 0 件になり他区分は返る（02 §3.4 / 04 §5.3）。</p>
 *
 * <p>JSON は設計書 02 §3.4 のレスポンス例に合わせて snake_case で出力する
 * （第一波 {@code ScopeTabItemResponse} と同じ流儀）。FE 型 {@code ActionRequiredSummary}
 * （camelCase）との差異はプロジェクトの命名変換規約に委ねる。</p>
 *
 * <p>設計書: docs/features/F22.1_swipe_scope_dashboard/02_api_design.md §3.4 /
 * 04_widgets.md §5</p>
 */
@Builder
public record ActionRequiredSummaryResponse(

        /** 回覧板（未確認）区分。 */
        @JsonProperty("circulation") CirculationSection circulation,

        /** アンケート（未回答）区分。 */
        @JsonProperty("survey") SurveySection survey,

        /** 出席確認（未回答）区分。 */
        @JsonProperty("attendance") AttendanceSection attendance,

        /** 3 区分の未対応合計（タグバッジにも反映）。 */
        @JsonProperty("total_action_count") long totalActionCount
) {

    /**
     * 回覧板区分。
     */
    @Builder
    public record CirculationSection(
            @JsonProperty("unconfirmed_count") long unconfirmedCount,
            @JsonProperty("items") List<CirculationItem> items
    ) {
    }

    /**
     * アンケート区分。
     */
    @Builder
    public record SurveySection(
            @JsonProperty("unanswered_count") long unansweredCount,
            @JsonProperty("items") List<SurveyItem> items
    ) {
    }

    /**
     * 出席確認区分。
     */
    @Builder
    public record AttendanceSection(
            @JsonProperty("unanswered_count") long unansweredCount,
            @JsonProperty("items") List<AttendanceItem> items
    ) {
    }

    /**
     * 回覧板の未確認アイテム（直近 3 件）。
     */
    @Builder
    public record CirculationItem(
            @JsonProperty("id") Long id,
            @JsonProperty("title") String title,
            @JsonProperty("circulated_at") java.time.LocalDateTime circulatedAt,
            @JsonProperty("deadline") java.time.LocalDate deadline
    ) {
    }

    /**
     * アンケートの未回答アイテム（直近 3 件）。
     */
    @Builder
    public record SurveyItem(
            @JsonProperty("id") Long id,
            @JsonProperty("title") String title,
            @JsonProperty("deadline") java.time.LocalDateTime deadline
    ) {
    }

    /**
     * 出席確認の未回答アイテム（直近 3 件）。
     */
    @Builder
    public record AttendanceItem(
            @JsonProperty("schedule_id") Long scheduleId,
            @JsonProperty("event_title") String eventTitle,
            @JsonProperty("starts_at") java.time.LocalDateTime startsAt
    ) {
    }
}
