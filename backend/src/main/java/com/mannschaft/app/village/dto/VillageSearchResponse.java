package com.mannschaft.app.village.dto;

import lombok.Builder;

import java.util.List;

/**
 * 村検索レスポンス DTO（F17.1 §4.2）。
 *
 * <p>{@link VillageResponse} を簡略化した検索向け形式。
 * UNLISTED 村・archived / deleted 済み村は含めない。</p>
 *
 * @param content       検索結果リスト
 * @param totalElements 総件数
 * @param page          ページ番号（0 始まり）
 * @param size          ページサイズ
 */
@Builder
public record VillageSearchResponse(
        List<VillageResponse> content,
        long totalElements,
        int page,
        int size
) {}
