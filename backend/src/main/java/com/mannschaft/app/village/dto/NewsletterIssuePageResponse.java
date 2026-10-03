package com.mannschaft.app.village.dto;

import lombok.Builder;

import java.util.List;

/**
 * 村ニュースレター号一覧 ページ レスポンス DTO（F17.1 ②-4・設計書 §8.1）。
 *
 * <p>ページ形は {@link VillageSearchResponse} の金型（content / totalElements / page / size）に倣う。</p>
 *
 * @param content       号要約リスト（新しい順）
 * @param totalElements 総件数
 * @param page          ページ番号（0 始まり）
 * @param size          ページサイズ
 */
@Builder
public record NewsletterIssuePageResponse(
        List<NewsletterIssueSummaryResponse> content,
        long totalElements,
        int page,
        int size
) {}
