package com.mannschaft.app.line.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SNSフィードプレビューレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class SnsFeedPreviewResponse {

    private final String provider;
    private final String accountUsername;
    private final List<FeedItem> items;

    /**
     * フィードの各投稿アイテム。
     */
    @Getter
    @RequiredArgsConstructor
    public static class FeedItem {
        private final String postId;
        private final String imageUrl;
        private final String caption;
        private final String permalink;
        private final LocalDateTime postedAt;
    }
}
