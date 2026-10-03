package com.mannschaft.app.advertising.dto;

import java.time.LocalDateTime;

/**
 * 広告クリエイティブレスポンス。
 */
public record AdCreativeResponse(
        Long id,
        Long campaignId,
        String title,
        String imageUrl,
        String destinationUrl,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        // ─── F09.19.1 拡張（骨格） ───
        /** 掲載面。 */
        com.mannschaft.app.advertising.AdPlacement placement,
        /** バナー幅 px。 */
        Integer width,
        /** バナー高さ px。 */
        Integer height,
        /** 代替テキスト。 */
        String altText
) {
}
