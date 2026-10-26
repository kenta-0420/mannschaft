package com.mannschaft.app.village.dto;

import lombok.Builder;

import java.util.List;

/**
 * 公開ニュースレター号一覧（村横断）ページ レスポンス DTO（F17.1 ②-4・設計書 §8.2）。
 *
 * @param content       公開号リスト（新しい順）
 * @param totalElements 総件数
 * @param page          ページ番号（0 始まり）
 * @param size          ページサイズ
 */
@Builder
public record PublicNewsletterIssuePageResponse(
        List<PublicNewsletterIssueResponse> content,
        long totalElements,
        int page,
        int size
) {}
