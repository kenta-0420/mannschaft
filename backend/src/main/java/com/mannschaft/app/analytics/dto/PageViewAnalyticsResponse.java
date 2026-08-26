package com.mannschaft.app.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * アクセス解析レスポンス DTO（GET /api/v1/teams/{slug}/analytics 等）。
 *
 * <p>FE 型 {@code AnalyticsResponse}（{@code frontend/app/types/analytics.ts}）と命名・型を厳密一致させる。
 * 全フィールド非 null 必須（{@code null} を返すと FE での型エラー源になる・AC-08・AC-15）。</p>
 *
 * <ul>
 *   <li>{@code summary} — {@link SummaryDto}（PV サマリ）</li>
 *   <li>{@code daily} — {@link DailyDto} の配列（日次推移。日付は "YYYY-MM-DD" 文字列）</li>
 *   <li>{@code monthly} — {@link MonthlyDto} の配列（月次推移。月は "YYYY-MM" 文字列）</li>
 *   <li>{@code topContent} — {@link ContentRankingDto} の配列（第 2 弾で実データ・人気コンテンツランキング・AC-P2-8）</li>
 * </ul>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageViewAnalyticsResponse {

    /** PV サマリ（非 null）。 */
    private SummaryDto summary;

    /** 日次配列（空配列可・非 null・AC-15）。 */
    private List<DailyDto> daily;

    /** 月次配列（空配列可・非 null）。 */
    private List<MonthlyDto> monthly;

    /**
     * 人気コンテンツランキング（第 2 弾で実データ・空配列可・非 null・AC-P2-8）。
     * FE 型 {@code ContentRanking[]} に対応。
     */
    private List<ContentRankingDto> topContent;

    // ─── ネスト DTO ──────────────────────────────────────────

    /**
     * PV サマリ。FE 型 {@code PageViewStats} に対応。全フィールド非 null。
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SummaryDto {
        /** 総 PV 数。 */
        private long totalViews;
        /** ユニーク訪問者数。 */
        private long uniqueVisitors;
        /** メンバー閲覧数。 */
        private long memberViews;
        /** ゲスト閲覧数。 */
        private long guestViews;
    }

    /**
     * 日次値。FE 型 {@code DailyPageView} に対応。
     * {@code date} は "YYYY-MM-DD" 形式の文字列（AC-16）。
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyDto {
        /** 日付文字列（"YYYY-MM-DD"）。 */
        private String date;
        /** PV 数。 */
        private long views;
        /** ユニーク訪問者数。 */
        private long uniqueVisitors;
    }

    /**
     * 月次値。FE 型 {@code MonthlyPageView} に対応。
     * {@code month} は "YYYY-MM" 形式の文字列（AC-16）。
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyDto {
        /** 年月文字列（"YYYY-MM"）。 */
        private String month;
        /** PV 数。 */
        private long views;
        /** ユニーク訪問者数。 */
        private long uniqueVisitors;
    }

    /**
     * 人気コンテンツランキング 1 件。FE 型 {@code ContentRanking} に対応。全フィールド非 null（第 2 弾で実装）。
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContentRankingDto {
        private String contentType;
        private long contentId;
        private String title;
        private String url;
        private long views;
        private long uniqueVisitors;
    }
}
